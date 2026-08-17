#!/bin/bash

# The write cells, declared once and sourced by every action that runs more than
# one of them. Keeping the list here rather than in each action is what stops a
# new cell from being wired into one entry point and quietly missed by another.
#
# Split by workload, the same axis the directories use: an action that runs one
# workload takes that array, and WRITE_TESTS is their concatenation so the
# whole-path and whole-suite actions never enumerate cells themselves.

source perf/write/load/measure-cell.sh
source perf/write/load/single/measure.sh
source perf/write/spike/measure-cell.sh
source perf/write/spike/single/measure.sh

WRITE_LOAD_TESTS=(
  "write load single:perf_write_load_single"
)

WRITE_SPIKE_TESTS=(
  "write spike single:perf_write_spike_single"
)

WRITE_TESTS=("${WRITE_LOAD_TESTS[@]}" "${WRITE_SPIKE_TESTS[@]}")
