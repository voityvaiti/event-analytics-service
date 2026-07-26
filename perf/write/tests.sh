#!/bin/bash

# The write cells, declared once and sourced by every action that runs more than
# one of them. Keeping the list here rather than in each action is what stops a
# new cell from being wired into one entry point and quietly missed by another.

source perf/write/load/measure.sh
source perf/write/spike/measure.sh

WRITE_TESTS=(
  "write load:perf_load"
  "write spike:perf_spike"
)
