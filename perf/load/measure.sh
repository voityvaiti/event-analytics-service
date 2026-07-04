#!/bin/bash

# Load test: steady-state ingest write throughput. Defines perf_load, which the
# harness (perf/lib/harness.sh) must already be sourced for. Appends one row to
# perf/load/journal.jsonl — the absolute throughput series, comparable only
# within a fixed rig, so every row self-stamps CPU/cores/pool/schema.
#
# Tunables via env: VUS (default 10, keep near the pool), DURATION (default 60s).

perf_load() {
  local script=perf/load/ingest-events.js
  local summary=perf/load/last-summary.json
  local journal=perf/load/journal.jsonl

  export VUS=${VUS:-10}
  export DURATION=${DURATION:-60s}
  export SUMMARY_OUT=$summary

  # Warm up (JIT + connection pool) and throw the numbers away, so the measured
  # run reflects steady state, not cold start. This pass also stands in for a
  # ramp stage — the measured run then starts at full VUs against a warmed app,
  # so its summary is not diluted by low-concurrency ramp samples.
  reset_db
  k6_run "$script" --env VUS --env DURATION -e DURATION=30s -e SUMMARY_OUT=/dev/null || true

  # Measured run, from a known-empty table. Remove the previous summary first so
  # a run that dies produces no file to journal, rather than a stale one.
  reset_db
  rm -f "$summary"
  k6_run "$script" --env VUS --env DURATION
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
    "${INGEST_PATH:-sync}" "$schema_version" <<'PY'
import json, sys

summary_path, journal_path, date, commit, cpu, cores, pool, ingest_path, schema_version = (
    sys.argv[1:10]
)
with open(summary_path) as f:
    s = json.load(f)

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
    "start_rows": 0,
    "requests": round(s["requests"]),
    "throughput_rps": round(s["throughput_rps"], 1),
    "p95_ms": round(s["latency_ms"]["p95"], 2),
    "p99_ms": round(s["latency_ms"]["p99"], 2),
    "failed_rate": s["failed_rate"],
}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")

print("\nAppended to " + journal_path + ":")
print(json.dumps(row))
print(
    "PERF_RESULT load: %d req/s | p95 %sms | p99 %sms | %.2f%% failed (vus %d, %s)"
    % (
        row["throughput_rps"],
        row["p95_ms"],
        row["p99_ms"],
        row["failed_rate"] * 100,
        row["vus"],
        row["duration"],
    )
)
PY
  ) || return 1
  echo "$out"
  perf_result "$(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')"
}