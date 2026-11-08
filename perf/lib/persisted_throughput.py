"""Persisted throughput of a write run: the events k6 saw accepted, divided by the time
from the run's start until the consumer committed the last of them.

That end is the moment lag first read 0 after the run, or the run's own end when lag
already read 0 by then. Timing the drain from the run's end rather than from k6's exit
keeps the summary's aggregation out of it.

Usage: persisted_throughput.py <summary.json> [persisted_at_ms] adds
persisted_events_per_sec to the summary in place, as the CI comparison does.
"""

import json
import sys

from lag_samples import epoch_millis


def per_second(summary, persisted_at_ms=None):
    end = int(persisted_at_ms) if persisted_at_ms else epoch_millis(summary["finished_at"])
    return summary["accepted_events"] * 1000 / (end - epoch_millis(summary["started_at"]))


if __name__ == "__main__":
    path = sys.argv[1]
    persisted_at = sys.argv[2] if len(sys.argv) > 2 else None
    with open(path) as f:
        summary = json.load(f)
    summary["persisted_events_per_sec"] = per_second(summary, persisted_at)
    with open(path, "w") as f:
        json.dump(summary, f)
