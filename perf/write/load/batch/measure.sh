#!/bin/bash

# Measure batch ingestion using the shared harness and write/load/measure-cell.sh. Match
# single-event VUS=10 for comparison. Use 30s rather than 60s to limit corpus growth and
# cleanup cost; journal the duration.
#
# Tunables: VUS (10), DURATION (30s), BATCH_SIZE (100).

perf_write_load_batch() {
  perf_write_load_cell perf/write/load/batch/journal.jsonl \
    perf/write/load/ingest-batches.js "${VUS:-10}" "${DURATION:-30s}" "${BATCH_SIZE:-100}"
}

# Report spread over events/s, comparable across batch sizes. Derive this cell's noise
# floor independently of single-event writes.
perf_write_load_batch_spread() {
  perf_spread "write load batch" perf/write/load/batch/journal.jsonl \
    "$1" events_per_sec
}
