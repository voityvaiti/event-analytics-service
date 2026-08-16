#!/bin/bash

# The read cells, declared once and sourced by every action that runs more than
# one of them. Keeping the list here rather than in each action is what stops a
# new cell from being wired into one entry point and quietly missed by another.
#
# Split by workload, the same axis the directories use: an action that runs one
# workload takes that array, and READ_TESTS is their concatenation so the
# whole-path and whole-suite actions never enumerate cells themselves.

source perf/read/load/measure-cell.sh
source perf/read/load/event-counts/measure.sh
source perf/read/load/active-users/measure.sh
source perf/read/load/top-pages/measure.sh
source perf/read/spike/measure-cell.sh
source perf/read/spike/event-counts/measure.sh
source perf/read/spike/active-users/measure.sh
source perf/read/spike/top-pages/measure.sh

READ_LOAD_TESTS=(
  "read load event-counts:perf_read_load_event_counts"
  "read load active-users:perf_read_load_active_users"
  "read load top-pages:perf_read_load_top_pages"
)

READ_SPIKE_TESTS=(
  "read spike event-counts:perf_read_spike_event_counts"
  "read spike active-users:perf_read_spike_active_users"
  "read spike top-pages:perf_read_spike_top_pages"
)

READ_TESTS=("${READ_LOAD_TESTS[@]}" "${READ_SPIKE_TESTS[@]}")
