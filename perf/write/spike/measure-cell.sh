#!/bin/bash

# One measured write spike cell, shared by every leaf under write/spike: warm up,
# step the request rate far above steady-state capacity and back down, put the
# corpus back, and append one row with a recovery verdict to the leaf's journal.
# Defines perf_write_spike_cell, which the harness (perf/lib/harness.sh) must
# already be sourced for.
#
# Separate from write/load/measure-cell.sh on purpose: a surge is measured in
# phases against a verdict, not as one steady window, so the two share the
# harness and nothing else.
#
# The measured run is tolerated failing (|| true): a spike is allowed to shed, so
# k6's recovery threshold tripping is data, not a reason to abort before it is
# recorded. The function returns non-zero only when the app did not recover.
#
# Everything a cell differs by arrives as an argument rather than being read here,
# because each is a property of the request shape being surged rather than of a
# surge:
#   - the scenario, and the load scenario used to warm for it;
#   - the surge rate, derived per cell from its own load journal;
#   - the batch size, when the shape sends more than one event per request;
#   - the ceiling on a healthy baseline. That last one is not a formality: it
#     asks "is the baseline phase a state worth measuring recovery against", and
#     the answer scales with the unit of work. A single insert answers in ~2ms, a
#     batch of a hundred cannot, and a bound written for one would report the
#     other as having no valid baseline on every round.
#
# Usage: perf_write_spike_cell <journal> <script> <warmup_script> <spike_rate>
#                              <baseline_max_p95_ms> [batch_size]

perf_write_spike_cell() {
  local journal=$1 script=$2 warmup_script=$3 spike_rate=$4 baseline_max_p95_ms=$5
  local batch_size=${6:-}
  local summary=perf/write/spike/last-summary.json
  local batch=()
  [ -n "$batch_size" ] && batch=(-e BATCH_SIZE="$batch_size")

  # Warm JIT and pool with the matching steady load scenario before the surge, so
  # the spike hits a warmed app and measures the surge, not cold start. Its rows
  # are then dropped, putting the table back to the seeded corpus.
  k6_run "$warmup_script" "${batch[@]}" -e TOKEN="$WRITE_TOKEN" \
    -e VUS=10 -e DURATION=20s -e SUMMARY_OUT=/dev/null || true
  restore_seed_baseline || return 1

  local start_rows
  count_events || return 1
  start_rows=$CORPUS_ROWS

  rm -f "$summary"
  k6_run "$script" "${batch[@]}" -e TOKEN="$WRITE_TOKEN" \
    --env BASELINE_RATE \
    --env BASELINE_SECONDS --env SPIKE_SECONDS --env RECOVERY_SECONDS --env MAX_VUS \
    -e SPIKE_RATE="$spike_rate" -e SUMMARY_OUT="$summary" || true
  restore_seed_baseline || return 1
  [ -s "$summary" ] || {
    echo "Spike run produced no summary at $summary — did the app stay up?" >&2
    return 1
  }

  local pool schema_version
  pool=$(read_pool) || return 1
  schema_version=$(read_schema) || return 1

  local out
  out=$(python3 - "$summary" "$journal" "$(date -u +%Y-%m-%d)" "$(git rev-parse --short HEAD)" \
    "$(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')" "$(nproc)" "$pool" \
    "${INGEST_PATH:-sync}" "$schema_version" "$start_rows" "$baseline_max_p95_ms" <<'PY'
import json, sys

(
    summary_path, journal_path, date, commit, cpu, cores, pool, ingest_path,
    schema_version, start_rows, baseline_max_p95_ms,
) = sys.argv[1:12]
with open(summary_path) as f:
    s = json.load(f)

spike = s["phases"]["spike"]
recovery = s["phases"]["recovery"]
baseline = s["phases"]["baseline"]


def r(value, digits=2):
    return round(value, digits) if isinstance(value, (int, float)) else value


# What the two shapes can be surged against each other over: their request rates
# differ by the batch size, so only the events behind them compare. Journalled
# only where it says something the row does not already say — a scenario posting
# one event per request reports no batch size, and for it the achieved request
# rate already is the achieved event rate.
batch_size = s.get("batch_size")


def events(value):
    return r(value * batch_size, 1) if isinstance(value, (int, float)) else value


# Same definition of recovered the read spike uses: serving every request is not
# enough if each one now takes far longer than it did before the surge. The
# multiple is wide because run-to-run jitter already moves this figure by about
# a factor of two, and a gate that trips on jitter teaches people to ignore it.
RECOVERY_LATENCY_FACTOR = 5

# The read spike's precondition, for the same reason: everything else here is
# relative to the baseline phase, so a baseline that has itself collapsed is not a
# reference. The bound comes from the cell rather than from here, because what
# counts as a healthy baseline is a property of the request being surged — see the
# usage note above.
BASELINE_MAX_P95_MS = float(baseline_max_p95_ms)

baseline_valid = (
    isinstance(baseline["p95_ms"], (int, float))
    and baseline["p95_ms"] <= BASELINE_MAX_P95_MS
)
served = isinstance(recovery["failed_rate"], (int, float)) and recovery["failed_rate"] < 0.01
drained = (
    isinstance(recovery["p95_ms"], (int, float))
    and isinstance(baseline["p95_ms"], (int, float))
    and recovery["p95_ms"] <= baseline["p95_ms"] * RECOVERY_LATENCY_FACTOR
)
recovered = baseline_valid and served and drained

row = {
    "date": date,
    "commit": commit,
    "scenario": s["scenario"],
    "ingest_path": ingest_path,
    "schema_version": schema_version,
    "cpu": cpu,
    "cores": int(cores),
    "pool": int(pool),
    "start_rows": int(start_rows),
    "batch_size": batch_size,
    "baseline_rate": s["baseline_rate"],
    "spike_rate": s["spike_rate"],
    "max_vus": s["max_vus"],
    "spike_seconds": s["seconds"]["spike"],
    "baseline_max_p95_ms": round(BASELINE_MAX_P95_MS, 2),
    "spike_achieved_rps": r(spike["achieved_rps"], 1),
    "spike_achieved_events_per_sec": events(spike["achieved_rps"]) if batch_size else None,
    "spike_dropped": round(spike["dropped"]) if isinstance(spike["dropped"], (int, float)) else 0,
    "spike_failed_rate": r(spike["failed_rate"], 4),
    "spike_p95_ms": r(spike["p95_ms"]),
    "spike_p99_ms": r(spike["p99_ms"]),
    "spike_max_ms": r(spike["max_ms"]),
    "recovery_failed_rate": r(recovery["failed_rate"], 4),
    "recovery_p95_ms": r(recovery["p95_ms"]),
    "baseline_achieved_rps": r(baseline["achieved_rps"], 1),
    "baseline_p95_ms": r(baseline["p95_ms"]),
    "recovered": recovered,
}
row = {name: value for name, value in row.items() if value is not None}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")

# Journalled, so the row answers its own question rather than leaving a reader to
# apply this file's rule by hand. Which rule produced it is already answered by the
# row's `commit` stamp, as it is for every other field.
if not baseline_valid:
    verdict = "NO VALID BASELINE"
elif recovered:
    verdict = "recovered"
elif served:
    verdict = "STILL DRAINING"
else:
    verdict = "DID NOT RECOVER"

print("\nAppended to " + journal_path + ":")
print(json.dumps(row))
print(
    "PERF_RESULT write spike %s: %s | baseline %s of %s rps, p95 %sms | spike →%d rps "
    "achieved %s%s, dropped %d, %s%% failed, p99 %sms | recovery %s%% failed, "
    "p95 %sms"
    % (
        row["scenario"],
        verdict,
        row["baseline_achieved_rps"],
        row["baseline_rate"],
        row["baseline_p95_ms"],
        row["spike_rate"],
        row["spike_achieved_rps"],
        " (%s events/s)" % row["spike_achieved_events_per_sec"] if batch_size else "",
        row["spike_dropped"],
        r(spike["failed_rate"] * 100, 2) if isinstance(spike["failed_rate"], (int, float)) else "n/a",
        row["spike_p99_ms"],
        r(recovery["failed_rate"] * 100, 2) if isinstance(recovery["failed_rate"], (int, float)) else "n/a",
        row["recovery_p95_ms"],
    )
)
print("PERF_STATUS " + ("ok" if recovered else "unrecovered"))
PY
  )
  echo "$out"
  perf_result "$(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')"
  [ "$(printf '%s\n' "$out" | sed -n 's/^PERF_STATUS //p')" = ok ]
}
