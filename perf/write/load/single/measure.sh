#!/bin/bash

# Measure single-event ingestion using the harness and write/load/measure-cell.sh;
# append one journal row. VUS=10 matches the default pool; higher concurrency adds
# connection wait.
#
# Tunables: VUS (10), DURATION (60s).

perf_write_load_single() {
  perf_write_load_cell perf/write/load/single/journal.jsonl \
    perf/write/load/ingest-events.js "${VUS:-10}" "${DURATION:-60s}"
}

# Use throughput spread to distinguish small write costs from jitter. Latency
# percentiles provide context but do not decide the comparison.
perf_write_load_single_spread() {
  perf_spread "write load single" perf/write/load/single/journal.jsonl \
    "$1" throughput_rps
}
