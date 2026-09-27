#!/bin/bash

# Mixed spike cell: post single events at a steady rate while GET
# /api/v1/stats/active-users surges far past what the pool can serve, and
# record how much of that ingest was not accepted. Defines
# perf_mixed_spike_active_users; the harness and perf/mixed/spike/measure-cell.sh
# must already be sourced. Appends one row to
# perf/mixed/spike/active-users/journal.jsonl.
#
# active-users because its surge is the one that holds the pool longest: it is
# the heaviest read, and its read spike cell still drains after the surge ends.
# groupBy=day, as in that cell.
#
# Tunables via env: WRITE_RATE (default 1000), WRITE_MAX_VUS (6000),
# READ_SPIKE_RATE (400), READ_BASELINE_RATE (20), READ_MAX_VUS (500), GROUP_BY,
# SPIKE_WINDOW, *_SECONDS.

perf_mixed_spike_active_users() {
  perf_mixed_spike_cell perf/mixed/spike/active-users/journal.jsonl \
    active-users "${GROUP_BY:-day}"
}
