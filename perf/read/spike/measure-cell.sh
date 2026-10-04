#!/bin/bash

# Warm, measure, and journal one read surge with its recovery verdict. Requires
# perf/lib/harness.sh; reads need no cleanup.
#
# Tolerate k6 failures to record overload. A failed recovery verdict does not fail the
# function, so remaining rounds still run.
#
# Usage: perf_read_spike_cell <journal> <endpoint> <spike_rate> [group_by] [limit]

perf_read_spike_cell() {
  local journal=$1 endpoint=$2 spike_rate=$3 group_by=${4:-} limit=${5:-}
  local script=perf/read/spike/stats-spike.js
  local summary=perf/read/spike/last-summary.json

  # Warms JIT and the pool if no read cell has already done so, so the surge
  # measures the surge rather than cold start.
  warm_reads "$endpoint" "$group_by"

  local start_rows scans_before scans_after
  count_events || return 1
  start_rows=$CORPUS_ROWS
  ARTIFACT_COMMIT=$(read_artifact_commit) || return 1
  scans_before=$(read_scan_counters) || return 1

  # The query and the rate are handed to k6 explicitly rather than left in the
  # environment: each is a per-cell decision, and an exported one would outlive
  # its cell and quietly re-point or re-rate the next.
  rm -f "$summary"
  k6_run "$script" \
    --env SEED_ANCHOR --env SEED_SPREAD_DAYS --env SPIKE_WINDOW \
    --env BASELINE_RATE \
    --env BASELINE_SECONDS --env SPIKE_SECONDS --env RECOVERY_SECONDS --env MAX_VUS \
    -e ENDPOINT="$endpoint" -e GROUP_BY="$group_by" -e LIMIT="$limit" \
    -e TOKEN="$SEED_TOKEN" \
    -e SPIKE_RATE="$spike_rate" -e SUMMARY_OUT="$summary" || true
  scans_after=$(read_scan_counters) || return 1
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
    "$request_metrics" "$scrape" "$schema_version" "$start_rows" \
    "$scans_before" "$scans_after" <<'PY'
import json, sys

(
    summary_path, journal_path, date, commit, cpu, cores, pool, request_metrics,
    scrape, schema_version, start_rows, scans_before, scans_after,
) = sys.argv[1:14]

with open(summary_path) as f:
    s = json.load(f)

spike = s["phases"]["spike"]
recovery = s["phases"]["recovery"]
baseline = s["phases"]["baseline"]

before = json.loads(scans_before)
after = json.loads(scans_after)
index_scans_by_index = {
    name: scans - before["by_index"].get(name, 0)
    for name, scans in after["by_index"].items()
}


def r(value, digits=2):
    return round(value, digits) if isinstance(value, (int, float)) else value


# Recovery requires latency to return as well as successful responses. The 5x margin
# tolerates baseline jitter while catching sustained queueing.
RECOVERY_LATENCY_FACTOR = 5

# Require an absolute healthy baseline before applying the recovery ratio. Otherwise 28s
# recovery against a 15196ms baseline passes. The 1000ms bound separates healthy from
# collapsed runs on this rig; reconsider it when changing baseline load or pool size.
BASELINE_MAX_P95_MS = 1000

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
    "endpoint": s["endpoint"],
    "group_by": s["group_by"],
    "limit": s["limit"],
    "window": s["window"],
    "schema_version": schema_version,
    "cpu": cpu,
    "cores": int(cores),
    "pool": int(pool),
    "request_metrics": request_metrics,
    "scrape": scrape,
    "start_rows": int(start_rows),
    "baseline_rate": s["baseline_rate"],
    "spike_rate": s["spike_rate"],
    "max_vus": s["max_vus"],
    "spike_seconds": s["seconds"]["spike"],
    "spike_started_at": spike["started_at"],
    "spike_finished_at": spike["finished_at"],
    "spike_achieved_rps": r(spike["achieved_rps"], 1),
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
    "index_scans": sum(index_scans_by_index.values()),
    "index_scans_by_index": index_scans_by_index,
    "seq_scans": after["seq_scans"] - before["seq_scans"],
    "recovered": recovered,
}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")

# Store the verdict for direct interpretation. The commit stamp identifies the rule
# version, including rows predating the baseline check.
if not baseline_valid:
    verdict = "NO VALID BASELINE"
elif recovered:
    verdict = "recovered"
elif served:
    verdict = "STILL DRAINING"
else:
    verdict = "DID NOT RECOVER"

query = row["endpoint"]
if row["group_by"]:
    query += " groupBy=" + row["group_by"]
if row["limit"]:
    query += " limit=" + str(row["limit"])

print("\nAppended to " + journal_path + ":")
print(json.dumps(row))
print(
    "PERF_RESULT read spike (%s %s): %s | baseline %s of %s rps, p95 %sms | "
    "spike →%d rps achieved %s, dropped %d, %s%% failed, p99 %sms | "
    "recovery %s%% failed, p95 %sms"
    % (
        query,
        row["window"],
        verdict,
        row["baseline_achieved_rps"],
        row["baseline_rate"],
        row["baseline_p95_ms"],
        row["spike_rate"],
        row["spike_achieved_rps"],
        row["spike_dropped"],
        r(spike["failed_rate"] * 100, 2) if isinstance(spike["failed_rate"], (int, float)) else "n/a",
        row["spike_p99_ms"],
        r(recovery["failed_rate"] * 100, 2)
        if isinstance(recovery["failed_rate"], (int, float))
        else "n/a",
        row["recovery_p95_ms"],
    )
)
PY
  ) || return 1
  echo "$out"
  perf_result "$(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')"
  perf_journalled "$journal"
}
