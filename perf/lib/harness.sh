#!/bin/bash

# Shared perf bootstrap, corpus management, k6 execution, and measurement stamps.
#
# Callers must cd to the repo root, source the harness and cell, then call
# perf_bootstrap once before measuring. Pass k6 scenario paths relative to the repo
# root, mounted at /work.

export BASE_URL=${BASE_URL:-http://localhost:8080}
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.50.0}

# The seeder is JavaScript so it can share the k6 event generator; like k6, node
# is not installed on the host but pulled as a pinned image.
NODE_IMAGE=${NODE_IMAGE:-node:22-alpine}

# Where the metrics stack listens, when one is up at all. Only ever asked whether
# it was scraping during a run (read_scrape); nothing here needs it to be there.
PROMETHEUS_URL=${PROMETHEUS_URL:-http://localhost:9090}

# The corpus every test measures against. Exported so the seeder reads the same
# definition the read scenarios will query against.
export SEED_ROWS=${SEED_ROWS:-20000000}
export SEED_SPREAD_DAYS=${SEED_SPREAD_DAYS:-180}
export SEED_ANCHOR=${SEED_ANCHOR:-2026-01-01T00:00:00Z}

# Separate corpus and write tenants so cleanup removes only test writes. SEED_SOURCE
# must match seed-corpus.mjs (which uses COPY); WRITE_BATCH_SOURCE must match
# WRITE_TOKEN. Mixing tokens silently leaves test rows behind or measures reads over an
# empty tenant.
SEED_SOURCE=perf-seed
WRITE_BATCH_SOURCE=perf-test

# One-line result per test, printed together by perf_report at the end so a
# multi-test pipeline run leaves a single readable digest, not a scroll-back.
PERF_RESULTS=()

# The journals a run appended to, put on the dashboard together by perf_report.
# Accumulated rather than rediscovered so the sync offers the rows just measured
# instead of re-reading every journal in the suite on every run.
PERF_JOURNALS=()

# Bring up dependencies and verify the app and k6 image are usable, failing here
# with a clear cause rather than as a swallowed error mid-measurement. Safe to
# call once per process; the pipeline calls it once and then runs every test.
perf_bootstrap() {
  scripts/actions/dependencies

  if ! curl -sf "$BASE_URL/actuator/health" | grep -q '"status":"UP"'; then
    echo "App is unhealthy at $BASE_URL. Start it with scripts/actions/perf/app and retry." >&2
    return 1
  fi

  # Pull/verify the k6 image up front so a missing image or broken Docker fails
  # here, not as a swallowed warm-up error surfacing later.
  if ! docker run --rm "$K6_IMAGE" version >/dev/null 2>&1; then
    echo "Cannot run '$K6_IMAGE'. Check Docker and image access." >&2
    return 1
  fi

  require_optimized_jvm || return 1

  # Read here as well as per cell, so an app whose artifact cannot be proved fails
  # before the first measurement instead of after it.
  ARTIFACT_COMMIT=$(read_artifact_commit) || return 1

  # Mint read and write tokens once. Each cell must pass its token explicitly via -e
  # TOKEN=...; missing tokens cause 401s reported as load failures.
  SEED_TOKEN=$(mint_token "$SEED_SOURCE") || return 1
  WRITE_TOKEN=$(mint_token "$WRITE_BATCH_SOURCE") || return 1

  seed_corpus
}

# The process serving BASE_URL, for the checks that read the running app itself
# rather than trusting what the shell around it happens to say. Empty when the
# listening port cannot be traced to a process — which is every remote app, so a
# caller decides on its own whether that is a note or a failure.
app_pid() {
  local port=${BASE_URL##*:}
  port=${port%%/*}
  ss -ltnp 2>/dev/null | grep -F ":$port " | grep -oP 'pid=\K[0-9]+' | head -1
}

# Reject C1-only JIT (-XX:TieredStopAtLevel=1 from bootRun). It reduced batch throughput
# from 126.0k to 107k events/s while leaving reads unchanged. Journal rows do not record
# launch mode, so fail rather than record misleading results.
require_optimized_jvm() {
  case "$BASE_URL" in
    *localhost*|*127.0.0.1*) ;;
    *)
      echo "Note: cannot check the app's JIT settings at $BASE_URL — make sure it is not bootRun." >&2
      return 0
      ;;
  esac

  local pid
  pid=$(app_pid) || pid=""

  if [ -z "$pid" ] || [ ! -r "/proc/$pid/cmdline" ]; then
    echo "Note: could not read the app's JVM arguments — make sure it is not bootRun." >&2
    return 0
  fi

  if tr '\0' '\n' < "/proc/$pid/cmdline" | grep -q 'TieredStopAtLevel'; then
    cat >&2 <<'MESSAGE'
The app limits tiered compilation, disabling C2 and understating measured write throughput by ~15%. Read results were unaffected; journals do not record this setting.
Start the app with: scripts/actions/perf/app
MESSAGE
    return 1
  fi
}

# Read the serving process's start time from /proc/<pid> mtime. Subtracting integer `ps
# etimes` from `date +%s` can be off by a second and incorrectly reject a stamp written
# just before exec.
process_started_at() {
  local started
  started=$(stat -c %Y "/proc/$1" 2>/dev/null)

  if [ -z "$started" ]; then
    echo "Could not read when the process serving $BASE_URL started." >&2
    return 1
  fi

  printf '%s' "$started"
}

# Read the running jar's build stamp into ARTIFACT_COMMIT before each measured cell. The
# checkout may differ, and the app may restart between cells.
#
# Reject local jars without a traceable stamp or with a stamp newer than the process. A
# failed second launch can replace the stamp while the old app still serves.
# Uninspectable remote apps use "unknown".
ARTIFACT_COMMIT=""
read_artifact_commit() {
  case "$BASE_URL" in
    *localhost*|*127.0.0.1*) ;;
    *)
      echo "Note: cannot read the build stamp of the app at $BASE_URL — rows say commit 'unknown'." >&2
      echo unknown
      return 0
      ;;
  esac

  local pid="" jar="" stamp=""
  pid=$(app_pid) || pid=""
  if [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ]; then
    jar=$(tr '\0' '\n' < "/proc/$pid/cmdline" \
      | awk 'after_jar { print; exit } $0 == "-jar" { after_jar = 1 }')
  fi

  # The launcher passes a repo-relative path, so the stamp is found next to the
  # jar the process opened rather than next to a same-named one under our cwd.
  case "$jar" in
    '' | /*) ;;
    *) jar="$(readlink -f "/proc/$pid/cwd")/$jar" ;;
  esac

  if [ -n "$jar" ] && [ -r "$jar.commit" ]; then
    stamp=$(tr -d '[:space:]' < "$jar.commit")
  fi

  if [ -z "$stamp" ]; then
    cat >&2 <<'MESSAGE'
The running app has no readable jar build stamp; its commit cannot be recorded reliably.
Start the app with: scripts/actions/perf/app
MESSAGE
    return 1
  fi

  local process_started
  process_started=$(process_started_at "$pid") || return 1

  if [ "$(stat -c %Y "$jar")" -gt "$process_started" ] \
    || [ "$(stat -c %Y "$jar.commit")" -gt "$process_started" ]; then
    cat >&2 <<'MESSAGE'
The jar or build stamp is newer than the running app, so it cannot identify the loaded build. A second launch may have rebuilt the jar before failing on the occupied port.
Restart the app with: scripts/actions/perf/app
MESSAGE
    return 1
  fi

  printf '%s\n' "$stamp"
}

# Sign a tenant token with the pinned Node image; only Docker is required. An optional
# second argument adds a scope, such as persistence:control.
#
# Usage: mint_token <tenant> [scope]
mint_token() {
  docker run --rm \
    --volume "$PWD":/work --workdir /work \
    "$NODE_IMAGE" node perf/lib/mint-token.mjs "$@" || {
    echo "Could not mint a token for '$1' — is dev-keys/ still present?" >&2
    return 1
  }
}

# Run pinned k6 with the repo mounted and the host app reachable via --network host
# (Linux). Docker Desktop needs host.docker.internal routing instead. Use the caller's
# uid so summaries remain user-owned. The label lets the saturation sampler find the
# container, which starts after it.
#
# Usage: k6_run <script.js> [extra docker/k6 args...]
#
# Forward common base URL, run ID, and summary settings. Later -e arguments override
# earlier ones. Each cell must pass TOKEN explicitly to select its tenant.
K6_CONTAINER_LABEL=perf.harness=k6
k6_run() {
  local script="$1"
  shift
  docker run --rm --network host \
    --label "$K6_CONTAINER_LABEL" \
    --user "$(id -u):$(id -g)" \
    --volume "$PWD":/work --workdir /work \
    --env BASE_URL --env RUN_ID --env SUMMARY_OUT \
    "$@" \
    "$K6_IMAGE" run "$script"
}

# One run ID for every init context of a k6 run. Left to the scenario, each VU and
# handleSummary take their own Date.now(), and the summary's run_id stops prefixing the
# run's event IDs. An exported RUN_ID is kept.
new_run_id() {
  printf '%s' "${RUN_ID:-$(date +%s%3N)}"
}

psql_events() {
  docker compose exec -T postgres psql -U event_analytics -d event_analytics "$@"
}

# Seed a deterministic corpus via COPY. Empty tables hide index effects, while API
# ingestion would make setup slow. Finish with ANALYZE for current planner statistics
# and VACUUM for the visibility map required by index-only scans; do not wait for
# autovacuum during measurement.
seed_corpus() {
  # Handle SEED_ROWS=0 separately: the generator rejects zero, and TRUNCATE needs
  # ANALYZE to replace stale planner row estimates.
  if [ "$SEED_ROWS" = 0 ]; then
    echo "Emptying the table for SEED_ROWS=0."
    psql_events -qc 'TRUNCATE events;' || return 1
    psql_events -qc 'VACUUM ANALYZE events;' || return 1
    return 0
  fi

  local existing
  existing=$(psql_events -tAc "SELECT count(*) FROM events WHERE tenant_name = '$SEED_SOURCE'" \
    | tr -d '[:space:]') || return 1

  # Reuse an intact corpus: rebuilding millions of rows before every run costs
  # minutes and produces exactly the same table. SEED_FORCE=1 after changing the
  # generator, which this count cannot notice.
  if [ "$existing" = "$SEED_ROWS" ] && [ "${SEED_FORCE:-0}" != 1 ]; then
    echo "Corpus already holds $SEED_ROWS seeded rows — reusing it (SEED_FORCE=1 to rebuild)."
    return 0
  fi

  echo "Seeding $SEED_ROWS rows spread over $SEED_SPREAD_DAYS days from $SEED_ANCHOR ..."
  psql_events -qc 'TRUNCATE events;' || return 1
  docker run --rm \
    --volume "$PWD":/work --workdir /work \
    --env SEED_ROWS --env SEED_SPREAD_DAYS --env SEED_ANCHOR \
    "$NODE_IMAGE" node perf/lib/seed-corpus.mjs \
    | psql_events -qc \
      "COPY events (event_id, tenant_name, user_id, event_type, occurred_at, properties)
       FROM STDIN WITH (FORMAT csv)" || return 1
  psql_events -qc 'VACUUM ANALYZE events;' || return 1
}

# The persistence group's lag in records, read from the app. Prints "nan" while the
# broker does not answer, so an outage never reads as an empty backlog.
read_consumer_lag() {
  curl -sf "$BASE_URL/actuator/metrics/persistence.consumer.lag" \
    | python3 -c '
import json, sys

lag = float(json.load(sys.stdin)["measurements"][0]["value"])
print("nan" if lag != lag else int(lag))
'
}

# Wait until the consumer has committed every record on events.raw: a 202 means the
# broker holds the events, not the table, so a count or a DELETE straight after k6
# would miss rows still on their way. Sets DRAIN_SECONDS, the wait after the caller's
# k6 run exited, and PERSISTED_AT_MS, when lag first read 0, empty when it already did
# at the call. Gives up after DRAIN_TIMEOUT_SECONDS rather than delete under a consumer
# that is stopped or stalled.
DRAIN_TIMEOUT_SECONDS=${DRAIN_TIMEOUT_SECONDS:-300}
wait_until_persisted() {
  local started_ns lag
  started_ns=$(date +%s%N)
  PERSISTED_AT_MS=""
  lag=$(read_consumer_lag)
  while [ "$lag" != 0 ]; do
    if (($(date +%s%N) - started_ns >= DRAIN_TIMEOUT_SECONDS * 1000000000)); then
      echo "The persistence consumer still lags (${lag:-unreadable} records) after ${DRAIN_TIMEOUT_SECONDS}s — is it stopped or stalled?" >&2
      return 1
    fi
    sleep 0.5
    lag=$(read_consumer_lag)
    [ "$lag" = 0 ] && PERSISTED_AT_MS=$(date +%s%3N)
  done
  DRAIN_SECONDS=$(python3 -c "print(round(($(date +%s%N) - $started_ns) / 1e9, 1))")
}

# One actuator metric's value, optionally narrowed to a tag such as outcome:inserted.
# Prints nan when the app does not answer or reports no number, never 0.
#
# Usage: read_metric_value <metric> [tag]
read_metric_value() {
  local url="$BASE_URL/actuator/metrics/$1" value
  if [ -n "${2:-}" ]; then
    url="$url?tag=$2"
  fi
  value=$(curl -sf -m 2 "$url" | grep -oP '"value":\K[^,}\]]+') || value=""
  case "$value" in
    '' | *[!0-9.eE+-]*) echo nan ;;
    *) echo "$value" ;;
  esac
}

# Sample the consumer's lag and the inserted-events counter in the background, once a
# second on a fixed schedule, until stop_lag_sampler. Each "<epoch_ms> <lag> <inserted>"
# line carries the time its sample was taken, because a slow broker can stretch one read
# past its tick; a late tick moves the schedule rather than bursting to catch up. The
# loop also ends once the shell that started it is gone, so a killed run leaves no
# sampler behind. lib/lag_samples.py reads the file.
#
# Usage: start_lag_sampler <file>
LAG_SAMPLER_PID=""
start_lag_sampler() {
  local file=$1
  : >"$file"
  (
    next_millis=$(date +%s%3N)
    while kill -0 $$ 2>/dev/null; do
      taken_millis=$(date +%s%3N)
      printf '%s %s %s\n' "$taken_millis" \
        "$(read_metric_value persistence.consumer.lag)" \
        "$(read_metric_value events.persisted outcome:inserted)" >>"$file"
      next_millis=$((next_millis + 1000))
      wait_millis=$((next_millis - $(date +%s%3N)))
      if ((wait_millis > 0)); then
        sleep "$((wait_millis / 1000)).$(printf '%03d' $((wait_millis % 1000)))"
      else
        next_millis=$(date +%s%3N)
      fi
    done
  ) &
  LAG_SAMPLER_PID=$!
}

stop_lag_sampler() {
  [ -n "$LAG_SAMPLER_PID" ] || return 0
  kill "$LAG_SAMPLER_PID" 2>/dev/null || true
  wait "$LAG_SAMPLER_PID" 2>/dev/null || true
  LAG_SAMPLER_PID=""
}

# Sample what each part of the rig is doing once a second until stop_saturation_sampler:
# CPU of the host, the app and each container, disk, the consumer's busy share, the pool
# and Postgres sessions. lib/saturation_samples.py takes and reads the samples. SATURATION
# overrides the cell's default, on or off; off measures what the sampling itself costs.
# An app whose process cannot be inspected is not sampled, and its row says null.
#
# Usage: start_saturation_sampler <file> <default: on|off>
SATURATION_SAMPLER_PID=""
start_saturation_sampler() {
  local file=$1 pid
  rm -f "$file"
  [ "${SATURATION:-$2}" = on ] || return 0
  pid=$(app_pid) || pid=""
  if [ -z "$pid" ]; then
    echo "Note: cannot find the process serving $BASE_URL; the run is not sampled for saturation." >&2
    return 0
  fi
  python3 perf/lib/saturation_samples.py "$file" "$BASE_URL" "$pid" $$ "$K6_CONTAINER_LABEL" &
  SATURATION_SAMPLER_PID=$!
}

stop_saturation_sampler() {
  [ -n "$SATURATION_SAMPLER_PID" ] || return 0
  kill "$SATURATION_SAMPLER_PID" 2>/dev/null || true
  wait "$SATURATION_SAMPLER_PID" 2>/dev/null || true
  SATURATION_SAMPLER_PID=""
}

# Reconcile a drained run against the table: ACCEPTED_EVENTS is what k6 saw answered
# 202, MISSING_EVENTS how many of those have no row. Rows whose ID the run logged as
# unaccepted are left out of the count: k6 gave up on those requests, yet they may
# still have been stored, and counting them would hide a lost accepted event.
#
# Usage: reconcile_accepted <summary> <unaccepted_log>
reconcile_accepted() {
  local summary=$1 log=$2 run_id stored
  read -r run_id ACCEPTED_EVENTS < <(python3 -c '
import json, sys

with open(sys.argv[1]) as summary_file:
    summary = json.load(summary_file)

print(summary["run_id"], round(summary.get("accepted_events") or 0))
' "$summary") || {
    echo "Could not read the run and its accepted events from $summary." >&2
    return 1
  }
  stored=$({
    echo 'CREATE TEMP TABLE unaccepted (event_id TEXT);'
    echo 'COPY unaccepted FROM STDIN;'
    if [ -f "$log" ]; then sed -n 's/^unaccepted //p' "$log"; fi
    echo '\.'
    echo "SELECT count(*) FROM events e
          WHERE e.tenant_name = '$WRITE_BATCH_SOURCE' AND starts_with(e.event_id, 'evt_${run_id}_')
            AND NOT EXISTS (SELECT 1 FROM unaccepted u WHERE u.event_id = e.event_id);"
  } | psql_events -qtA -v ON_ERROR_STOP=1 | tr -d '[:space:]')
  [[ $stored =~ ^[0-9]+$ ]] || {
    echo "Could not count the stored events of run $run_id." >&2
    return 1
  }
  MISSING_EVENTS=$((ACCEPTED_EVENTS - stored))
}

# Fail a cell, once its row is journalled, when reconcile_accepted found accepted events
# without a row, or rows k6 neither saw accepted nor logged.
require_nothing_missing() {
  if [ "$MISSING_EVENTS" -gt 0 ]; then
    echo "$MISSING_EVENTS of $ACCEPTED_EVENTS accepted events are not in the table after the consumer drained." >&2
    return 1
  fi
  if [ "$MISSING_EVENTS" -lt 0 ]; then
    echo "$((-MISSING_EVENTS)) stored events were neither accepted nor logged as unaccepted; nothing vouches for this run." >&2
    return 1
  fi
}

# Once the consumer has drained, delete only test writes, then VACUUM for dead rows and
# visibility maps. ANALYZE removes row-count estimates that autoanalyze may have
# captured mid-write; otherwise following reads can plan for a larger corpus.
restore_seed_baseline() {
  wait_until_persisted || return 1
  psql_events -qc "DELETE FROM events WHERE tenant_name = '$WRITE_BATCH_SOURCE';" || return 1
  psql_events -qc 'VACUUM ANALYZE events;' || return 1
}

# Cache the starting row count in CORPUS_ROWS: reads preserve it and writes restore it.
# Recounting would scan the corpus and disturb caches before every cell. Read the
# variable directly; command substitution would discard its cached state.
CORPUS_ROWS=""
count_events() {
  [ -n "$CORPUS_ROWS" ] || CORPUS_ROWS=$(psql_events -tAc 'SELECT count(*) FROM events' \
    | tr -d '[:space:]') || return 1
  printf '%s' "$CORPUS_ROWS"
}

# Warm read JIT and the pool once per process, immediately before the first read.
# Bootstrap may precede write cells that churn the table; later reads reuse the warmed
# shared path.
READS_WARMED=0
warm_reads() {
  [ "$READS_WARMED" = 1 ] && return 0

  k6_run perf/read/load/stats-read.js \
    --env SEED_ANCHOR --env SEED_SPREAD_DAYS \
    -e ENDPOINT="$1" -e GROUP_BY="${2:-}" -e TOKEN="$SEED_TOKEN" \
    -e VUS=4 -e DURATION=10s -e SUMMARY_OUT=/dev/null >/dev/null 2>&1 || true
  READS_WARMED=1
}

# Return current events scan counters as JSON: idx_scan per secondary index and table
# seq_scan. Cells journal deltas over actual app queries, avoiding a duplicate EXPLAIN
# query. Missing indexes contribute zero; exclude the idempotency primary key.
read_scan_counters() {
  psql_events -tAc "
    SELECT json_build_object(
             'by_index',
             coalesce((SELECT json_object_agg(indexrelname, idx_scan)
                       FROM pg_stat_user_indexes
                       WHERE relname = 'events' AND indexrelname <> 'events_pkey'),
                      '{}'::JSON),
             'seq_scans',
             coalesce((SELECT seq_scan FROM pg_stat_user_tables
                       WHERE relname = 'events'), 0))"
}

# The pool the run ACTUALLY used, read straight from the app rather than trusted
# from an env var — a journalled config the run did not use would be a quiet lie.
read_pool() {
  curl -sf "$BASE_URL/actuator/metrics/hikaricp.connections.max" \
    | python3 -c 'import json, sys; print(int(json.load(sys.stdin)["measurements"][0]["value"]))' || {
    echo "Could not read pool size from the app's actuator metrics — is the 'metrics' endpoint exposed?" >&2
    return 1
  }
}

# The Kafka settings the running app uses, as a JSON object for a journal row.
#
# linger_ms comes from the process's command line, else from the application.yaml packaged
# in the jar it runs, and is null when neither sets it. Setting linger any other way fails
# the run, because the row could not say what the producer used. consumer_concurrency and
# partitions come from the app's own consumer metrics: how many persistence consumers run,
# and how many partitions they hold between them. A remote app records linger_ms as null.
read_kafka_settings() {
  local pid=""
  case "$BASE_URL" in
    *localhost*|*127.0.0.1*) pid=$(app_pid) || pid="" ;;
  esac
  python3 - "$BASE_URL" "$pid" <<'PY'
import json, os, re, sys, urllib.parse, urllib.request, zipfile

base_url, pid = sys.argv[1:3]
LINGER_ARGUMENT = re.compile(r"^--spring\.kafka\.producer\.properties\.linger\.ms=(\d+)$")
PACKAGED_CONFIG = "BOOT-INF/classes/application.yaml"
PACKAGED_LINGER = re.compile(r"^\s+linger\.ms:\s*(\d+)\s*$", re.MULTILINE)
PERSISTENCE_CONSUMER = re.compile(r"^consumer-persistence-\d+$")
ASSIGNED_PARTITIONS = "kafka.consumer.coordinator.assigned.partitions"
EXPLICIT_LINGER = "pass --spring.kafka.producer.properties.linger.ms=<ms> so the row can record it"


def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)


def metric(name, tag=None):
    url = base_url + "/actuator/metrics/" + name
    if tag:
        url += "?tag=" + urllib.parse.quote(tag)
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.load(response)


def packaged_linger(arguments):
    if "-jar" not in arguments:
        return None
    jar = os.path.join(os.readlink("/proc/%s/cwd" % pid), arguments[arguments.index("-jar") + 1])
    try:
        with zipfile.ZipFile(jar) as archive:
            config = archive.read(PACKAGED_CONFIG).decode()
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        fail("Could not read %s from %s (%s), so the row could not say what linger the app uses." % (PACKAGED_CONFIG, jar, error))
    match = PACKAGED_LINGER.search(config)
    return int(match.group(1)) if match else None


linger_ms = None
if pid:
    with open("/proc/%s/cmdline" % pid, "rb") as f:
        arguments = f.read().decode(errors="replace").split("\0")
    with open("/proc/%s/environ" % pid, "rb") as f:
        environment = f.read().decode(errors="replace").split("\0")
    for argument in arguments:
        match = LINGER_ARGUMENT.match(argument)
        if match:
            linger_ms = int(match.group(1))
        elif "linger" in argument.lower():
            fail("The app sets linger as '%s'; %s." % (argument, EXPLICIT_LINGER))
    if any("linger" in variable.lower() for variable in environment):
        fail("The app's environment sets linger; %s." % EXPLICIT_LINGER)
    if linger_ms is None:
        linger_ms = packaged_linger(arguments)

try:
    clients = [
        client
        for tag in metric(ASSIGNED_PARTITIONS)["availableTags"]
        if tag["tag"] == "client.id"
        for client in tag["values"]
        if PERSISTENCE_CONSUMER.match(client)
    ]
    partitions = sum(
        metric(ASSIGNED_PARTITIONS, "client.id:" + client)["measurements"][0]["value"]
        for client in clients
    )
except (OSError, ValueError, KeyError, IndexError) as error:
    fail("Could not read the persistence consumers from the app's metrics (%s); is the consumer running?" % error)

print(json.dumps({
    "linger_ms": linger_ms,
    "consumer_concurrency": len(clients),
    "partitions": round(partitions),
}))
PY
}

# The events table's index size in MB. Every insert-and-delete cycle grows it (see "The
# floor between runs" in perf/README.md), so a series of heavy write runs drifts; rows
# that record it show by how much.
read_index_mb() {
  psql_events -tAc "SELECT round(pg_indexes_size('events') / 1048576.0)" | tr -d '[:space:]' || {
    echo "Could not read the size of the events indexes." >&2
    return 1
  }
}

# Read cumulative Hikari connection timeouts; cells journal the run's delta. This
# distinguishes pool-wait 500s from other write failures with the same status.
read_connection_timeouts() {
  curl -sf "$BASE_URL/actuator/metrics/hikaricp.connections.timeout" \
    | python3 -c 'import json, sys; print(int(json.load(sys.stdin)["measurements"][0]["value"]))' || {
    echo "Could not read the pool's connection timeouts from the app's actuator metrics." >&2
    return 1
  }
}

# The schema the run actually hit, read from the migrated DB rather than assumed:
# secondary indexes (a GIN on properties especially) make every INSERT costlier,
# so throughput drops when they land — this stamp says "that drop is a new
# migration, not a regression" without a commit-by-commit hunt.
read_schema() {
  psql_events -tAc \
    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" \
    | tr -d '[:space:]' || {
    echo "Could not read the schema version from flyway_schema_history." >&2
    return 1
  }
}

# Probe the request meter to stamp instrumentation state automatically. Avoid
# /actuator/prometheus because probing it would contaminate scrape measurements. Only
# 404 means disabled; connection failures or 503s must not be recorded as denied
# metrics.
read_request_metrics() {
  local status
  status=$(curl -s -m 5 -o /dev/null -w '%{http_code}' \
    "$BASE_URL/actuator/metrics/http.server.requests") || status=000

  case "$status" in
    200) echo on ;;
    404) echo off ;;
    *)
      echo "Could not tell whether the app was publishing request metrics: $BASE_URL answered '$status'." >&2
      return 1
      ;;
  esac
}

# The window a run was measured in, as "<started_at> <finished_at>", read from the
# summary k6 wrote for it. The pair a cell hands to read_scrape is the pair its row
# carries, so the stamp cannot end up describing a different window than the
# figures beside it.
read_run_window() {
  python3 -c '
import json, sys

with open(sys.argv[1]) as summary_file:
    summary = json.load(summary_file)

print(summary["started_at"], summary["finished_at"])
' "$1" || {
    echo "Could not read the measured window from $1." >&2
    return 1
  }
}

# Query Prometheus for scrape attempts within [started_at, finished_at], evaluated at
# finished_at. A probe-time window could mislabel runs measured before the stack
# started.
#
# Use count_over_time(up), including failed scrapes: they still cost app work. One
# sample means on, including a partially scraped run or one shorter than the 2s
# interval. A stopped stack means off. Keep the job name aligned with
# observability/prometheus.yml.
#
# Usage: read_scrape <started_at> <finished_at>
read_scrape() {
  local started_at=$1 finished_at=$2

  local started_millis finished_millis
  started_millis=$(date -u -d "$started_at" +%s%3N) \
    && finished_millis=$(date -u -d "$finished_at" +%s%3N) || {
    echo "Could not read the window '$started_at' to '$finished_at' to ask Prometheus about it." >&2
    return 1
  }

  local window_millis=$((finished_millis - started_millis))
  [ "$window_millis" -gt 0 ] || window_millis=1

  local response
  response=$(curl -sf -m 2 --get "$PROMETHEUS_URL/api/v1/query" \
    --data-urlencode "query=count_over_time(up{job=\"event-analytics\"}[${window_millis}ms])" \
    --data-urlencode "time=$finished_at" 2>/dev/null) || {
    echo off
    return 0
  }

  printf '%s' "$response" | python3 -c '
import json, sys

try:
    samples = json.load(sys.stdin)["data"]["result"]
except Exception:
    samples = []

print("on" if any(float(sample["value"][1]) > 0 for sample in samples) else "off")
'
}

# Record a test's headline result for the end-of-run digest.
perf_result() {
  PERF_RESULTS+=("$1")
}

# Record a journal a test appended a row to, for the end-of-run dashboard sync.
perf_journalled() {
  PERF_JOURNALS+=("$1")
}

# Calculate spread from the journal rows just appended. group_key separates series such
# as event-counts groupings; omit it for one row per round. Report CV for clustering and
# peak-to-peak for the variation a single before/after pair can show.
#
# Usage: perf_spread <label> <journal> <rounds> <field> [group_key]
perf_spread() {
  local label=$1 journal=$2 rounds=$3 field=$4 group_key=${5:-}

  local out
  out=$(python3 - "$label" "$journal" "$rounds" "$field" "$group_key" <<'PY'
import json, statistics, sys

label, journal_path, rounds, field, group_key = sys.argv[1:6]
rounds = int(rounds)

with open(journal_path) as f:
    rows = [json.loads(line) for line in f if line.strip()]

series = {}
for row in rows:
    series.setdefault(row.get(group_key, "") if group_key else "", []).append(row)

print("\nSpread over the last %d rounds in %s:" % (rounds, journal_path))
for key, series_rows in series.items():
    values = sorted(row[field] for row in series_rows[-rounds:])
    low, high = values[0], values[-1]
    median = statistics.median(values)
    mean = statistics.fmean(values)
    peak_to_peak = (high - low) / median * 100 if median else 0.0
    variation = statistics.stdev(values) / mean * 100 if len(values) > 1 and mean else 0.0
    named = "%s=%s " % (group_key, key) if group_key else ""

    print("  %s%s: %s" % (named, field, ", ".join("%g" % value for value in values)))
    print("    min %g | median %g | max %g" % (low, median, high))
    print(
        "PERF_RESULT %s %sspread over %d rounds: peak-to-peak %.2f%%, "
        "coefficient of variation %.2f%% (median %g %s)"
        % (label, named, len(values), peak_to_peak, variation, median, field)
    )
PY
  ) || return 1
  echo "$out"

  local line
  while IFS= read -r line; do
    perf_result "$line"
  done < <(printf '%s\n' "$out" | sed -n 's/^PERF_RESULT //p')
}

# Run label:function cells sequentially, repeating each ROUNDS times (default 1). A
# failure stops that cell's remaining rounds, but other cells still run; report non-zero
# after all cells finish.
#
# For repeated cells, call the optional <function>_spread hook. Spike cells omit it
# because a scalar spread does not represent their recovery verdict.
perf_run_tests() {
  local rounds=${ROUNDS:-1}
  local failures=0 entry name fn round cell_failed

  # Rejected up front rather than left to arithmetic: bash evaluates ROUNDS=7x as
  # 0, which would run no rounds at all and still exit successfully — a silent
  # nothing after an unattended wait.
  if ! printf '%s' "$rounds" | grep -qE '^[1-9][0-9]*$'; then
    echo "ROUNDS must be a whole number of at least 1, got '$rounds'." >&2
    return 1
  fi

  for entry in "$@"; do
    name=${entry%%:*}
    fn=${entry#*:}
    cell_failed=0

    for ((round = 1; round <= rounds; round++)); do
      echo
      if [ "$rounds" -gt 1 ]; then
        echo ">>> perf: $name (round $round of $rounds)"
      else
        echo ">>> perf: $name"
      fi
      if ! "$fn"; then
        echo "!!! perf: $name failed" >&2
        failures=$((failures + 1))
        cell_failed=1
        break
      fi
    done

    if [ "$rounds" -gt 1 ] && [ "$cell_failed" -eq 0 ] \
      && declare -F "${fn}_spread" >/dev/null; then
      "${fn}_spread" "$rounds" || failures=$((failures + 1))
    fi
  done

  perf_report
  echo "Review the appended journal rows before committing."

  if [ "$failures" -gt 0 ]; then
    echo "$failures perf cell(s) failed." >&2
    return 1
  fi
}

# Print the digest and sync dashboard annotations after all journal rows are written.
# Syncing earlier would add HTTP/container work to measurements. Annotation failures are
# non-fatal; journals remain the record.
perf_report() {
  echo
  echo "=== perf results ==="
  local line
  for line in "${PERF_RESULTS[@]}"; do
    echo "  $line"
  done
  echo

  if [ "${#PERF_JOURNALS[@]}" -gt 0 ]; then
    perf/lib/annotate-runs.sh "${PERF_JOURNALS[@]}" || true
  fi
}