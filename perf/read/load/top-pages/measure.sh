#!/bin/bash

# Read load cell: GET /api/v1/stats/top-pages. Defines
# perf_read_load_top_pages; the harness and perf/read/load/measure-cell.sh must
# already be sourced. Appends one row to
# perf/read/load/top-pages/journal.jsonl.
#
# The one read whose grouping key lives inside JSONB: properties->>'page_url' is
# extracted per row and ranked, so the index can narrow the window but nothing
# more. If an expression or GIN index on that key is ever considered, this is
# the cell that would show whether it earns its keep.
#
# Tunables via env: VUS (default 4), DURATION (default 30s), LIMIT (default 10).

perf_read_load_top_pages() {
  local journal=perf/read/load/top-pages/journal.jsonl

  perf_read_load_cell "$journal" top-pages "" "${LIMIT:-10}"
}

perf_read_load_top_pages_spread() {
  perf_spread "read load top-pages" perf/read/load/top-pages/journal.jsonl \
    "$1" p95_ms
}
