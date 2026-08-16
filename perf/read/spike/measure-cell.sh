#!/bin/bash

# One measured read spike cell, shared by every leaf under read/spike: warm up,
# step the request rate far above what the pool can serve and back down, then
# append one row to the leaf's journal with a recovery verdict. Defines
# perf_read_spike_cell, which the harness (perf/lib/harness.sh) must already be
# sourced for.
#
# Separate from read/load/measure-cell.sh on purpose: a surge is measured in
# phases against a recovery verdict, not as one steady window, so the two share
# the harness and nothing else.
#
# The measured run is tolerated failing (|| true): a surge is allowed to shed,
# so k6's recovery threshold tripping is data, not a reason to abort before it
# is recorded. These cells report rather than gate (see the spike README), so
# the function succeeds even on a red verdict — which is the expected outcome at
# the default corpus, and a cell that failed would stop its remaining rounds and
# take the rest of the measurements with it.
# Reads do not mutate, so the corpus needs no restoring afterwards.
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
    -e SPIKE_RATE="$spike_rate" -e SUMMARY_OUT="$summary" || true
  scans_after=$(read_scan_counters) || return 1
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
    "$schema_version" "$start_rows" "$scans_before" "$scans_after" <<'PY'
import json, sys

(
    summary_path, journal_path, date, commit, cpu, cores, pool, schema_version,
    start_rows, scans_before, scans_after,
) = sys.argv[1:12]

with open(summary_path) as f:
    s = json.load(f)

spike = s["phases"]["spike"]
recovery = s["phases"]["recovery"]
baseline = s["phases"]["baseline"]

index_before, seq_before = (int(v) for v in scans_before.split())
index_after, seq_after = (int(v) for v in scans_after.split())


def r(value, digits=2):
    return round(value, digits) if isinstance(value, (int, float)) else value


# Serving every request is not enough to call this recovered. A run can answer
# everything while still taking tens of times longer than it did before, which
# is an outage as far as a dashboard is concerned, so latency has to come back
# too. The multiple is deliberately wide — ordinary run-to-run jitter moves this
# figure by a factor of two, and a gate that trips on jitter teaches people to
# ignore it. What it has to catch is the real case, and that one is not close:
# a surge past the pool's ceiling leaves recovery an order of magnitude slow.
RECOVERY_LATENCY_FACTOR = 5

# Recovery is judged against the baseline phase, so the baseline has to be a
# healthy state or none of the rest means anything. Absolute, not relative: a
# ratio to a collapsed baseline is satisfied by a system that never recovered,
# and the index experiment measured exactly that — 28s recovery against a 15196ms
# baseline was called recovered, while the same test on a healthy 123ms baseline
# was not.
#
# 1000ms sits an order of magnitude above the healthy figure and an order of
# magnitude below the collapsed one, so it separates the two cases without
# tripping on jitter. It is a property of these workloads at BASELINE_RATE, not a
# universal number: raise it deliberately if the baseline rate or the pool
# changes, rather than to make a red run go green.
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
    "scenario": s["scenario"],
    "endpoint": s["endpoint"],
    "group_by": s["group_by"],
    "limit": s["limit"],
    "window": s["window"],
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
    "index_scans": index_after - index_before,
    "seq_scans": seq_after - seq_before,
    "recovered": recovered,
}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")

# The verdict is journalled even though it is derived, because the row is meant to
# answer its own question: numbers without a conclusion mean going to read this
# file's rule and applying it by hand. Which rule produced a given verdict is
# already answered by the row's `commit` stamp — the same mechanism every other
# field relies on — so a stored verdict from before the baseline clause existed is
# attributable rather than merely stale.
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
}
