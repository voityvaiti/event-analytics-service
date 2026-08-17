#!/bin/bash

# Write load cell: POST /api/v1/events/batch, BATCH_SIZE events per request.
# Defines perf_write_load_batch; the harness and perf/write/load/measure-cell.sh
# must already be sourced. Appends one row to
# perf/write/load/batch/journal.jsonl.
#
# VUS is held at the single-event cell's 10 on purpose — same concurrency, same
# pool, so the only thing that differs between the two series is how many events
# a request carries.
#
# DURATION is 30s where the single-event cell takes 60s, because this cell writes
# BATCH_SIZE times as many rows per request: a window that adds a large fraction
# of the corpus would change the table it is measuring against, and the delete and
# vacuum afterwards grow with it. It is a journalled field, so the shorter window
# is visible rather than assumed.
#
# Tunables via env: VUS (default 10), DURATION (default 30s), BATCH_SIZE
# (default 100).

perf_write_load_batch() {
  perf_write_load_cell perf/write/load/batch/journal.jsonl \
    perf/write/load/ingest-batches.js "${VUS:-10}" "${DURATION:-30s}" "${BATCH_SIZE:-100}"
}

# Events per second, not requests per second: the point of a batch is how many
# events a second it persists, and its request rate is that number divided by a
# batch size that is itself a knob. Taken over its own rows only — this is a
# regime of its own, and the single-event cell's ~6% floor was measured on a
# different amount of work per request.
perf_write_load_batch_spread() {
  perf_spread "write load batch" perf/write/load/batch/journal.jsonl \
    "$1" events_per_sec
}
