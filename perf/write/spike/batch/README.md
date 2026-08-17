# Write spike — `batch`

Surges `POST /api/v1/events/batch` at 100 events per request. How a spike is
applied, judged and journalled is the same for every cell here and is described
[one level up](..); this page is the request shape.

## The rate surges, the batch size does not

Both would raise events per second, and only one of them is a surge in the sense
the single-event cell measures. Holding the batch size fixed keeps the unit of work
constant across all three phases, so what the row reports is how the path behaves
when *more requests* arrive — not how it behaves when each request suddenly asks
for more. A cell that moved both would be measuring two changes at once, and its
`spike_p95_ms` could not be read against the baseline's.

## Every constant comes from this shape's own load journal

[`load/batch/`](../../load/batch) measures 1252 req/s sustained at p95 7.78ms —
median of three rounds at 20M rows — and all three of this cell's constants are
that number put through the multiples the single-event cell uses of its own:

| Constant | Here | Rule | Sibling |
|---|---|---|---|
| `SPIKE_RATE` | 2500 | ~2x sustained | 8000 = 2.1x of 3756 |
| `BASELINE_RATE` | 150 | ~12% of sustained | 500 = 13% of 3756 |
| `baseline_max_p95_ms` | 100 | ~13x the cell's load p95 | 50 = 12.7x of 3.93 |

The rates are two orders of magnitude apart from the sibling's in requests and
within a factor of two in events, which is the point: the shapes saturate the same
pool at very different request rates.

**The baseline is derived as carefully as the surge, and for the mirror-image
reason.** A surge the app does not notice makes a cell that always passes; a
baseline the app does not notice makes a verdict that means nothing, because every
other clause is measured against it. `recovery_p95 ≤ baseline_p95 × 5` between two
numbers both sitting at the floor is decided by one scheduler hiccup — which is
already why the suite reports no spread over spike metrics at all.

The surge started life at 600, written down before there was anything to derive it
from, and a plumbing check absorbed that without dropping a single request. That is
the failure mode this table exists to prevent: a wrong constant that looks like a
result.

## It gates, like its sibling

The write path recovers, so `recovered: false` here is news. But the precondition
is the clause to watch first: a `NO VALID BASELINE` verdict means the baseline
ceiling above is wrong for a hundred-event request, which is a fact about this
file rather than about the app.

## What a healthy run looks like

The same shape as the single-event cell, and the first three rounds are it: baseline
150 req/s answered at p95 4.6–5.1ms, the surge offering 2,500 and carrying
1,431–1,450 req/s of it (143,100–145,000 events/s), ~32,000 dropped at the client,
0% failed, p99 around 1.37s, then recovery p95 back at 5.2–6.9ms. All three
recovered.

Two figures in there are worth reading twice. The surge carries **more** events per
second than the [load cell](../../load/batch) sustains — 143,600 against 125,290 —
because a surge runs with hundreds of VUs where that cell runs ten, so the pool
queue stays full; steady-state throughput at the pool's width is a different
question from what the path absorbs under pressure. And p99 climbs to 1.37s where
the single-event cell reaches 0.5s: each queued request here is a hundred inserts
of work, so waiting behind one costs proportionally more.
