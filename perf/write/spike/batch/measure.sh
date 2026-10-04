#!/bin/bash

# Measure batch surge and recovery using the harness and write/spike/measure-cell.sh;
# append one journal row.
#
# From load medians of 1252 req/s and p95 7.78ms: surge 2500 (~2x), baseline 150 (~12%),
# healthy-baseline bound 100ms (~13x p95). Keep phase durations and MAX_VUS aligned with
# single-event spikes; compare events/s. See the cell README for calibration.
#
# Tunables: SPIKE_RATE (2500), BATCH_SIZE (100), BASELINE_RATE (150), *_SECONDS,
# MAX_VUS.

perf_write_spike_batch() {
  perf_write_spike_cell perf/write/spike/batch/journal.jsonl \
    perf/write/spike/spike-batches.js perf/write/load/ingest-batches.js \
    "${SPIKE_RATE:-2500}" 100 "${BATCH_SIZE:-100}"
}
