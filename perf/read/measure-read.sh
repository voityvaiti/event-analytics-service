#!/bin/bash

# One measured read cell, shared by every read leaf: warm up, run the scenario
# against the seeded corpus, and append one row to the leaf's journal. Defines
# perf_read_cell, which the harness (perf/lib/harness.sh) must already be
# sourced for.
#
# Reads do not mutate, so unlike a write cell there is nothing to clean up
# afterwards — the corpus is still exactly as seeded when the run ends.
#
# Usage: perf_read_cell <journal> <endpoint> [group_by] [limit]

perf_read_cell() {
  local journal=$1 endpoint=$2 group_by=${3:-} limit=${4:-}
  local script=perf/read/stats-read.js
  local summary=perf/read/last-summary.json

  # Knobs are resolved into locals and handed to k6 explicitly. Exporting them
  # would make a cell's settings outlive it: the next cell's own `${VUS:-4}`
  # would then read the previous one's value instead of its default, and the
  # same cell would measure differently depending on which action invoked it.
  local vus=${VUS:-4} duration=${DURATION:-30s}
  local common=(
    -e ENDPOINT="$endpoint" -e GROUP_BY="$group_by" -e LIMIT="$limit"
    -e VUS="$vus"
    --env SEED_ANCHOR --env SEED_SPREAD_DAYS
  )

  # Warms JIT and the pool if nothing has yet; it deliberately does not try to
  # warm the data, since the scenario walks its window across a corpus far
  # larger than the buffer cache and a steady mix of hits and misses is the
  # state worth measuring.
  warm_reads "$endpoint" "$group_by"

  local start_rows scans_before scans_after
  count_events || return 1
  start_rows=$CORPUS_ROWS
  scans_before=$(read_scan_counters) || return 1

  rm -f "$summary"
  k6_run "$script" "${common[@]}" \
    -e DURATION="$duration" -e SUMMARY_OUT="$summary"
  scans_after=$(read_scan_counters) || return 1
  [ -s "$summary" ] || {
    echo "Measured run produced no summary at $summary — did the app stay up?" >&2
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

index_before, seq_before = (int(v) for v in scans_before.split())
index_after, seq_after = (int(v) for v in scans_after.split())

row = {
    "date": date,
    "commit": commit,
    "scenario": s["scenario"],
    "endpoint": s["endpoint"],
    "group_by": s["group_by"],
    "schema_version": schema_version,
    "cpu": cpu,
    "cores": int(cores),
    "pool": int(pool),
    "vus": s["vus"],
    "duration": s["duration"],
    "start_rows": int(start_rows),
    "corpus_days": s["corpus_days"],
    "windows": {
        size: {
            "requests": round(stats["requests"]),
            "med_ms": round(stats["med_ms"], 2),
            "p95_ms": round(stats["p95_ms"], 2),
        }
        for size, stats in s["windows"].items()
    },
    "requests": round(s["requests"]),
    "throughput_rps": round(s["throughput_rps"], 1),
    "med_ms": round(s["latency_ms"]["med"], 2),
    "p95_ms": round(s["latency_ms"]["p95"], 2),
    "p99_ms": round(s["latency_ms"]["p99"], 2),
    "failed_rate": s["failed_rate"],
    "index_scans": index_after - index_before,
    "seq_scans": seq_after - seq_before,
}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")

label = row["endpoint"] + (" groupBy=" + row["group_by"] if row["group_by"] else "")

print("\nAppended to " + journal_path + ":")
print(json.dumps(row))
print(
    "PERF_RESULT read %s: med %sms | p95 %sms | p99 %sms | %d req/s | %.2f%% failed "
    "| index scans %d, seq scans %d (vus %d, %s)"
    % (
        label,
        row["med_ms"],
        row["p95_ms"],
        row["p99_ms"],
        row["throughput_rps"],
        row["failed_rate"] * 100,
        row["index_scans"],
        row["seq_scans"],
        row["vus"],
        row["duration"],
    )
)
PY
  ) || return 1
  echo "$out"
  perf_result "$(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')"
}
