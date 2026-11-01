#!/bin/bash

# Offer single events at a fixed rate and judge whether the consumer persists them as
# they arrive; see write/steady/measure-cell.sh for the verdict. The default rate is the
# sync path's closed-model throughput, Stage 3's floor; what lies above it is the
# closed-model load cell's question.
#
# Tunables: RATE (3700), WRITE_TIMEOUT_SECONDS (1), MAX_VUS (derived),
# PHASES (warmup:30,measure:180).

perf_write_steady_single() {
  perf_write_steady_cell perf/write/steady/single/journal.jsonl "${RATE:-3700}"
}
