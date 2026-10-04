#!/bin/bash

# Measure event-counts surge and recovery. Requires the harness and
# read/spike/measure-cell.sh; append one journal row.
#
# Type grouping's ~840 req/s ceiling motivates SPIKE_RATE=4000. Hour/day cost ~2.5x
# more; recalculate the surge rate when changing grouping.
#
# Tunables: SPIKE_RATE (4000), GROUP_BY, SPIKE_WINDOW, BASELINE_RATE, *_SECONDS,
# MAX_VUS.

perf_read_spike_event_counts() {
  local journal=perf/read/spike/event-counts/journal.jsonl

  perf_read_spike_cell "$journal" event-counts "${SPIKE_RATE:-4000}" "${GROUP_BY:-type}"
}
