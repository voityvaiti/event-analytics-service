#!/bin/bash

# Measure top-pages surge and recovery. Requires the harness and
# read/spike/measure-cell.sh; append one journal row. SPIKE_RATE=1500 is ~5x the
# reference ~310 req/s ceiling. LIMIT bounds output, not scanned rows.
#
# Tunables: SPIKE_RATE (1500), LIMIT (10), SPIKE_WINDOW, BASELINE_RATE, *_SECONDS,
# MAX_VUS.

perf_read_spike_top_pages() {
  local journal=perf/read/spike/top-pages/journal.jsonl

  perf_read_spike_cell "$journal" top-pages "${SPIKE_RATE:-1500}" "" "${LIMIT:-10}"
}
