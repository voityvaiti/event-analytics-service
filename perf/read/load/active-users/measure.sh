#!/bin/bash

# Measure active-users latency and append one journal row. Requires the harness and
# read/load/measure-cell.sh. V7 covers user_id; wide-window cost is dominated by the
# distinct-count sort.
#
# Tunables: VUS (4), DURATION (30s), GROUP_BY (day; hour creates more buckets).

perf_read_load_active_users() {
  local journal=perf/read/load/active-users/journal.jsonl

  perf_read_load_cell "$journal" active-users "${GROUP_BY:-day}"
}

perf_read_load_active_users_spread() {
  perf_spread "read load active-users" perf/read/load/active-users/journal.jsonl \
    "$1" p95_ms
}
