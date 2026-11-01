#!/bin/bash

# Measure a mixed surge, restore the write tenant, and append phase acceptance and
# failure counts. Pool-timeout deltas identify 5xx caused by connection waits. Tolerate
# k6 failures to record them. Lag is sampled every second; the curve goes to lag.jsonl.
#
# Verdict, Stage 3's: under 0.1% of writes unaccepted in every phase, a baseline median
# lag under one second of intake, and lag back under that bound for good before the
# recovery phase ends. Each phase needs valid samples for 80% of its seconds.
#
# Requires perf/lib/harness.sh. Usage: perf_mixed_spike_cell <journal> <endpoint>
# <group_by>

perf_mixed_spike_cell() {
  local journal=$1 endpoint=$2 group_by=$3
  local script=perf/mixed/spike/ingest-under-read-spike.js
  local summary=perf/mixed/spike/last-summary.json
  local unaccepted=perf/mixed/spike/last-unaccepted.log
  local samples=perf/mixed/spike/last-lag-samples.txt
  local curves=${journal%journal.jsonl}lag.jsonl

  warm_reads "$endpoint" "$group_by"
  k6_run perf/write/load/ingest-events.js -e TOKEN="$WRITE_TOKEN" \
    -e VUS=10 -e DURATION=15s -e SUMMARY_OUT=/dev/null >/dev/null 2>&1 || true
  restore_seed_baseline || return 1

  local start_rows timeouts_before timeouts_after
  count_events || return 1
  start_rows=$CORPUS_ROWS
  ARTIFACT_COMMIT=$(read_artifact_commit) || return 1
  timeouts_before=$(read_connection_timeouts) || return 1

  rm -f "$summary" "$unaccepted"
  start_lag_sampler "$samples"
  k6_run "$script" \
    --env SEED_ANCHOR --env SEED_SPREAD_DAYS --env SPIKE_WINDOW \
    --env READ_BASELINE_RATE --env READ_SPIKE_RATE --env READ_MAX_VUS \
    --env WRITE_RATE --env WRITE_TIMEOUT_SECONDS --env WRITE_MAX_VUS \
    --env BASELINE_SECONDS --env SPIKE_SECONDS --env RECOVERY_SECONDS \
    -e ENDPOINT="$endpoint" -e GROUP_BY="$group_by" \
    -e READ_TOKEN="$SEED_TOKEN" -e WRITE_TOKEN="$WRITE_TOKEN" \
    -e SUMMARY_OUT="$summary" -e RUN_ID="$(new_run_id)" \
    -e K6_LOG_OUTPUT="file=$unaccepted" -e K6_LOG_FORMAT=raw || true
  stop_lag_sampler
  timeouts_after=$(read_connection_timeouts) || return 1
  local drain_seconds
  wait_until_persisted || return 1
  drain_seconds=$DRAIN_SECONDS
  if [ -s "$summary" ]; then
    reconcile_accepted "$summary" "$unaccepted" || return 1
  fi
  restore_seed_baseline || return 1
  [ -s "$summary" ] || {
    echo "Mixed run produced no summary at $summary — did the app stay up?" >&2
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
    "$request_metrics" "$scrape" "${INGEST_PATH:-async}" "$schema_version" "$start_rows" \
    "$timeouts_before" "$timeouts_after" "$ACCEPTED_EVENTS" "$MISSING_EVENTS" "$drain_seconds" \
    "$samples" "$curves" <<'PY'
import json, sys

sys.path.insert(0, "perf/lib")
import lag_samples

(
    summary_path, journal_path, date, commit, cpu, cores, pool, request_metrics,
    scrape, ingest_path, schema_version, start_rows, timeouts_before, timeouts_after,
    accepted_events, missing_events, drain_seconds, samples_path, curves_path,
) = sys.argv[1:20]

MAX_UNACCEPTED_SHARE = 0.001
MIN_VALID_SHARE = 0.8

with open(summary_path) as f:
    s = json.load(f)
samples = lag_samples.load(samples_path)


def r(value, digits=2):
    return round(value, digits) if isinstance(value, (int, float)) else value


row = {
    "date": date,
    "commit": commit,
    "run_id": s["run_id"],
    "started_at": s["started_at"],
    "finished_at": s["finished_at"],
    "scenario": s["scenario"],
    "ingest_path": ingest_path,
    "endpoint": s["endpoint"],
    "group_by": s["group_by"],
    "window": s["window"],
    "schema_version": schema_version,
    "cpu": cpu,
    "cores": int(cores),
    "pool": int(pool),
    "request_metrics": request_metrics,
    "scrape": scrape,
    "start_rows": int(start_rows),
    "write_rate": s["write_rate"],
    "write_timeout_seconds": s["write_timeout_seconds"],
    "write_max_vus": s["write_max_vus"],
    "read_baseline_rate": s["read_baseline_rate"],
    "read_spike_rate": s["read_spike_rate"],
    "read_max_vus": s["read_max_vus"],
}

# A phase's drops are what it scheduled minus what it sent, because k6 counts
# drops per scenario and the writes are one scenario. Attribution by start time
# can move a handful of requests across a boundary, so a phase that dropped
# nothing may send and accept a few more than it scheduled; its drops and unaccepted
# share read as 0, not negative.
for phase, p in s["phases"].items():
    write, read = p["write"], p["read"]
    scheduled = write["scheduled"]
    row.update(
        {
            phase + "_started_at": p["started_at"],
            phase + "_finished_at": p["finished_at"],
            phase + "_write_unaccepted_share": r(max(0.0, 1 - write["accepted"] / scheduled), 4),
            phase + "_write_scheduled": scheduled,
            phase + "_write_sent": round(write["sent"]),
            phase + "_write_dropped": max(0, scheduled - round(write["sent"])),
            phase + "_write_accepted": round(write["accepted"]),
            phase + "_write_server_errors": round(write["server_errors"]),
            phase + "_write_rejected": round(write["rejected"]),
            phase + "_write_timeouts": round(write["timeouts"]),
            phase + "_write_transport_errors": round(write["transport_errors"]),
            phase + "_write_p95_ms": r(write["p95_ms"]),
            phase + "_write_p99_ms": r(write["p99_ms"]),
            phase + "_write_max_ms": r(write["max_ms"]),
            phase + "_read_achieved_rps": r(read["achieved_rps"], 1),
            phase + "_read_dropped": round(read["dropped"]),
            phase + "_read_failed_rate": r(read["failed_rate"], 4),
            phase + "_read_p95_ms": r(read["p95_ms"]),
            phase + "_read_p99_ms": r(read["p99_ms"]),
        }
    )

row["write_dropped"] = round(s["write_dropped"])
row["connection_timeouts"] = int(timeouts_after) - int(timeouts_before)
row["accepted_events"] = int(accepted_events)
row["missing_events"] = int(missing_events)
row["drain_seconds"] = float(drain_seconds)

lag_bound = s["write_rate"]
row["lag_bound"] = lag_bound
lag_windows = {}
for phase, p in s["phases"].items():
    start = lag_samples.epoch_millis(p["started_at"])
    end = lag_samples.epoch_millis(p["finished_at"])
    lags = lag_samples.describe(lag_samples.between(samples, start, end))
    lag_windows[phase] = (start, end, lags)
    row.update(
        {
            phase + "_lag_median": r(lags["median"], 1),
            phase + "_lag_max": r(lags["max"], 0),
            phase + "_lag_samples": lags["samples"],
            phase + "_lag_failed_samples": lags["failed_samples"],
        }
    )

recovery_start, recovery_end, _ = lag_windows["recovery"]
settled = lag_samples.settled_below(samples, recovery_start, recovery_end, lag_bound)
row["recovery_lag_settled_seconds"] = (
    None if settled is None else round((settled - recovery_start) / 1000, 1)
)

if any(
    lags["samples"] < MIN_VALID_SHARE * (end - start) / 1000
    for start, end, lags in lag_windows.values()
):
    verdict = "TOO FEW SAMPLES"
elif any(row[phase + "_write_unaccepted_share"] >= MAX_UNACCEPTED_SHARE for phase in s["phases"]):
    verdict = "WRITES NOT ACCEPTED"
elif lag_windows["baseline"][2]["median"] >= lag_bound:
    verdict = "BASELINE LAGGED"
elif settled is None:
    verdict = "LAG NOT RECOVERED"
else:
    verdict = "survived"
row["verdict"] = verdict

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")
with open(curves_path, "a") as f:
    curve = lag_samples.curve(samples, lag_samples.epoch_millis(s["started_at"]))
    f.write(json.dumps({"run_id": row["run_id"], "commit": commit, "samples": curve}) + "\n")


def share(phase):
    value = row[phase + "_write_unaccepted_share"]
    return "%.2f%%" % (value * 100) if isinstance(value, (int, float)) else "n/a"


query = row["endpoint"] + (" groupBy=" + row["group_by"] if row["group_by"] else "")

print("\nAppended to " + journal_path + ":")
print(json.dumps(row))
print(
    "PERF_RESULT mixed spike (%s %s): %s | writes %d/s not accepted: baseline %s | "
    "spike %s (dropped %d, 5xx %d, timeouts %d, accepted p99 %sms) | recovery %s | "
    "lag max %s/%s/%s, under %d %ss into recovery | "
    "reads spike p99 %sms, recovery p95 %sms (baseline %sms) | pool timeouts %d"
    % (
        query,
        row["window"],
        verdict,
        row["write_rate"],
        share("baseline"),
        share("spike"),
        row["spike_write_dropped"],
        row["spike_write_server_errors"],
        row["spike_write_timeouts"],
        row["spike_write_p99_ms"],
        share("recovery"),
        row["baseline_lag_max"],
        row["spike_lag_max"],
        row["recovery_lag_max"],
        lag_bound,
        row["recovery_lag_settled_seconds"],
        row["spike_read_p99_ms"],
        row["recovery_read_p95_ms"],
        row["baseline_read_p95_ms"],
        row["connection_timeouts"],
    )
)
PY
  ) || return 1
  echo "$out"
  perf_result "$(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')"
  perf_journalled "$journal"
  require_nothing_missing
}
