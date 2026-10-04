#!/bin/bash

# Measure top-pages latency and append one row. Requires the harness and
# read/load/measure-cell.sh. The index narrows the window; JSONB extraction and ranking
# still scale with matching rows.
#
# Tunables: VUS (4), DURATION (30s), LIMIT (10).

perf_read_load_top_pages() {
  local journal=perf/read/load/top-pages/journal.jsonl

  perf_read_load_cell "$journal" top-pages "" "${LIMIT:-10}"
}

perf_read_load_top_pages_spread() {
  perf_spread "read load top-pages" perf/read/load/top-pages/journal.jsonl \
    "$1" p95_ms
}
