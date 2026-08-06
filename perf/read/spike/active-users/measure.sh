#!/bin/bash

# Read spike cell: step the dashboard request rate for /api/v1/stats/active-users
# far above what the pool can serve, then back down, and check the read path
# recovers. Defines perf_read_spike_active_users, which the harness
# (perf/lib/harness.sh) must already be sourced for. Appends one row to
# perf/read/spike/active-users/journal.jsonl — its own series, never merged with
# the read load journals or the write spike.
#
# active-users is the endpoint worth surging first because it is the heaviest
# read: its COUNT(DISTINCT user_id) reaches the heap for every matching row, so
# it exhausts the pool sooner than the other two would.
#
# The measured run is tolerated failing (|| true): a surge is allowed to shed,
# so k6's recovery threshold tripping is data, not a reason to abort before it
# is recorded. The function returns non-zero only when the app did not recover.
# Reads do not mutate, so the corpus needs no restoring afterwards.
#
# Tunables via env: ENDPOINT, GROUP_BY, SPIKE_WINDOW, BASELINE_RATE, SPIKE_RATE,
# *_SECONDS, MAX_VUS.

perf_read_spike_active_users() {
  local script=perf/read/spike/stats-spike.js
  local summary=perf/read/spike/last-summary.json
  local journal=perf/read/spike/active-users/journal.jsonl

  # Resolved once into locals rather than read from the environment at each use:
  # a preceding read cell exports nothing now, but taking the default here keeps
  # the warm-up and the measured run pointed at the same query no matter what
  # ran before.
  local endpoint=${ENDPOINT:-active-users} group_by=${GROUP_BY:-day}

  # Warms JIT and the pool if no read cell has already done so, so the surge
  # measures the surge rather than cold start.
  warm_reads "$endpoint" "$group_by"

  local start_rows scans_before scans_after
  count_events || return 1
  start_rows=$CORPUS_ROWS
  scans_before=$(read_scan_counters) || return 1

  rm -f "$summary"
  k6_run "$script" \
    --env SEED_ANCHOR --env SEED_SPREAD_DAYS --env SPIKE_WINDOW \
    --env BASELINE_RATE --env SPIKE_RATE \
    --env BASELINE_SECONDS --env SPIKE_SECONDS --env RECOVERY_SECONDS --env MAX_VUS \
    -e ENDPOINT="$endpoint" -e GROUP_BY="$group_by" -e SUMMARY_OUT="$summary" || true
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

served = isinstance(recovery["failed_rate"], (int, float)) and recovery["failed_rate"] < 0.01
drained = (
    isinstance(recovery["p95_ms"], (int, float))
    and isinstance(baseline["p95_ms"], (int, float))
    and recovery["p95_ms"] <= baseline["p95_ms"] * RECOVERY_LATENCY_FACTOR
)
recovered = served and drained

row = {
    "date": date,
    "commit": commit,
    "scenario": s["scenario"],
    "endpoint": s["endpoint"],
    "group_by": s["group_by"],
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
    "baseline_p95_ms": r(baseline["p95_ms"]),
    "index_scans": index_after - index_before,
    "seq_scans": seq_after - seq_before,
    "recovered": recovered,
}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")

if recovered:
    verdict = "recovered"
elif served:
    verdict = "STILL DRAINING"
else:
    verdict = "DID NOT RECOVER"

print("\nAppended to " + journal_path + ":")
print(json.dumps(row))
print(
    "PERF_RESULT read spike (%s %s): %s | spike %s→%d rps achieved %s, dropped %d, %s%% failed, "
    "p99 %sms | recovery %s%% failed, p95 %sms (baseline %sms)"
    % (
        row["endpoint"],
        row["window"],
        verdict,
        row["baseline_rate"],
        row["spike_rate"],
        row["spike_achieved_rps"],
        row["spike_dropped"],
        r(spike["failed_rate"] * 100, 2) if isinstance(spike["failed_rate"], (int, float)) else "n/a",
        row["spike_p99_ms"],
        r(recovery["failed_rate"] * 100, 2)
        if isinstance(recovery["failed_rate"], (int, float))
        else "n/a",
        row["recovery_p95_ms"],
        row["baseline_p95_ms"],
    )
)
PY
  )
  echo "$out"
  perf_result "$(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')"
}
