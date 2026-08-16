#!/bin/bash

# Read spike cell: surge GET /api/v1/stats/event-counts far past what the pool
# can serve, then back down, and check the read path recovers. Defines
# perf_read_spike_event_counts; the harness and perf/read/spike/measure-cell.sh
# must already be sourced. Appends one row to
# perf/read/spike/event-counts/journal.jsonl.
#
# The lightest read, so the highest ceiling of the three: ~840 req/s on the
# reference rig, an order of magnitude above active-users, and 4000 is what it
# takes to clear it five times over. Journalled with groupBy=type, the endpoint
# default and the cheapest of its plans — the one that aggregates off the index's
# trailing column without touching the heap. The time groupings cost ~2.5x more
# per query, so GROUP_BY=hour or day wants SPIKE_RATE re-derived with it; left at
# 4000 the surge is 11x their ceiling rather than 5x.
#
# Tunables via env: SPIKE_RATE (default 4000), GROUP_BY, SPIKE_WINDOW,
# BASELINE_RATE, *_SECONDS, MAX_VUS.

perf_read_spike_event_counts() {
  local journal=perf/read/spike/event-counts/journal.jsonl

  perf_read_spike_cell "$journal" event-counts "${SPIKE_RATE:-4000}" "${GROUP_BY:-type}"
}
