#!/bin/bash

# Write spike cell: surge POST /api/v1/events/batch past what the pool can absorb,
# then back down, and check the batch path recovers. Defines
# perf_write_spike_batch; the harness and perf/write/spike/measure-cell.sh must
# already be sourced. Appends one row to perf/write/spike/batch/journal.jsonl.
#
# SPIKE_RATE 2000, BASELINE_RATE 50 and BASELINE_MAX_P95_MS 500 are all
# predictions, not measurements — this cell's load journal is still empty. The
# rules they are placeholders for are the single-event cell's: surge at ~2x the
# request rate the same shape sustains under load, hold the baseline at roughly a
# tenth of it (500 against ~4100 there), and set the healthy-baseline ceiling an
# order of magnitude above the p95 that shape actually answers in. All three get
# replaced by figures read off perf/write/load/batch/journal.jsonl once it has
# rows, as the read spike cells' rates were.
#
# The baseline needs re-deriving as much as the surge does, and for the same
# reason in reverse: a rate the app does not notice measures idle rather than a
# healthy state, and the recovery clause compares recovery p95 against it. Two
# numbers both at the floor make a verdict one scheduler hiccup decides.
#
# 2000 rather than the 600 this cell was first written with, because 600 is not a
# surge: a plumbing check on an empty table at batch 25 absorbed it with nothing
# dropped and p95 at 3ms. That check is not a measurement — no corpus, a 5s window,
# a quarter of the batch size — but it is enough to rule out a rate the app plainly
# does not notice, and a cell that never surges always passes.
#
# Held equal with the single-event cell so the two rows stay comparable:
# BATCH_SIZE aside, the phase durations, MAX_VUS and the surge multiple are the
# same. What differs is that each request here carries 100 events, so the row's
# spike_achieved_events_per_sec is the field the two cells share.
#
# Tunables via env: SPIKE_RATE (default 2000), BATCH_SIZE (default 100),
# BASELINE_RATE (default 50, in spike-batches.js), *_SECONDS, MAX_VUS.

perf_write_spike_batch() {
  perf_write_spike_cell perf/write/spike/batch/journal.jsonl \
    perf/write/spike/spike-batches.js perf/write/load/ingest-batches.js \
    "${SPIKE_RATE:-2000}" 500 "${BATCH_SIZE:-100}"
}
