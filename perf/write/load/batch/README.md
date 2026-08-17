# Write load — `batch`

`POST /api/v1/events/batch`, 100 events per request. What a row means, and why the
config stamped into it is what makes it mean anything, is [one level up](..); this
page is the request shape.

## The number this cell exists to produce

Events per second. A batch request and a single-event request have request rates
that differ by the batch size, so `throughput_rps` cannot be read across the two
series — `events_per_sec` is the field that can, and it is what this cell's spread
is taken over. Both are in the row, along with `batch_size`, so the arithmetic is
checkable rather than implied. Only this cell's rows carry those two: for one event
per request they would be copies of `requests` and `throughput_rps`.

The reason the endpoint exists is that a batch amortises per-request overhead: one
round trip, one parse, one commit for a hundred inserts instead of a hundred of
each. A result in the same range as the [`single/`](../single) cell's would have
meant it bought nothing; the first three rounds at 20M rows measure **125,290
events/s** against that cell's 3,777 on the same day and rig — **33x** — at 1,253
requests per second.

## A regime of its own

The suite's write noise floor — ~6% peak-to-peak — was measured on the
single-event cell, where a request is one insert. This cell's request is a hundred,
and [the floor belongs to the amount of work, not to the machine](../../../README.md#the-measured-floor),
so it had to be established here rather than inherited. Its first three rounds hold
to **0.79% peak-to-peak, 0.40% coefficient of variation** — an order of magnitude
tighter, because proportionally less of each request is the per-request overhead
that jitters. Three rounds is a first reading, not a range: peak-to-peak only grows
as rounds are added.

## Why the window is 30s, not 60s

The single-event cell adds ~250k rows to the 20M corpus over its 60s window, about
1.2%. This one writes 100 rows per request, and the measured rate makes that
concrete: 1,253 req/s over 30s is **3.77M rows, 18.9% of the corpus**, added inside
the measured window. A 60s window would put it near 38%.

That is the cost of the shape, not a flaw to hide — but it does mean the last
requests of a round hit a table almost a fifth larger than the first did. Whatever
that costs is inside the 0.79% the rounds agree to, so it is not what the number
turns on. The rows say how bounded it was without anyone having to trust this
paragraph: `events` against `start_rows` is the fraction the run added, and
`duration` is journalled beside it, so the two cells' windows differ visibly and a
longer one is a knob rather than a surprise.

## What to watch

`p95_ms` is per *request* here, so 7.78ms sits above the single-event cell's 4.2ms:
it covers a hundred inserts and a hundred rows of JSON parsing. Divided by
`batch_size` it is **0.078ms per event**, a factor of 54 below the single-event
figure, and that is the comparable one.

`VUS=10` is held at the sibling's value so the two cells differ in one thing only —
but it is not this shape's ceiling. The [spike cell](../../spike/batch) reaches
~143,600 events/s during its surge, 15% above this cell's steady figure, because a
surge runs with far more concurrency than ten VUs and keeps the pool queue full.
What this cell measures is throughput at the pool's width, which is the number the
single-event series has always reported; the higher figure belongs to the surge and
is journalled there.
