#!/bin/bash

# One measured write load cell, shared by every leaf under write/load: warm up,
# run the scenario against the seeded corpus, put the corpus back, and append one
# row to the leaf's journal. Defines perf_write_load_cell, which the harness
# (perf/lib/harness.sh) must already be sourced for. The spike cells do not share
# it — a surge is measured in phases against a recovery verdict, not as one
# steady window.
#
# Unlike a read cell, a write cell mutates: every measured run inserts rows and
# then deletes exactly the ones it inserted, so the next cell starts from the
# same corpus this one did.
#
# The scenario is an argument rather than a constant here, because that is what
# separates one write cell from another: they post different request shapes to
# different endpoints and are measured identically. VUS and DURATION arrive
# resolved for the same reason a cell resolves its own knobs — the window a cell
# wants is a property of the request it sends, and an exported one would outlive
# the cell that set it.
#
# batch_size is optional and only reaches a scenario that posts batches; the
# events-per-second the row turns on is derived from what the scenario reports
# using it, not from what was asked for here.
#
# Usage: perf_write_load_cell <journal> <script> <vus> <duration> [batch_size]

perf_write_load_cell() {
  local journal=$1 script=$2 vus=$3 duration=$4 batch_size=${5:-}
  local summary=perf/write/load/last-summary.json
  local batch=()
  [ -n "$batch_size" ] && batch=(-e BATCH_SIZE="$batch_size")

  # Warm up (JIT + connection pool) and throw the numbers away, so the measured
  # run reflects steady state, not cold start. This pass also stands in for a
  # ramp stage — the measured run then starts at full VUs against a warmed app,
  # so its summary is not diluted by low-concurrency ramp samples. Its rows are
  # then dropped, putting the table back to the seeded corpus.
  k6_run "$script" "${batch[@]}" -e TOKEN="$WRITE_TOKEN" \
    -e VUS="$vus" -e DURATION=30s -e SUMMARY_OUT=/dev/null || true
  restore_seed_baseline || return 1

  local start_rows
  count_events || return 1
  start_rows=$CORPUS_ROWS

  # Measured run, from the corpus. Remove the previous summary first so a run
  # that dies produces no file to journal, rather than a stale one.
  rm -f "$summary"
  k6_run "$script" "${batch[@]}" -e TOKEN="$WRITE_TOKEN" \
    -e VUS="$vus" -e DURATION="$duration" -e SUMMARY_OUT="$summary"
  restore_seed_baseline || return 1
  [ -s "$summary" ] || {
    echo "Measured run produced no summary at $summary — did the app stay up?" >&2
    return 1
  }

  local pool schema_version
  pool=$(read_pool) || return 1
  schema_version=$(read_schema) || return 1

  # Capture so the trailing `PERF_RESULT ` line can be lifted into the digest;
  # everything before it is echoed straight through to eyeball before committing.
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

# Events per second is what one write shape can be compared with another over: a
# batch request and a single-event request have request rates that differ by the
# batch size and mean nothing side by side.
#
# It is journalled only where it says something the row does not already say. A
# scenario that posts one event per request reports no batch size, and its row
# carries neither the size nor anything derived from it — for one event per
# request, `requests` is the event count and `throughput_rps` is the event rate,
# and a second copy of each under another name is a field that can go stale
# against itself.
batch_size = s.get("batch_size")
requests = round(s["requests"])
throughput_rps = round(s["throughput_rps"], 1)

row = {
    "date": date,
    "commit": commit,
    "scenario": s["scenario"],
    "ingest_path": ingest_path,
    "schema_version": schema_version,
    "cpu": cpu,
    "cores": int(cores),
    "pool": int(pool),
    "vus": s["vus"],
    "duration": s["duration"],
    "batch_size": batch_size,
    "start_rows": int(start_rows),
    "requests": requests,
    "events": requests * batch_size if batch_size else None,
    "throughput_rps": throughput_rps,
    "events_per_sec": round(s["throughput_rps"] * batch_size, 1) if batch_size else None,
    "p95_ms": round(s["latency_ms"]["p95"], 2),
    "p99_ms": round(s["latency_ms"]["p99"], 2),
    "failed_rate": s["failed_rate"],
}
row = {name: value for name, value in row.items() if value is not None}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")

print("\nAppended to " + journal_path + ":")
print(json.dumps(row))
rate = (
    "%d events/s | %d req/s" % (row["events_per_sec"], row["throughput_rps"])
    if batch_size
    else "%d req/s" % row["throughput_rps"]
)
shape = ", batch %d" % batch_size if batch_size else ""
print(
    "PERF_RESULT write load %s: %s | p95 %sms | p99 %sms | %.2f%% failed (vus %d, %s%s)"
    % (
        row["scenario"],
        rate,
        row["p95_ms"],
        row["p99_ms"],
        row["failed_rate"] * 100,
        row["vus"],
        row["duration"],
        shape,
    )
)
PY
  ) || return 1
  echo "$out"
  perf_result "$(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')"
}
