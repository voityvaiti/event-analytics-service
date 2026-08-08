# Read spike — surviving a burst of dashboard traffic

Steps the request rate far above what the connection pool can serve, holds it,
then drops back and checks the read path serves cleanly again. The k6 scenario
lives in [`../stats-spike.js`](../stats-spike.js).

## Why `active-users` is the only endpoint with a spike cell

A spike measures what happens when requests outrun the pool — queueing,
shedding, and whether the queue drains once the surge passes. That is a property
of the pool and the queue, not of a query shape, and a cell per endpoint would
multiply the slowest test in the suite for the same answer. `active-users` is the
one that gets it because it is the heaviest read, so its ceiling is the lowest
and the surge reaches it first. `ENDPOINT` repoints this cell at another query
without a new directory, for a one-off comparison; a second endpoint earns a
cell of its own beside this one only if its shape turns out to shed differently.

That condition now looks met. The [index experiment](../../../index-experiment.md)
measured per-query cost spreading 7x across the read cells at 2M and 11x at 20M,
and the ceiling here is pool size over query latency — so they cannot shed at
the same rate, and `event-counts` may drain where `active-users` cannot. The pinned
window is the same argument in another direction: for this cell's own query the
index is worth 62x at 1h and 0.73x at 30d, so one surge at 1d is a single point on
a curve. Sibling cells are the fix.

## Why the rates are two orders of magnitude below the write spike

The write spike steps to 8000 req/s because an insert costs ~2ms. A read costs
tens to hundreds of milliseconds, so the read ceiling is roughly pool size
divided by query latency — on the reference rig, tens of requests per second,
not thousands. `SPIKE_RATE` has to clear that ceiling to be a surge at all, and
400 against a baseline of 20 does.

## Why the window size is pinned here

The load cells deliberately mix window sizes, because that is what real
traffic looks like. This one does not: mixing them would change how much work
each request costs at the same time as the request rate, and a spike exists to
change one of those. The position still moves, so the run is not answering from
one cached stretch.

## Reading a row

`journal.jsonl` is this test's own series, written only by the
`PERF - Read Spike` task, never by hand, and never merged with the load
journals — a different question, measured differently. The run has three phases:
`baseline` establishes what a calm rate costs, `spike` is the surge, and
`recovery` returns to the calm rate to ask whether the surge is over.

The question is recovery. The surge itself is allowed to shed, and
`spike_dropped` records how much k6 could not even hand over. What must hold is
`recovery_failed_rate` near zero with `recovery_p95_ms` back near
`baseline_p95_ms` — **and** `baseline_p95_ms` under `BASELINE_MAX_P95_MS`
(1000ms), because a baseline that is itself saturated is not a reference. Without
that precondition the ratio compares broken against broken and passes: in the
index experiment's no-index arm at the default corpus, a 29.6s recovery sat only
1.8x above a baseline that had itself collapsed to 16.7s, which clears a 5x margin
comfortably — while the indexed arm's 6.3s recovery against a healthy 124ms
baseline does not.

`recovered` carries the verdict, so a row answers its own question instead of
leaving a reader to apply the rule by hand. It is derived from the three fields
above, and which version of the rule produced it is answered by the row's
`commit` — the same stamp every other field is read against. Rows written before
the baseline precondition existed carry whatever the older rule concluded; their
`commit` is what makes that difference legible rather than confusing.

This cell does not gate. Nothing in the app cuts off a long-running read yet, so
a surge past the pool's ceiling always leaves a tail and the verdict would be red
on every run — a permanently red test stops being read. It is journalled and
printed instead, and becomes a gate once a statement timeout or queue limit lands.
The write spike does gate: it genuinely recovers.

The tail is a function of what a query costs, not a property of the surge. At a
tenth of the corpus the index experiment's indexed arm absorbed the whole 400
req/s step with recovery back at its 13ms baseline — a clean `recovered` from this
very cell. At the default corpus it does not, with or without the index, which is
what makes the protection above the thing that would change this verdict.

`index_scans` and `seq_scans` are carried here too, for the same reason as in
the load cells: without the index a surge would be answered by sequential
scans, and the row should say so rather than leave a collapse unexplained.
