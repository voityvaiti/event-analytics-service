#!/bin/bash

# The mixed cells, declared once and sourced by every action that runs more
# than one of them, for the reason perf/read/tests.sh gives. Mixed cells run
# both paths at once, so they belong to neither of the other two lists.

source perf/mixed/spike/measure-cell.sh
source perf/mixed/spike/active-users/measure.sh

MIXED_SPIKE_TESTS=(
  "mixed spike active-users:perf_mixed_spike_active_users"
)

MIXED_TESTS=("${MIXED_SPIKE_TESTS[@]}")
