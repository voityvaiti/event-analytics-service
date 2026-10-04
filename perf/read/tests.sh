#!/bin/bash

# Shared read cell lists, grouped by workload. Multi-cell actions consume these arrays;
# READ_TESTS combines them so new cells reach every entry point.

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
