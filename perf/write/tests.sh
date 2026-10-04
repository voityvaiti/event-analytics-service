#!/bin/bash

# Shared write cell lists, grouped by workload. Multi-cell actions consume these arrays;
# WRITE_TESTS combines them so new cells reach every entry point.

source perf/write/load/measure-cell.sh
source perf/write/load/single/measure.sh
source perf/write/load/batch/measure.sh
source perf/write/spike/measure-cell.sh
source perf/write/spike/single/measure.sh
source perf/write/spike/batch/measure.sh

WRITE_LOAD_TESTS=(
  "write load single:perf_write_load_single"
  "write load batch:perf_write_load_batch"
)

WRITE_SPIKE_TESTS=(
  "write spike single:perf_write_spike_single"
  "write spike batch:perf_write_spike_batch"
)

WRITE_TESTS=("${WRITE_LOAD_TESTS[@]}" "${WRITE_SPIKE_TESTS[@]}")
