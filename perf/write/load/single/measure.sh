#!/bin/bash

# Write load cell: POST /api/v1/events, one event per request. Defines
# perf_write_load_single; the harness and perf/write/load/measure-cell.sh must
# already be sourced. Appends one row to perf/write/load/single/journal.jsonl.
#
# VUS defaults to 10 to sit at the pool: past ~pool size the number stops being
# insert cost and becomes connection-wait.
#
# Tunables via env: VUS (default 10), DURATION (default 60s).

perf_write_load_single() {
  perf_write_load_cell perf/write/load/single/journal.jsonl \
    perf/write/load/ingest-events.js "${VUS:-10}" "${DURATION:-60s}"
}

# Throughput is the field whose spread decides anything here: the write side of a
# secondary index costs a few percent either way, close enough to jitter that the
# two are easy to confuse, so the floor has to be known before a delta is called
# real. Latency percentiles ride along in the rows for context but are not what a
# write comparison turns on.
perf_write_load_single_spread() {
  perf_spread "write load single" perf/write/load/single/journal.jsonl \
    "$1" throughput_rps
}
