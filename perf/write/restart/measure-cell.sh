#!/bin/bash

# Stop the persistence consumer for a fixed downtime while single events keep arriving,
# restart it, and journal how long the backlog took to drain. Lag is sampled every
# second; the curve goes to lag.jsonl beside the journal.
#
# Timeline: wait for the first accepted write, BASELINE_SECONDS, stop, poll until
# STOPPED, DOWNTIME_SECONDS from that confirmation, start, poll until RUNNING. k6 keeps
# offering for AFTER_SECONDS beyond the planned restart so the drain is seen to hold.
#
# Verdict: lag falls under one second of intake for good within DRAIN_BUDGET_SECONDS of
# the start request, and under 0.1% of writes went unaccepted. As in every write cell,
# an accepted event missing after the drain fails the cell.
#
# The outage restarts the consumer whenever it exits, failed, terminated or not, and
# abandons its timeline once the shell that started it is gone. Ctrl-C reaches only the
# foreground shell, since background jobs ignore SIGINT; the outage then notices it is
# orphaned within a second and restarts the consumer.
#
# Requires perf/lib/harness.sh and an app with app.persistence-consumer.control.enabled,
# which scripts/actions/perf/app sets. Usage: perf_write_restart_cell <journal> <rate>

CONTROL_TOKEN=""

# The consumer's status from the control endpoint, after an optional stop or start.
#
# Usage: consumer_control [stop|start]
consumer_control() {
  local action=${1:-} method=GET
  [ -n "$action" ] && method=POST
  curl -sf -m 30 -X "$method" -H "Authorization: Bearer $CONTROL_TOKEN" \
    "$BASE_URL/internal/persistence-consumer${action:+/$action}" \
    | grep -oP '"status":"\K[A-Z]+'
}

# Fail before measuring when the control endpoint is off or the consumer is not running.
require_consumer_control() {
  if [ -z "$CONTROL_TOKEN" ]; then
    CONTROL_TOKEN=$(mint_token "$WRITE_BATCH_SOURCE" persistence:control) || return 1
  fi
  local status
  status=$(consumer_control) || {
    echo "The consumer control endpoint does not answer at $BASE_URL. Start the app with scripts/actions/perf/app, which enables it." >&2
    return 1
  }
  [ "$status" = RUNNING ] || {
    echo "The persistence consumer is $status before the outage; start it first." >&2
    return 1
  }
}

# Print the epoch milliseconds at which the consumer was first seen in <status>.
#
# Usage: wait_for_consumer <status> <timeout_seconds>
wait_for_consumer() {
  local wanted=$1 deadline status=""
  deadline=$(($(date +%s%3N) + $2 * 1000))
  while (($(date +%s%3N) < deadline)); do
    status=$(consumer_control) || status=""
    if [ "$status" = "$wanted" ]; then
      date +%s%3N
      return 0
    fi
    sleep 0.1
  done
  echo "The persistence consumer did not report $wanted within $2 s (last: ${status:-unreadable})." >&2
  return 1
}

# Start the consumer unless it is running. The outage's exit trap, so a failed or
# interrupted timeline never leaves it stopped.
resume_consumer() {
  local status
  status=$(consumer_control) || status=""
  if [ "$status" = STOPPING ]; then
    wait_for_consumer STOPPED 60 >/dev/null || true
    status=STOPPED
  fi
  [ "$status" = RUNNING ] && return 0
  echo "Restarting the persistence consumer, found ${status:-unreadable}." >&2
  consumer_control start >/dev/null || echo "Could not restart the persistence consumer; POST /internal/persistence-consumer/start with a persistence:control token." >&2
}

# Sleep until <epoch_millis>, a second at a time, failing once the starting shell is gone.
sleep_until() {
  local until_millis=$1 left
  while left=$((until_millis - $(date +%s%3N))) && ((left > 0)); do
    kill -0 $$ 2>/dev/null || return 1
    if ((left >= 1000)); then
      sleep 1
    else
      sleep "0.$(printf '%03d' "$left")"
    fi
  done
}

# Print the epoch milliseconds at which events.ingested first exceeded <before>: the
# outage times its baseline from the first accepted write, not from k6's launch.
wait_for_intake() {
  local before=$1 deadline now
  [ "$before" = nan ] && before=0
  deadline=$(($(date +%s%3N) + 60000))
  while (($(date +%s%3N) < deadline)); do
    now=$(read_metric_value events.ingested)
    if [ "$now" != nan ] && awk -v now="$now" -v before="$before" 'BEGIN { exit !(now > before) }'; then
      date +%s%3N
      return 0
    fi
    sleep 0.1
  done
  echo "No write was accepted within 60 s of launching k6." >&2
  return 1
}

# Run in a subshell: append "<event> <epoch_millis>" lines to <timeline> for intake,
# stop_requested, stopped, start_requested and running.
#
# Usage: run_consumer_outage <timeline> <baseline_seconds> <downtime_seconds> <ingested_before>
run_consumer_outage() {
  local timeline=$1 baseline=$2 downtime=$3 ingested_before=$4 intake stopped running
  trap resume_consumer EXIT
  trap 'exit 143' TERM

  intake=$(wait_for_intake "$ingested_before") || return 1
  echo "intake $intake" >>"$timeline"
  sleep_until $((intake + baseline * 1000)) || return 1

  echo "stop_requested $(date +%s%3N)" >>"$timeline"
  consumer_control stop >/dev/null || return 1
  stopped=$(wait_for_consumer STOPPED 60) || return 1
  echo "stopped $stopped" >>"$timeline"
  sleep_until $((stopped + downtime * 1000)) || return 1

  echo "start_requested $(date +%s%3N)" >>"$timeline"
  consumer_control start >/dev/null || return 1
  running=$(wait_for_consumer RUNNING 60) || return 1
  echo "running $running" >>"$timeline"
}

perf_write_restart_cell() {
  local journal=$1 rate=$2
  local baseline=${BASELINE_SECONDS:-30} downtime=${DOWNTIME_SECONDS:-60}
  local after=${AFTER_SECONDS:-90} drain_budget=${DRAIN_BUDGET_SECONDS:-60}
  local script=perf/write/steady/ingest-at-rate.js
  local summary=perf/write/restart/last-summary.json
  local unaccepted=perf/write/restart/last-unaccepted.log
  local samples=perf/write/restart/last-lag-samples.txt
  local timeline=perf/write/restart/last-outage.txt
  local curves=${journal%journal.jsonl}lag.jsonl

  require_consumer_control || return 1
  restore_seed_baseline || return 1

  local start_rows kafka_settings
  count_events || return 1
  start_rows=$CORPUS_ROWS
  ARTIFACT_COMMIT=$(read_artifact_commit) || return 1
  kafka_settings=$(read_kafka_settings) || return 1

  rm -f "$summary" "$unaccepted"
  : >"$timeline"
  local ingested_before outage_pid outage_status=0
  ingested_before=$(read_metric_value events.ingested)
  start_lag_sampler "$samples"
  (run_consumer_outage "$timeline" "$baseline" "$downtime" "$ingested_before") &
  outage_pid=$!
  k6_run "$script" \
    --env WRITE_TIMEOUT_SECONDS --env MAX_VUS \
    -e TOKEN="$WRITE_TOKEN" -e RATE="$rate" -e SCENARIO=restart-single \
    -e PHASES="intake:$((baseline + downtime + after))" \
    -e SUMMARY_OUT="$summary" -e RUN_ID="$(new_run_id)" \
    -e K6_LOG_OUTPUT="file=$unaccepted" -e K6_LOG_FORMAT=raw || true
  wait "$outage_pid" || outage_status=$?
  stop_lag_sampler

  local drain_seconds
  wait_until_persisted || return 1
  drain_seconds=$DRAIN_SECONDS
  if [ -s "$summary" ]; then
    reconcile_accepted "$summary" "$unaccepted" || return 1
  fi
  restore_seed_baseline || return 1
  [ "$outage_status" -eq 0 ] || {
    echo "The outage did not complete (see $timeline); nothing journalled." >&2
    return 1
  }
  [ -s "$summary" ] || {
    echo "Restart run produced no summary at $summary — did the app stay up?" >&2
    return 1
  }

  local pool schema_version request_metrics scrape started_at finished_at
  read -r started_at finished_at < <(read_run_window "$summary") || return 1
  pool=$(read_pool) || return 1
  schema_version=$(read_schema) || return 1
  request_metrics=$(read_request_metrics) || return 1
  scrape=$(read_scrape "$started_at" "$finished_at") || return 1

  local out
  out=$(python3 - "$summary" "$samples" "$timeline" "$journal" "$curves" \
    "$(date -u +%Y-%m-%d)" "$ARTIFACT_COMMIT" \
    "$(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')" "$(nproc)" "$pool" \
    "$request_metrics" "$scrape" "${INGEST_PATH:-async}" "$schema_version" "$start_rows" \
    "$ACCEPTED_EVENTS" "$MISSING_EVENTS" "$drain_seconds" "$drain_budget" \
    "$kafka_settings" <<'PY'
import json, sys
from datetime import datetime, timezone

sys.path.insert(0, "perf/lib")
import lag_samples

(
    summary_path, samples_path, timeline_path, journal_path, curves_path, date, commit, cpu,
    cores, pool, request_metrics, scrape, ingest_path, schema_version, start_rows,
    accepted_events, missing_events, drain_seconds, drain_budget, kafka_settings,
) = sys.argv[1:21]

MAX_UNACCEPTED_SHARE = 0.001
MIN_VALID_SHARE = 0.8

with open(summary_path) as f:
    s = json.load(f)
with open(timeline_path) as f:
    at = {event: int(millis) for event, millis in (line.split() for line in f if line.strip())}
samples = lag_samples.load(samples_path)
drain_budget = float(drain_budget)


def r(value, digits=2):
    return round(value, digits) if isinstance(value, (int, float)) else value


def seconds(start, end):
    return round((at[end] - at[start]) / 1000, 1)


def iso(millis):
    moment = datetime.fromtimestamp(millis / 1000, timezone.utc)
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


lag_bound = s["rate"]
intake = s["phases"]["intake"]
run_start = lag_samples.epoch_millis(s["started_at"])
run_end = lag_samples.epoch_millis(s["finished_at"])
restart = at["start_requested"]

after_restart = lag_samples.describe(lag_samples.between(samples, restart, run_end))
drained_at = lag_samples.settled_below(samples, restart, run_end, lag_bound)
drain_after_restart = None if drained_at is None else round((drained_at - restart) / 1000, 1)
unaccepted = max(0.0, 1 - intake["accepted"] / intake["scheduled"])

if after_restart["samples"] < MIN_VALID_SHARE * (run_end - restart) / 1000:
    verdict = "TOO FEW SAMPLES"
elif drain_after_restart is None or drain_after_restart > drain_budget:
    verdict = "DID NOT DRAIN"
elif unaccepted >= MAX_UNACCEPTED_SHARE:
    verdict = "WRITES NOT ACCEPTED"
else:
    verdict = "drained"

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
    "rate": s["rate"],
    "write_timeout_seconds": s["write_timeout_seconds"],
    "max_vus": s["max_vus"],
    "stopped_at": iso(at["stopped"]),
    "restarted_at": iso(restart),
    "baseline_seconds": seconds("intake", "stop_requested"),
    "stop_seconds": seconds("stop_requested", "stopped"),
    "downtime_seconds": seconds("stopped", "start_requested"),
    "start_seconds": seconds("start_requested", "running"),
    "after_restart_seconds": round((run_end - restart) / 1000, 1),
    "unaccepted_share": r(unaccepted, 5),
    "scheduled": intake["scheduled"],
    "sent": round(intake["sent"]),
    "dropped": max(0, intake["scheduled"] - round(intake["sent"])),
    "accepted": round(intake["accepted"]),
    "server_errors": round(intake["server_errors"]),
    "rejected": round(intake["rejected"]),
    "timeouts": round(intake["timeouts"]),
    "transport_errors": round(intake["transport_errors"]),
    "p95_ms": r(intake["p95_ms"]),
    "p99_ms": r(intake["p99_ms"]),
    "max_ms": r(intake["max_ms"]),
    "lag_bound": lag_bound,
    "lag_at_restart": r(lag_samples.last_lag(samples, restart), 0),
    "lag_max": r(lag_samples.describe(lag_samples.between(samples, run_start, run_end))["max"], 0),
    "drain_budget_seconds": drain_budget,
    "drain_after_restart_seconds": drain_after_restart,
    "catch_up_events_per_sec": r(
        None if drained_at is None
        else lag_samples.persisted_per_second(samples, restart, drained_at + 1), 1
    ),
    "after_restart_lag_failed_samples": after_restart["failed_samples"],
    "verdict": verdict,
    "accepted_events": int(accepted_events),
    "missing_events": int(missing_events),
    "drain_seconds": float(drain_seconds),
}

with open(journal_path, "a") as f:
    f.write(json.dumps(row) + "\n")
with open(curves_path, "a") as f:
    curve = lag_samples.curve(samples, run_start)
    f.write(json.dumps({"run_id": row["run_id"], "commit": commit, "samples": curve}) + "\n")

print("\nAppended to " + journal_path + ":")
print(json.dumps(row))
print(
    "PERF_RESULT write restart %s: %s | down %ss, backlog %s at restart, under %d after %ss "
    "(budget %gs), catch-up %s events/s | %.3f%% not accepted"
    % (
        row["scenario"],
        verdict,
        row["downtime_seconds"],
        row["lag_at_restart"],
        lag_bound,
        drain_after_restart,
        drain_budget,
        row["catch_up_events_per_sec"],
        unaccepted * 100,
    )
)
PY
  ) || return 1
  echo "$out"
  perf_result "$(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')"
  perf_journalled "$journal"
  require_nothing_missing
}
