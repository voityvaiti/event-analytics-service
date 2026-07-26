#!/bin/bash

# The read cells, declared once and sourced by every action that runs more than
# one of them. Keeping the list here rather than in each action is what stops a
# new cell from being wired into one entry point and quietly missed by another.

source perf/read/measure-read.sh
source perf/read/event-counts/measure.sh
source perf/read/active-users/measure.sh
source perf/read/top-pages/measure.sh
source perf/read/spike/measure.sh

READ_TESTS=(
  "read event-counts:perf_read_event_counts"
  "read active-users:perf_read_active_users"
  "read top-pages:perf_read_top_pages"
  "read spike:perf_read_spike"
)
