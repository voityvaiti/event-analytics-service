#!/bin/bash

# Read spike cell: surge GET /api/v1/stats/active-users far past what the pool
# can serve, then back down, and check the read path recovers. Defines
# perf_read_spike_active_users; the harness and perf/read/spike/measure-cell.sh
# must already be sourced. Appends one row to
# perf/read/spike/active-users/journal.jsonl.
#
# The heaviest read, so the lowest ceiling of the three: ~79 req/s on the
# reference rig, which 400 clears five times over. Journalled with groupBy=day,
# the endpoint default; hour is the same plan over more buckets and is reachable
# with GROUP_BY=hour rather than as its own row.
#
# Tunables via env: SPIKE_RATE (default 400), GROUP_BY, SPIKE_WINDOW,
# BASELINE_RATE, *_SECONDS, MAX_VUS.

perf_read_spike_active_users() {
  local journal=perf/read/spike/active-users/journal.jsonl

  perf_read_spike_cell "$journal" active-users "${SPIKE_RATE:-400}" "${GROUP_BY:-day}"
}
