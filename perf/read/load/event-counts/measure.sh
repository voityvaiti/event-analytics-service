#!/bin/bash

# Read load cell: GET /api/v1/stats/event-counts, one measured run per grouping.
# Defines perf_read_load_event_counts; the harness and
# perf/read/load/measure-cell.sh must already be sourced. Appends one row per
# grouping to perf/read/load/event-counts/journal.jsonl.
#
# The groupings are separate rows because they are separate query plans, not
# cosmetic variants: groupBy=type aggregates on the index's trailing column and
# never touches the heap, while the time groupings bucket through date_trunc.
# Their sensitivity to the index is the thing worth reading off individually.
#
# Tunables via env: VUS (default 4), DURATION (default 30s).

perf_read_load_event_counts() {
  local journal=perf/read/load/event-counts/journal.jsonl
  local grouping status=0

  for grouping in type hour day; do
    perf_read_load_cell "$journal" event-counts "$grouping" || status=$?
  done

  return "$status"
}

# Grouped by group_by, because a round appends one row per grouping and those are
# three different query plans — pooling them would report the gap between plans
# as if it were jitter within one.
perf_read_load_event_counts_spread() {
  perf_spread "read load event-counts" perf/read/load/event-counts/journal.jsonl \
    "$1" p95_ms group_by
}
