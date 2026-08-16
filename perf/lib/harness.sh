#!/bin/bash

# Shared harness for the perf suite. Every test runner sources this, then a
# cell's measure function (perf/<path>/<workload>/[<endpoint>/]measure.sh) and
# calls it. It owns the parts that are identical across tests — bringing up
# backing services, checking the app and the k6 image, resetting the table, and
# reading the rig/config stamps (pool, schema, CPU) that make a number mean
# something — so a new test file is only the k6 scenario plus how to summarise
# it, never this plumbing.
#
# Contract for a sourcing script:
#   - it has already cd'd to the repo root;
#   - it calls perf_bootstrap once, before any measure;
#   - k6 scenario files live beside the cells that run them and are passed to
#     k6_run as repo-root-relative paths (k6 runs with the repo mounted at
#     /work).

export BASE_URL=${BASE_URL:-http://localhost:8080}
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.50.0}

# The seeder is JavaScript so it can share the k6 event generator; like k6, node
# is not installed on the host but pulled as a pinned image.
NODE_IMAGE=${NODE_IMAGE:-node:22-alpine}

# The corpus every test measures against. Exported so the seeder reads the same
# definition the read scenarios will query against.
export SEED_ROWS=${SEED_ROWS:-20000000}
export SEED_SPREAD_DAYS=${SEED_SPREAD_DAYS:-180}
export SEED_ANCHOR=${SEED_ANCHOR:-2026-01-01T00:00:00Z}

# Row sources, split so a write test's batch can be told apart from the corpus
# and deleted on its own. Both mirror constants in the JavaScript that writes
# them — SOURCE in perf/lib/event-generator.js and perf/lib/seed-corpus.mjs.
SEED_SOURCE=perf-seed
WRITE_BATCH_SOURCE=perf-test

# One-line result per test, printed together by perf_report at the end so a
# multi-test pipeline run leaves a single readable digest, not a scroll-back.
PERF_RESULTS=()

# Bring up dependencies and verify the app and k6 image are usable, failing here
# with a clear cause rather than as a swallowed error mid-measurement. Safe to
# call once per process; the pipeline calls it once and then runs every test.
perf_bootstrap() {
  scripts/actions/dependencies

  if ! curl -sf "$BASE_URL/actuator/health" | grep -q '"status":"UP"'; then
    echo "App is not healthy at $BASE_URL — start it (e.g. scripts/actions/start) and retry." >&2
    return 1
  fi

  # Pull/verify the k6 image up front so a missing image or broken Docker fails
  # here, not as a swallowed warm-up error surfacing later.
  if ! docker run --rm "$K6_IMAGE" version >/dev/null 2>&1; then
    echo "Could not run the k6 image '$K6_IMAGE' — is Docker available and the image pullable?" >&2
    return 1
  fi

  seed_corpus
}

# Run the pinned k6 image against the host-resident app and repo.
#   --network host    so localhost:8080 (and the summary's base_url) match a
#                     host run exactly. Linux-only; Docker Desktop would instead
#                     need BASE_URL=http://host.docker.internal:8080.
#   --user $(id -u)…  so the summary file k6 writes into the mounted repo is
#                     owned by us, not the image's baked-in uid.
# Usage: k6_run <script.js> [extra docker/k6 args...]. The common env (base URL,
# run id, token, summary path) is forwarded here; a test appends its own knobs
# as further --env flags, and a later -e wins so a warm-up can override them.
k6_run() {
  local script="$1"
  shift
  docker run --rm --network host \
    --user "$(id -u):$(id -g)" \
    --volume "$PWD":/work --workdir /work \
    --env BASE_URL --env RUN_ID --env TOKEN --env SUMMARY_OUT \
    "$@" \
    "$K6_IMAGE" run "$script"
}

psql_events() {
  docker compose exec -T postgres psql -U event_analytics -d event_analytics "$@"
}

# Load the fixed corpus every test measures against, so runs start from a known
# non-empty table rather than an empty one — an index the planner ignores on an
# empty table would tell us nothing, and production tables are not empty either.
# Deterministic: the same SEED_ROWS always rebuilds the same corpus, so two runs
# stay comparable.
#
# Rows are streamed straight into COPY instead of posted to the API, which turns
# an hour of ingest at measured throughput into a couple of minutes. The closing
# VACUUM ANALYZE is not housekeeping: ANALYZE gives the planner statistics for
# the new size (stale ones can make it skip the index altogether, measuring the
# wrong thing), and VACUUM sets the visibility map that index-only scans need.
# Autovacuum does both eventually — on its own schedule, possibly in the middle
# of a measured run.
seed_corpus() {
  # SEED_ROWS=0 runs the same suite against an empty table, which is how much of
  # a read number belongs to the corpus rather than to the code. Answered here
  # and not through the paths below: the generator rejects a zero-row corpus, and
  # the reuse check would skip the ANALYZE — TRUNCATE leaves the planner's row
  # estimate untouched, so it would go on choosing plans for a corpus that is no
  # longer there.
  if [ "$SEED_ROWS" = 0 ]; then
    echo "Emptying the table — SEED_ROWS=0 measures against no corpus at all."
    psql_events -qc 'TRUNCATE events;' || return 1
    psql_events -qc 'VACUUM ANALYZE events;' || return 1
    return 0
  fi

  local existing
  existing=$(psql_events -tAc "SELECT count(*) FROM events WHERE source = '$SEED_SOURCE'" \
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
      "COPY events (event_id, source, user_id, event_type, occurred_at, properties)
       FROM STDIN WITH (FORMAT csv)" || return 1
  psql_events -qc 'VACUUM ANALYZE events;' || return 1
}

# Restore the corpus after a write test by deleting exactly the batch it posted.
# Reads leave the table alone, so only writers need this; vacuuming afterwards
# keeps their dead rows from accumulating across tests and keeps the visibility
# map current for the reads that follow.
#
# ANALYZE as well, because the write cells run before the read cells: a measured
# write run adds and then deletes a few hundred thousand rows, and if autoanalyze
# catches it mid-flight the read cells plan against a row estimate that includes a
# batch no longer in the table. Against 20M rows that is a rounding error; against
# 2M it is over a tenth of the table, and against an empty one it is the whole
# difference between an index and a sequential scan.
restore_seed_baseline() {
  psql_events -qc "DELETE FROM events WHERE source = '$WRITE_BATCH_SOURCE';" || return 1
  psql_events -qc 'VACUUM ANALYZE events;' || return 1
}

# The row count a measured run actually started from, journalled so a number is
# never read against the wrong table size. Counted once and remembered: it is
# the same for every cell by construction — reads never touch it and writes put
# back exactly what they added — and the count itself is a full scan of the
# corpus, which would otherwise sweep gigabytes through the buffer cache
# immediately before each measurement.
# Also left in CORPUS_ROWS, so a caller can read it without the command
# substitution that would discard the cache on every cell.
CORPUS_ROWS=""
count_events() {
  [ -n "$CORPUS_ROWS" ] || CORPUS_ROWS=$(psql_events -tAc 'SELECT count(*) FROM events' \
    | tr -d '[:space:]') || return 1
  printf '%s' "$CORPUS_ROWS"
}

# Warm JIT and the connection pool for the read path, once per process rather
# than once per cell. The pool fills on first use and never shrinks, and the
# preceding cell's own measured run warms the shared HTTP and JDBC path better
# than a short pass ever could — so repeating it per cell only spends time.
# Deliberately not folded into perf_bootstrap: a write cell churns the table
# between bootstrap and the first read, and a single-cell run still needs it.
READS_WARMED=0
warm_reads() {
  [ "$READS_WARMED" = 1 ] && return 0

  k6_run perf/read/load/stats-read.js \
    --env SEED_ANCHOR --env SEED_SPREAD_DAYS \
    -e ENDPOINT="$1" -e GROUP_BY="${2:-}" \
    -e VUS=4 -e DURATION=10s -e SUMMARY_OUT=/dev/null >/dev/null 2>&1 || true
  READS_WARMED=1
}

# "index scans, sequential scans" as of now. A read cell journals the delta over
# its measured run, which is what keeps a flat read-latency result from being
# ambiguous: it says outright whether the planner reached for the index or swept
# the table instead. Taken from counters over the queries the app actually ran,
# rather than from EXPLAIN on a copy of the SQL — the statements live in the
# repository class, and a second copy here would be one more thing to keep in
# step. Reports 0 index scans when the index does not exist, which is exactly
# what the without-index arm of the experiment should record.
read_scan_counters() {
  psql_events -tAc "
    SELECT coalesce((SELECT idx_scan FROM pg_stat_user_indexes
                     WHERE indexrelname = 'idx_events_occurred_at'), 0)
           || ' ' ||
           coalesce((SELECT seq_scan FROM pg_stat_user_tables
                     WHERE relname = 'events'), 0)"
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

# Record a test's headline result for the end-of-run digest.
perf_result() {
  PERF_RESULTS+=("$1")
}

# The spread across the rows a repeated cell just appended — jitter alone, since
# nothing changed between rounds. Shared by every cell that reports one, so the
# statistics and their wording are defined once; a cell only says which journal,
# which field, and how its rows are grouped.
#
# Read back off the journal rather than accumulated in shell state, so what this
# reports is exactly what was recorded rather than a parallel tally that could
# disagree with it.
#
# group_key names a field whose distinct values split the rows into separate
# series: event-counts appends one row per grouping every round, and a spread
# taken across groupings would compare different query plans instead of the same
# plan twice. Omit it when a round appends a single row.
#
# Reports peak-to-peak as well as the coefficient of variation because they
# answer different questions. The coefficient says how tightly the rounds
# cluster; peak-to-peak is what a single before/after pair can differ by on luck
# alone, and one run each side is what a comparison usually is.
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

# Run a list of "label:function" cells back to back and report them together.
# A failing cell does not abort the rest — stopping at the first problem would
# hide every number behind it — so the caller gets a non-zero exit only once
# everything has had its turn.
#
# ROUNDS repeats each cell that many times before moving to the next, and lives
# here rather than in the cells so every entry point honours it and no new cell
# can forget to. Repetition is the only way a spread gets measured at all: nothing
# changes between rounds, so however much they disagree is what jitter alone
# produces, and a later delta smaller than that is not a result. A cell that fails
# stops repeating — its remaining rounds would only re-measure whatever broke.
#
# The default is 1, so an ordinary run stays the single cheap measurement it has
# always been and repetition is a deliberate act. Ask for rounds when the number
# is going to be compared against something — establishing the noise floor, or
# measuring either side of a change — and the run costs that many times as long,
# which is a price worth paying knowingly rather than by default.
#
# After a repeated cell, a <function>_spread hook is called with the round count
# if the cell defines one. The spike cells deliberately define none: their
# overload metrics are too high-variance to reduce to a delta (see the suite
# README), so publishing a spread over them would invite exactly the comparison
# that is not supportable.
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
  echo "Eyeball the appended journal lines above, then commit them yourself."

  if [ "$failures" -gt 0 ]; then
    echo "$failures perf cell(s) failed." >&2
    return 1
  fi
}

# Print the collected headline results together. Called once at the end of a run.
perf_report() {
  echo
  echo "=== perf results ==="
  local line
  for line in "${PERF_RESULTS[@]}"; do
    echo "  $line"
  done
  echo
}