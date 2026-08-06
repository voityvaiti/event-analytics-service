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

That condition now looks met, and this section is the weaker for it. The index
experiment measured per-query cost differing by 4x to 38x across the endpoints,
and the ceiling here is roughly pool size over query latency — so they very
likely shed differently, and `event-counts` may recover at a rate `active-users`
cannot. Sibling cells are the fix; see
[`notes/perf-read-and-index-experiment.md`](../../../../notes/perf-read-and-index-experiment.md).

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
journals — a different question, measured differently. The gate is recovery: the
surge itself is allowed to shed, and `spike_dropped` records how much k6 could
not even hand over. What must hold is `recovery_failed_rate` near zero with
`recovery_p95_ms` back near `baseline_p95_ms`.

`index_scans` and `seq_scans` are carried here too, for the same reason as in
the load cells: without the index a surge would be answered by sequential
scans, and the row should say so rather than leave a collapse unexplained.
