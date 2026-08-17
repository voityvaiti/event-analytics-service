#!/bin/bash

# Write spike cell: surge POST /api/v1/events far past steady-state capacity, then
# back down, and check the ingest path recovers. Defines perf_write_spike_single;
# the harness and perf/write/spike/measure-cell.sh must already be sourced.
# Appends one row to perf/write/spike/single/journal.jsonl.
#
# SPIKE_RATE 8000 is ~2x the ~4100 req/s this shape sustains in its load journal,
# which is enough to outrun the pool and leave a queue — the load cell has never
# come close to failing, so the surge does not need the 5x the read cells take.
#
# BASELINE_MAX_P95_MS 50 is the healthy-baseline precondition, more than an order
# of magnitude above the ~2ms a single insert actually answers in, so it marks
# genuine saturation of the write path rather than jitter around it.
#
# Tunables via env: SPIKE_RATE (default 8000), BASELINE_RATE, *_SECONDS, MAX_VUS.

perf_write_spike_single() {
  perf_write_spike_cell perf/write/spike/single/journal.jsonl \
    perf/write/spike/spike-events.js perf/write/load/ingest-events.js \
    "${SPIKE_RATE:-8000}" 50
}
