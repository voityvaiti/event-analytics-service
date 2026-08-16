#!/bin/bash

# Read spike cell: surge GET /api/v1/stats/top-pages far past what the pool can
# serve, then back down, and check the read path recovers. Defines
# perf_read_spike_top_pages; the harness and perf/read/spike/measure-cell.sh must
# already be sourced. Appends one row to
# perf/read/spike/top-pages/journal.jsonl.
#
# Ceiling ~310 req/s on the reference rig, between the other two, and 1500 clears
# it five times over. Journalled with limit=10, the endpoint default: the limit
# bounds what is returned, not what is read, so it moves the ceiling far less
# than the endpoint's own shape does.
#
# Tunables via env: SPIKE_RATE (default 1500), LIMIT (default 10), SPIKE_WINDOW,
# BASELINE_RATE, *_SECONDS, MAX_VUS.

perf_read_spike_top_pages() {
  local journal=perf/read/spike/top-pages/journal.jsonl

  perf_read_spike_cell "$journal" top-pages "${SPIKE_RATE:-1500}" "" "${LIMIT:-10}"
}
