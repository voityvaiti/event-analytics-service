# Write load — `batch`

`POST /api/v1/events/batch`, 100 events per request. What a row means, and why the
config stamped into it is what makes it mean anything, is [one level up](..); this
page is the request shape.

## The number this cell exists to produce

Events per second. A batch request and a single-event request have request rates
that differ by the batch size, so `throughput_rps` cannot be read across the two
series — `events_per_sec` is the field that can, and it is what this cell's spread
is taken over. Both are in the row, along with `batch_size`, so the arithmetic is
checkable rather than implied.

The reason the endpoint exists is that a batch amortises per-request overhead: one
round trip, one parse, one commit for a hundred inserts instead of a hundred of
each. A result in the same range as the ~4,100 events/s the
[`single/`](../single) cell holds would mean it bought nothing.

## A regime of its own

The suite's write noise floor — ~6% peak-to-peak — was measured on the
single-event cell, where a request is one insert. This cell's request is a hundred,
and [the floor belongs to the amount of work, not to the machine](../../../README.md#the-measured-floor):
it has to be established here over its own rounds before any delta on this series
is called real. Until this journal has three rounds of the same commit, it has no
floor and no delta on it is supportable.

## Why the window is 30s, not 60s

The single-event cell adds ~250k rows to the 20M corpus over its 60s window, about
1.2%. This one writes 100 rows per request, so the arithmetic is the request rate
times 100 times the window: at a few hundred requests per second, 30s already adds
1–3M rows, 5–15% of the corpus. A 60s window would double that — enough to move
the table the run is measuring against, and enough to make the delete-and-vacuum
that restores the corpus a slow step of its own.

Thirty seconds keeps it bounded, and the rows themselves say how bounded: `events`
against `start_rows` is the fraction this run added, in every row. `duration` is
journalled too, so the two cells' windows differ visibly and a longer one is a
knob rather than a surprise.

## What to watch

`p95_ms` is per *request* here, so it is expected to be far above the
single-event cell's ~2ms: it covers a hundred inserts and a hundred rows of JSON
parsing. Divided by `batch_size` it becomes the per-event cost, which is the
comparable figure and the one the endpoint is judged on.
