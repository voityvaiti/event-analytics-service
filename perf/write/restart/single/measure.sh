#!/bin/bash

# Stage 3's kill-and-restart exercise: one persistence consumer, single events arriving
# at RATE throughout, the consumer stopped for DOWNTIME_SECONDS from its confirmed
# STOPPED. See write/restart/measure-cell.sh for the timeline and verdict.
#
# Tunables: RATE (1000), WRITE_TIMEOUT_SECONDS (1), BASELINE_SECONDS (30),
# DOWNTIME_SECONDS (60), AFTER_SECONDS (90), DRAIN_BUDGET_SECONDS (60), JOURNAL (this
# cell's; point an experiment elsewhere).

perf_write_restart_single() {
  perf_write_restart_cell "${JOURNAL:-perf/write/restart/single/journal.jsonl}" "${RATE:-1000}"
}
