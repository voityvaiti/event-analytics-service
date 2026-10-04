#!/bin/bash

# Measure single-event surge and recovery using the harness and
# write/spike/measure-cell.sh; append one journal row. SPIKE_RATE=8000 is ~2x measured
# capacity. The 50ms baseline bound tolerates jitter around healthy ~2ms inserts.
#
# Tunables: SPIKE_RATE (8000), BASELINE_RATE, *_SECONDS, MAX_VUS.

perf_write_spike_single() {
  perf_write_spike_cell perf/write/spike/single/journal.jsonl \
    perf/write/spike/spike-events.js perf/write/load/ingest-events.js \
    "${SPIKE_RATE:-8000}" 50
}
