#!/bin/bash

# Spike test: how the ingest path behaves when request rate suddenly steps far
# above steady-state capacity, and whether it recovers afterwards. Defines
# perf_spike, which the harness (perf/lib/harness.sh) must already be sourced
# for. Appends one row to perf/write/spike/journal.jsonl — its own series, never
# merged with the load journal (different scenario, different meaning).
#
# The measured run is tolerated failing (|| true): a spike is allowed to shed,
# so k6's recovery threshold tripping is data, not a reason to abort before we
# record it. perf_spike returns non-zero only when the app did not recover.
#
# Tunables via env: BASELINE_RATE, SPIKE_RATE (must exceed the load test's
# throughput), BASELINE_SECONDS, SPIKE_SECONDS, RECOVERY_SECONDS, MAX_VUS.

perf_spike() {
  local script=perf/write/spike/spike-events.js
  local summary=perf/write/spike/last-summary.json
  local journal=perf/write/spike/journal.jsonl

  # Warm JIT and pool with the steady load scenario before the surge, so the
  # spike hits a warmed app and measures the surge, not cold start. Its rows are
  # then dropped, putting the table back to the seeded corpus.
  k6_run perf/write/load/ingest-events.js -e VUS=10 -e DURATION=20s -e SUMMARY_OUT=/dev/null || true
  restore_seed_baseline || return 1

  local start_rows
  count_events || return 1
  start_rows=$CORPUS_ROWS

  rm -f "$summary"
  k6_run "$script" \
    --env BASELINE_RATE --env SPIKE_RATE \
    --env BASELINE_SECONDS --env SPIKE_SECONDS --env RECOVERY_SECONDS --env MAX_VUS \
    -e SUMMARY_OUT="$summary" || true
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
    "${INGEST_PATH:-sync}" "$schema_version" "$start_rows" <<'PY'
import json, sys

(
    summary_path, journal_path, date, commit, cpu, cores, pool, ingest_path,
    schema_version, start_rows,
) = sys.argv[1:11]
with open(summary_path) as f:
    s = json.load(f)

spike = s["phases"]["spike"]
recovery = s["phases"]["recovery"]
baseline = s["phases"]["baseline"]


def r(value, digits=2):
    return round(value, digits) if isinstance(value, (int, float)) else value


# Same definition of recovered the read spike uses: serving every request is not
# enough if each one now takes far longer than it did before the surge. The
# multiple is wide because run-to-run jitter already moves this figure by about
# a factor of two, and a gate that trips on jitter teaches people to ignore it.
RECOVERY_LATENCY_FACTOR = 5

# The read spike's precondition, for the same reason: everything else here is
# relative to the baseline phase, so a baseline that has itself collapsed is not a
# reference. Kept on this side too even though the write path has never come close
# to failing it — an insert answers in about 2ms and the baseline measures 2ms — a
# gate that only holds while the numbers stay healthy is not a gate.
#
# 50ms is more than an order of magnitude above the healthy figure, so it marks
# genuine saturation of the write path rather than jitter around it.
BASELINE_MAX_P95_MS = 50

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
    "baseline_rate": s["baseline_rate"],
    "spike_rate": s["spike_rate"],
    "max_vus": s["max_vus"],
    "spike_seconds": s["seconds"]["spike"],
    "spike_achieved_rps": r(spike["achieved_rps"], 1),
    "spike_dropped": round(spike["dropped"]) if isinstance(spike["dropped"], (int, float)) else 0,
    "spike_failed_rate": r(spike["failed_rate"], 4),
    "spike_p95_ms": r(spike["p95_ms"]),
    "spike_p99_ms": r(spike["p99_ms"]),
    "spike_max_ms": r(spike["max_ms"]),
    "recovery_failed_rate": r(recovery["failed_rate"], 4),
    "recovery_p95_ms": r(recovery["p95_ms"]),
    "baseline_achieved_rps": r(baseline["achieved_rps"], 1),
    "baseline_p95_ms": r(baseline["p95_ms"]),
}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")

# Not journalled: the verdict is derived from fields the row already carries, so
# storing it would leave a second source of truth that keeps asserting whichever
# rule was current when it was written. The gate below still uses it — computed
# fresh, every run.
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
    "PERF_RESULT spike: %s | baseline %s of %s rps, p95 %sms | spike →%d rps achieved %s, "
    "dropped %d, %s%% failed, p99 %sms | recovery %s%% failed, p95 %sms"
    % (
        verdict,
        row["baseline_achieved_rps"],
        row["baseline_rate"],
        row["baseline_p95_ms"],
        row["spike_rate"],
        row["spike_achieved_rps"],
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