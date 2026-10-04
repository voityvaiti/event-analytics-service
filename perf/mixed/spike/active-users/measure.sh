#!/bin/bash

# Measure steady writes during an active-users surge (groupBy=day), the read that holds
# the pool longest. Source the harness and mixed/spike/measure-cell.sh first; append to
# this cell's journal.
#
# Tunables: WRITE_RATE (1000), WRITE_TIMEOUT_SECONDS (5), WRITE_MAX_VUS (derived),
# READ_SPIKE_RATE (400), READ_BASELINE_RATE (20), READ_MAX_VUS (500), GROUP_BY,
# SPIKE_WINDOW, *_SECONDS.

perf_mixed_spike_active_users() {
  perf_mixed_spike_cell perf/mixed/spike/active-users/journal.jsonl \
    active-users "${GROUP_BY:-day}"
}
