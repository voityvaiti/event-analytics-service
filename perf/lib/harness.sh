#!/bin/bash

# Shared harness for the perf suite. Every test runner sources this, then a
# test's measure function (perf/<name>/measure.sh) and calls it. It owns the
# parts that are identical across tests — bringing up backing services, checking
# the app and the k6 image, resetting the table, and reading the rig/config
# stamps (pool, schema, CPU) that make a number mean something — so a new test
# file is only the k6 scenario plus how to summarise it, never this plumbing.
#
# Contract for a sourcing script:
#   - it has already cd'd to the repo root;
#   - it calls perf_bootstrap once, before any measure;
#   - k6 scenario files live at perf/<name>/<file>.js and are passed to k6_run
#     as repo-root-relative paths (k6 runs with the repo mounted at /work).

export BASE_URL=${BASE_URL:-http://localhost:8080}
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.50.0}

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

# Empty the table so a measured run starts from a known row count: inserts hit
# the event_id primary-key B-tree, whose cost rises with the row count, so a
# non-empty start would silently make one run slower than another.
reset_db() {
  docker compose exec -T postgres psql -U event_analytics -d event_analytics -c 'TRUNCATE events;'
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
  docker compose exec -T postgres psql -U event_analytics -d event_analytics -tAc \
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