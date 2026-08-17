#!/bin/bash

# Write spike cell: surge POST /api/v1/events/batch past what the pool can absorb,
# then back down, and check the batch path recovers. Defines
# perf_write_spike_batch; the harness and perf/write/spike/measure-cell.sh must
# already be sourced. Appends one row to perf/write/spike/batch/journal.jsonl.
#
# All three constants are derived from this shape's own load journal — 1252 req/s
# sustained at p95 7.78ms, median of three rounds at 20M rows — by the multiples
# the single-event cell uses of its own numbers:
#
#   SPIKE_RATE           2500 = 2.0x sustained      (single: 8000 = 2.1x of 3756)
#   BASELINE_RATE         150 = 12% of sustained    (single:  500 = 13% of 3756)
#   BASELINE_MAX_P95_MS   100 = 12.9x load p95      (single:   50 = 12.7x of 3.93)
#
# The baseline matters as much as the surge, for the same reason in reverse: a rate
# the app does not notice measures idle rather than a healthy state, and the
# recovery clause compares recovery p95 against it. Two numbers both at the floor
# make a verdict one scheduler hiccup decides.
#
# The surge started life at 600 before there was anything to derive it from, and a
# plumbing check absorbed that without dropping a request — a cell that never
# surges always passes, which is why the rate had to come from a measurement.
#
# Held equal with the single-event cell so the two rows stay comparable:
# BATCH_SIZE aside, the phase durations, MAX_VUS and the surge multiple are the
# same. What differs is that each request here carries 100 events, so the row's
# spike_achieved_events_per_sec is the field the two cells share.
#
# Tunables via env: SPIKE_RATE (default 2500), BATCH_SIZE (default 100),
# BASELINE_RATE (default 150, in spike-batches.js), *_SECONDS, MAX_VUS.

perf_write_spike_batch() {
  perf_write_spike_cell perf/write/spike/batch/journal.jsonl \
    perf/write/spike/spike-batches.js perf/write/load/ingest-batches.js \
    "${SPIKE_RATE:-2500}" 100 "${BATCH_SIZE:-100}"
}
