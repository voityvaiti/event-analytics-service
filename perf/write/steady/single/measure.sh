#!/bin/bash

# Offer single events at a fixed rate and judge whether the consumer persists them as
# they arrive; see write/steady/measure-cell.sh for the verdict. The default rate is the
# regression floor, 80% of the 25,000/s the capacity experiment sustained on this rig;
# RATE=3700 repeats Stage 3's floor.
#
# Tunables: RATE (20000), WRITE_TIMEOUT_SECONDS (1), MAX_VUS (derived, at most 4,000),
# PHASES (warmup:30,measure:180), JOURNAL (this cell's; point an experiment elsewhere).

perf_write_steady_single() {
  perf_write_steady_cell "${JOURNAL:-perf/write/steady/single/journal.jsonl}" "${RATE:-20000}"
}
