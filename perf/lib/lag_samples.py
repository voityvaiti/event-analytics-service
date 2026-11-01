"""Reads what start_lag_sampler wrote: one "<epoch_ms> <lag> <inserted>" line a second.

A sample whose read failed carries None, never 0, so an unanswered broker cannot pass
for an empty backlog. Cells import this from their journalling heredocs.
"""

import statistics
from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class Sample:
    at_millis: int
    lag: float | None
    inserted: float | None


def _number(text):
    try:
        value = float(text)
    except ValueError:
        return None
    return None if value != value else value


def load(path):
    samples = []
    with open(path) as samples_file:
        for line in samples_file:
            fields = line.split()
            if len(fields) == 3:
                samples.append(Sample(int(fields[0]), _number(fields[1]), _number(fields[2])))
    return samples


def epoch_millis(iso):
    return round(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000)


def between(samples, start_millis, end_millis):
    return [sample for sample in samples if start_millis <= sample.at_millis < end_millis]


def describe(samples):
    """Valid and failed sample counts, median and maximum lag of a stretch of samples."""
    lags = [sample.lag for sample in samples if sample.lag is not None]
    return {
        "samples": len(lags),
        "failed_samples": len(samples) - len(lags),
        "median": statistics.median(lags) if lags else None,
        "max": max(lags) if lags else None,
    }


def last_lag(samples, before_millis):
    valid = [s for s in samples if s.at_millis < before_millis and s.lag is not None]
    return valid[-1].lag if valid else None


def settled_below(samples, start_millis, end_millis, bound):
    """When lag fell under bound for good within the window, or None if it ended above it.

    For good means every later valid sample in the window stayed under the bound too, so a
    dip between two peaks does not count as having drained.
    """
    settled = None
    for sample in between(samples, start_millis, end_millis):
        if sample.lag is None:
            continue
        if sample.lag < bound:
            settled = sample.at_millis if settled is None else settled
        else:
            settled = None
    return settled


def persisted_per_second(samples, start_millis, end_millis):
    """Events the consumer inserted per second between the window's first and last counts."""
    counted = [s for s in between(samples, start_millis, end_millis) if s.inserted is not None]
    if len(counted) < 2 or counted[-1].at_millis == counted[0].at_millis:
        return None
    inserted = counted[-1].inserted - counted[0].inserted
    return inserted * 1000 / (counted[-1].at_millis - counted[0].at_millis)


def curve(samples, origin_millis):
    """[seconds since origin, lag, inserted] per sample, for the cell's lag.jsonl."""

    def whole(value):
        return None if value is None else round(value)

    return [
        [round((s.at_millis - origin_millis) / 1000, 1), whole(s.lag), whole(s.inserted)]
        for s in samples
    ]
