#!/bin/bash

# Warm, measure, clean up, and journal a write surge. Requires perf/lib/harness.sh.
# Tolerate k6 failure long enough to record results; failed recovery returns non-zero.
#
# Each cell supplies its scenario, matching warm-up, calibrated surge rate,
# healthy-baseline latency bound, and optional batch size.
#
# Usage: perf_write_spike_cell <journal> <script> <warmup_script> <spike_rate>
# <baseline_max_p95_ms> [batch_size]

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
  ARTIFACT_COMMIT=$(read_artifact_commit) || return 1

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

  local pool schema_version request_metrics scrape started_at finished_at
  read -r started_at finished_at < <(read_run_window "$summary") || return 1
  pool=$(read_pool) || return 1
  schema_version=$(read_schema) || return 1
  request_metrics=$(read_request_metrics) || return 1
  scrape=$(read_scrape "$started_at" "$finished_at") || return 1

  local out
  out=$(python3 - "$summary" "$journal" "$(date -u +%Y-%m-%d)" "$ARTIFACT_COMMIT" \
    "$(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')" "$(nproc)" "$pool" \
    "$request_metrics" "$scrape" "${INGEST_PATH:-sync}" "$schema_version" "$start_rows" \
    "$baseline_max_p95_ms" <<'PY'
import json, sys

(
    summary_path, journal_path, date, commit, cpu, cores, pool, request_metrics,
    scrape, ingest_path, schema_version, start_rows, baseline_max_p95_ms,
) = sys.argv[1:14]
with open(summary_path) as f:
    s = json.load(f)

spike = s["phases"]["spike"]
recovery = s["phases"]["recovery"]
baseline = s["phases"]["baseline"]


def r(value, digits=2):
    return round(value, digits) if isinstance(value, (int, float)) else value


# Record achieved events/s for batches to compare request shapes. For single-event
# requests, achieved req/s already gives the event rate.
batch_size = s.get("batch_size")


def events(value):
    return r(value * batch_size, 1) if isinstance(value, (int, float)) else value


# Require recovered latency as well as successful responses. The 5x margin allows
# roughly 2x baseline jitter without masking a sustained backlog.
RECOVERY_LATENCY_FACTOR = 5

# Reject unhealthy baselines before calculating relative recovery. Each cell supplies
# the bound appropriate to its request size.
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
    "run_id": s["run_id"],
    "started_at": s["started_at"],
    "finished_at": s["finished_at"],
    "scenario": s["scenario"],
    "ingest_path": ingest_path,
    "schema_version": schema_version,
    "cpu": cpu,
    "cores": int(cores),
    "pool": int(pool),
    "request_metrics": request_metrics,
    "scrape": scrape,
    "start_rows": int(start_rows),
    "batch_size": batch_size,
    "baseline_rate": s["baseline_rate"],
    "spike_rate": s["spike_rate"],
    "max_vus": s["max_vus"],
    "spike_seconds": s["seconds"]["spike"],
    "baseline_max_p95_ms": round(BASELINE_MAX_P95_MS, 2),
    "spike_started_at": spike["started_at"],
    "spike_finished_at": spike["finished_at"],
    "spike_achieved_rps": r(spike["achieved_rps"], 1),
    "spike_achieved_events_per_sec": events(spike["achieved_rps"]) if batch_size else None,
    "spike_dropped": round(spike["dropped"]) if isinstance(spike["dropped"], (int, float)) else 0,
    "spike_failed_rate": r(spike["failed_rate"], 4),
    "spike_p95_ms": r(spike["p95_ms"]),
    "spike_p99_ms": r(spike["p99_ms"]),
    "spike_max_ms": r(spike["max_ms"]),
    "recovery_started_at": recovery["started_at"],
    "recovery_finished_at": recovery["finished_at"],
    "recovery_failed_rate": r(recovery["failed_rate"], 4),
    "recovery_p95_ms": r(recovery["p95_ms"]),
    "baseline_started_at": baseline["started_at"],
    "baseline_finished_at": baseline["finished_at"],
    "baseline_achieved_rps": r(baseline["achieved_rps"], 1),
    "baseline_p95_ms": r(baseline["p95_ms"]),
    "recovered": recovered,
}
row = {name: value for name, value in row.items() if value is not None}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")

# Journal the derived verdict; commit identifies the rule used to compute it.
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
  perf_journalled "$journal"
  [ "$(printf '%s\n' "$out" | sed -n 's/^PERF_STATUS //p')" = ok ]
}
