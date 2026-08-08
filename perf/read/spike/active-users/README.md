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
that precondition the ratio compares broken against broken and passes: the index
experiment measured 28s recovery against a 15196ms baseline being called
recovered, while a healthy 123ms baseline was not.

The verdict itself is **not** a column. It is derived from the three fields
above, so storing it would put a second source of truth in the series — one that
keeps asserting whichever rule was current when the row was written. Rows from
before the baseline precondition existed carry exactly that stale `true`.

This cell does not gate. Nothing in the app cuts off a long-running read yet, so
a surge past the pool's ceiling always leaves a tail and the verdict would be red
on every run — a permanently red test stops being read. It is journalled and
printed instead, and becomes a gate once a statement timeout or queue limit lands.
The write spike does gate: it genuinely recovers.

`index_scans` and `seq_scans` are carried here too, for the same reason as in
the load cells: without the index a surge would be answered by sequential
scans, and the row should say so rather than leave a collapse unexplained.
