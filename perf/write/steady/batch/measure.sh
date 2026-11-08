#!/bin/bash

# Offer batches of BATCH_SIZE events at a fixed event rate and judge whether the consumer
# persists them as they arrive; see write/steady/measure-cell.sh for the verdict. The default
# rate is the regression floor, 80% of the 85,000 events/s the capacity experiment sustained
# in batches of 100 on this rig. Lag counts records, so its bound is the request rate.
#
# Tunables: RATE (68000, events/s), BATCH_SIZE (100), WRITE_TIMEOUT_SECONDS (1),
# MAX_VUS (derived), PHASES (warmup:30,measure:180), JOURNAL (this cell's).

perf_write_steady_batch() {
  perf_write_steady_cell "${JOURNAL:-perf/write/steady/batch/journal.jsonl}" "${RATE:-68000}" "${BATCH_SIZE:-100}"
}
