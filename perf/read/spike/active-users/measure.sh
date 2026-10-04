#!/bin/bash

# Measure active-users surge and recovery. Requires the harness and
# read/spike/measure-cell.sh; append one journal row.
#
# The original ~79 req/s ceiling motivated 400 req/s (~5x). Recalculate after pool,
# corpus, or plan changes. Tunables: SPIKE_RATE (400), GROUP_BY (day), SPIKE_WINDOW,
# BASELINE_RATE, *_SECONDS, MAX_VUS.

perf_read_spike_active_users() {
  local journal=perf/read/spike/active-users/journal.jsonl

  perf_read_spike_cell "$journal" active-users "${SPIKE_RATE:-400}" "${GROUP_BY:-day}"
}
