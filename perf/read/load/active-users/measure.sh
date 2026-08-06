#!/bin/bash

# Read load cell: GET /api/v1/stats/active-users. Defines
# perf_read_load_active_users; the harness and perf/read/load/measure-cell.sh
# must already be sourced. Appends one row to
# perf/read/load/active-users/journal.jsonl.
#
# The heaviest of the read cells, and the only one whose cost is dominated by
# something the index cannot serve: user_id is not in it, so every matching row
# is fetched from the heap and fed through COUNT(DISTINCT). Journalled with
# groupBy=day, the endpoint default; hour is the same plan over more buckets and
# is reachable with GROUP_BY=hour rather than as its own row.
#
# Tunables via env: VUS (default 4), DURATION (default 30s), GROUP_BY.

perf_read_load_active_users() {
  local journal=perf/read/load/active-users/journal.jsonl

  perf_read_load_cell "$journal" active-users "${GROUP_BY:-day}"
}

perf_read_load_active_users_spread() {
  perf_spread "read load active-users" perf/read/load/active-users/journal.jsonl \
    "$1" p95_ms
}
