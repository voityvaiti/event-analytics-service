#!/bin/bash

# Offer events at a fixed rate, one per request or in batches, sample the consumer's lag
# and the rig's saturation every second, then drain, reconcile, clean up and journal one
# row with its verdict. The lag curve goes to lag.jsonl and the saturation curve to
# saturation.jsonl beside the journal. The warm-up is the stream's first phase, so the
# consumer reaches the measure phase already running at the offered rate.
#
# Verdict, judged on the measure phase, first failure wins:
# - every 30 s segment has at least 25 valid lag samples;
# - k6 sent all but under 0.1% of the scheduled requests. A rate the rig could not offer
#   says nothing about the server, whatever lag did;
# - every segment's median lag is under one second of intake, counted in records: a batch
#   request is one record;
# - under 0.1% of the writes went unaccepted.
# A median, because lag saw-tooths between commits and short spikes are fluctuation.
#
# Requires perf/lib/harness.sh.
# Usage: perf_write_steady_cell <journal> <events_per_second> [batch_size]

perf_write_steady_cell() {
  local journal=$1 rate=$2 batch_size=${3:-1}
  local script=perf/write/steady/ingest-at-rate.js
  local summary=perf/write/steady/last-summary.json
  local unaccepted=perf/write/steady/last-unaccepted.log
  local samples=perf/write/steady/last-lag-samples.txt
  local saturation=perf/write/steady/last-saturation-samples.jsonl
  local curves=${journal%journal.jsonl}lag.jsonl
  local saturation_curves=${journal%journal.jsonl}saturation.jsonl
  local shape=single
  [ "$batch_size" -gt 1 ] && shape=batch
  mkdir -p "$(dirname "$journal")"

  restore_seed_baseline || return 1

  local start_rows start_index_mb kafka_settings
  count_events || return 1
  start_rows=$CORPUS_ROWS
  start_index_mb=$(read_index_mb) || return 1
  ARTIFACT_COMMIT=$(read_artifact_commit) || return 1
  kafka_settings=$(read_kafka_settings) || return 1

  rm -f "$summary" "$unaccepted"
  start_lag_sampler "$samples"
  start_saturation_sampler "$saturation" on
  k6_run "$script" \
    --env WRITE_TIMEOUT_SECONDS --env MAX_VUS --env PHASES \
    -e TOKEN="$WRITE_TOKEN" -e RATE="$rate" -e BATCH_SIZE="$batch_size" \
    -e SCENARIO="steady-$shape" \
    -e SUMMARY_OUT="$summary" -e RUN_ID="$(new_run_id)" \
    -e K6_LOG_OUTPUT="file=$unaccepted" -e K6_LOG_FORMAT=raw || true
  stop_saturation_sampler
  stop_lag_sampler

  local drain_seconds
  wait_until_persisted || return 1
  drain_seconds=$DRAIN_SECONDS
  if [ -s "$summary" ]; then
    reconcile_accepted "$summary" "$unaccepted" || return 1
  fi
  restore_seed_baseline || return 1
  [ -s "$summary" ] || {
    echo "Steady run produced no summary at $summary — did the app stay up?" >&2
    return 1
  }

  local pool schema_version request_metrics scrape started_at finished_at
  read -r started_at finished_at < <(read_run_window "$summary") || return 1
  pool=$(read_pool) || return 1
  schema_version=$(read_schema) || return 1
  request_metrics=$(read_request_metrics) || return 1
  scrape=$(read_scrape "$started_at" "$finished_at") || return 1

  local out
  out=$(python3 - "$summary" "$samples" "$journal" "$curves" "$(date -u +%Y-%m-%d)" \
    "$ARTIFACT_COMMIT" "$(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')" "$(nproc)" \
    "$pool" "$request_metrics" "$scrape" "${INGEST_PATH:-async}" "$schema_version" \
    "$start_rows" "$ACCEPTED_EVENTS" "$MISSING_EVENTS" "$drain_seconds" \
    "$kafka_settings" "$start_index_mb" "$saturation" "$saturation_curves" <<'PY'
import json, sys

sys.path.insert(0, "perf/lib")
import lag_samples
import saturation_samples

(
    summary_path, samples_path, journal_path, curves_path, date, commit, cpu, cores, pool,
    request_metrics, scrape, ingest_path, schema_version, start_rows, accepted_events,
    missing_events, drain_seconds, kafka_settings, start_index_mb, saturation_path,
    saturation_curves_path,
) = sys.argv[1:22]

SEGMENT_SECONDS = 30
MIN_VALID_SAMPLES = 25
MAX_UNACCEPTED_SHARE = 0.001
MAX_UNSENT_SHARE = 0.001

with open(summary_path) as f:
    s = json.load(f)
samples = lag_samples.load(samples_path)
saturation = saturation_samples.load(saturation_path)


def r(value, digits=2):
    return round(value, digits) if isinstance(value, (int, float)) else value


batch_size = s.get("batch_size", 1)
lag_bound = s.get("request_rate", s["rate"])
measure = s["phases"]["measure"]
measure_start = lag_samples.epoch_millis(measure["started_at"])
measure_end = lag_samples.epoch_millis(measure["finished_at"])
segments = [
    lag_samples.describe(lag_samples.between(samples, start, start + SEGMENT_SECONDS * 1000))
    for start in range(measure_start, measure_end, SEGMENT_SECONDS * 1000)
]
whole = lag_samples.describe(lag_samples.between(samples, measure_start, measure_end))
measure_unaccepted = max(0.0, 1 - measure["accepted"] / measure["scheduled"])
measure_unsent = max(0.0, 1 - measure["sent"] / measure["scheduled"])

if any(segment["samples"] < MIN_VALID_SAMPLES for segment in segments):
    verdict = "TOO FEW SAMPLES"
elif measure_unsent >= MAX_UNSENT_SHARE:
    verdict = "RATE NOT OFFERED"
elif any(segment["median"] >= lag_bound for segment in segments):
    verdict = "FELL BEHIND"
elif measure_unaccepted >= MAX_UNACCEPTED_SHARE:
    verdict = "WRITES NOT ACCEPTED"
else:
    verdict = "kept up"

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
    **json.loads(kafka_settings),
    "request_metrics": request_metrics,
    "scrape": scrape,
    "start_rows": int(start_rows),
    "start_index_mb": int(start_index_mb),
    "rate": s["rate"],
}
if batch_size > 1:
    row.update({"batch_size": batch_size, "request_rate": s["request_rate"]})
row.update({"write_timeout_seconds": s["write_timeout_seconds"], "max_vus": s["max_vus"]})

for phase, p in s["phases"].items():
    scheduled = p["scheduled"]
    row.update(
        {
            phase + "_started_at": p["started_at"],
            phase + "_finished_at": p["finished_at"],
            phase + "_unaccepted_share": r(max(0.0, 1 - p["accepted"] / scheduled), 5),
            phase + "_scheduled": scheduled,
            phase + "_sent": round(p["sent"]),
            phase + "_dropped": max(0, scheduled - round(p["sent"])),
            phase + "_accepted": round(p["accepted"]),
            phase + "_server_errors": round(p["server_errors"]),
            phase + "_rejected": round(p["rejected"]),
            phase + "_timeouts": round(p["timeouts"]),
            phase + "_transport_errors": round(p["transport_errors"]),
            phase + "_p95_ms": r(p["p95_ms"]),
            phase + "_p99_ms": r(p["p99_ms"]),
            phase + "_max_ms": r(p["max_ms"]),
        }
    )

row.update(
    {
        "dropped": round(s["dropped"]),
        "lag_bound": lag_bound,
        "measure_lag_segment_medians": [r(segment["median"], 1) for segment in segments],
        "measure_lag_segment_samples": [segment["samples"] for segment in segments],
        "measure_lag_max": r(whole["max"], 0),
        "measure_lag_failed_samples": whole["failed_samples"],
        "measure_persisted_events_per_sec": r(
            lag_samples.persisted_per_second(samples, measure_start, measure_end), 1
        ),
        "verdict": verdict,
        "accepted_events": int(accepted_events),
        "missing_events": int(missing_events),
        "drain_seconds": float(drain_seconds),
        "measure_saturation": saturation_samples.summarize(saturation, measure_start, measure_end),
    }
)

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")
run_start = lag_samples.epoch_millis(s["started_at"])
with open(curves_path, "a") as f:
    curve = lag_samples.curve(samples, run_start)
    f.write(json.dumps({"run_id": row["run_id"], "commit": commit, "samples": curve}) + "\n")
if saturation:
    with open(saturation_curves_path, "a") as f:
        curve = saturation_samples.curve(saturation, run_start)
        f.write(json.dumps({"run_id": row["run_id"], "commit": commit, **curve}) + "\n")

busy = (row["measure_saturation"] or {}).get("consumer_busy") or {}
print("\nAppended to " + journal_path + ":")
print(json.dumps(row))
print(
    "PERF_RESULT write steady %s: %s | %d events/s offered, %s/s persisted | lag medians %s "
    "(bound %d, max %s) | %.3f%% not accepted, p99 %sms | busiest consumer %s"
    % (
        row["scenario"],
        verdict,
        row["rate"],
        row["measure_persisted_events_per_sec"],
        row["measure_lag_segment_medians"],
        lag_bound,
        row["measure_lag_max"],
        row["measure_unaccepted_share"] * 100,
        row["measure_p99_ms"],
        busy.get("busiest", "n/a"),
    )
)
PY
  ) || return 1
  echo "$out"
  perf_result "$(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')"
  perf_journalled "$journal"
  require_nothing_missing
}
