#!/bin/bash

# Warm, measure, reconcile, clean up, and journal one write load cell. Requires
# perf/lib/harness.sh. Cleanup restores the corpus for the next cell. SATURATION=on also
# samples the rig during the measured run and journals the summary; it is off by default
# so the throughput series carries no sampling cost.
#
# Pass scenario, VUs, and duration explicitly to avoid leaking one cell's settings into
# another. Batch metrics use the scenario's reported batch size.
#
# Usage: perf_write_load_cell <journal> <script> <vus> <duration> [batch_size]

perf_write_load_cell() {
  local journal=$1 script=$2 vus=$3 duration=$4 batch_size=${5:-}
  local summary=perf/write/load/last-summary.json
  local unaccepted=perf/write/load/last-unaccepted.log
  local saturation=perf/write/load/last-saturation-samples.jsonl
  local batch=()
  [ -n "$batch_size" ] && batch=(-e BATCH_SIZE="$batch_size")

  # Discard warm-up metrics and rows. Measure at full VUs against a warmed app and the
  # restored corpus.
  k6_run "$script" "${batch[@]}" -e TOKEN="$WRITE_TOKEN" \
    -e VUS="$vus" -e DURATION=30s -e SUMMARY_OUT=/dev/null || true
  restore_seed_baseline || return 1

  local start_rows kafka_settings
  count_events || return 1
  start_rows=$CORPUS_ROWS
  ARTIFACT_COMMIT=$(read_artifact_commit) || return 1
  kafka_settings=$(read_kafka_settings) || return 1

  # Measured run, from the corpus. Remove the previous summary first so a run
  # that dies produces no file to journal, rather than a stale one.
  rm -f "$summary" "$unaccepted"
  start_saturation_sampler "$saturation" off
  k6_run "$script" "${batch[@]}" -e TOKEN="$WRITE_TOKEN" \
    -e VUS="$vus" -e DURATION="$duration" -e SUMMARY_OUT="$summary" -e RUN_ID="$(new_run_id)" \
    -e K6_LOG_OUTPUT="file=$unaccepted" -e K6_LOG_FORMAT=raw
  stop_saturation_sampler
  local drain_seconds
  wait_until_persisted || return 1
  drain_seconds=$DRAIN_SECONDS
  if [ -s "$summary" ]; then
    reconcile_accepted "$summary" "$unaccepted" || return 1
  fi
  restore_seed_baseline || return 1
  [ -s "$summary" ] || {
    echo "Measured run produced no summary at $summary — did the app stay up?" >&2
    return 1
  }

  local pool schema_version request_metrics scrape started_at finished_at
  read -r started_at finished_at < <(read_run_window "$summary") || return 1
  pool=$(read_pool) || return 1
  schema_version=$(read_schema) || return 1
  request_metrics=$(read_request_metrics) || return 1
  scrape=$(read_scrape "$started_at" "$finished_at") || return 1

  # Capture so the trailing `PERF_RESULT ` line can be lifted into the digest;
  # everything before it is echoed straight through to eyeball before committing.
  local out
  out=$(python3 - "$summary" "$journal" "$(date -u +%Y-%m-%d)" "$ARTIFACT_COMMIT" \
    "$(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')" "$(nproc)" "$pool" \
    "$request_metrics" "$scrape" "${INGEST_PATH:-async}" "$schema_version" "$start_rows" \
    "$ACCEPTED_EVENTS" "$MISSING_EVENTS" "$drain_seconds" "$kafka_settings" "$saturation" <<'PY'
import json, sys

sys.path.insert(0, "perf/lib")
import lag_samples
import saturation_samples

(
    summary_path, journal_path, date, commit, cpu, cores, pool, request_metrics,
    scrape, ingest_path, schema_version, start_rows, accepted_events, missing_events,
    drain_seconds, kafka_settings, saturation_path,
) = sys.argv[1:18]
with open(summary_path) as f:
    s = json.load(f)
settings = json.loads(kafka_settings)

# Derive events and events/s only for batches. Single-event requests and throughput
# already represent those values, so duplicate fields are unnecessary.
batch_size = s.get("batch_size")
requests = round(s["requests"])
throughput_rps = round(s["throughput_rps"], 1)

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
    **settings,
    "request_metrics": request_metrics,
    "scrape": scrape,
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
    "accepted_events": int(accepted_events),
    "missing_events": int(missing_events),
    "drain_seconds": float(drain_seconds),
    "saturation": saturation_samples.summarize(
        saturation_samples.load(saturation_path),
        lag_samples.epoch_millis(s["started_at"]),
        lag_samples.epoch_millis(s["finished_at"]),
    ),
}
row = {name: value for name, value in row.items() if value is not None or name in settings}

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
  perf_journalled "$journal"
  require_nothing_missing
}
