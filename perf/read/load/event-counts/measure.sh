#!/bin/bash

# Measure event-counts separately for each grouping and append one row each. Requires
# the harness and read/load/measure-cell.sh. Type counts aggregate indexed values;
# hour/day add date_trunc bucketing.
#
# Tunables: VUS (4), DURATION (30s).

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
