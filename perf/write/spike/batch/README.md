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

## Its numbers are predictions until its load journal has rows

`SPIKE_RATE` is 2000, `BASELINE_RATE` is 50 and `baseline_max_p95_ms` is 500. None
of the three is measured. The rules they stand in for are the sibling cell's:

- surge at ~2x the request rate this shape sustains under load, which for a batch
  is a smaller rate than the single-event cell's 8000 delivering far more events
  per second, since each request carries a hundred of them;
- hold the baseline at roughly a tenth of that sustained rate — 500 against ~4,100
  in the single-event cell;
- set the healthy-baseline ceiling an order of magnitude above the p95 this shape
  actually answers a request in, which is not ~2ms: a request here carries a
  hundred inserts.

**The baseline needs re-deriving as much as the surge does.** A surge the app does
not notice makes a cell that always passes; a baseline the app does not notice is
the same fault pointing the other way. Every other clause of the verdict is
measured against the baseline phase, and `recovery_p95 ≤ baseline_p95 × 5` between
two numbers both sitting at the floor is a verdict decided by one scheduler
hiccup — which is already why the suite reports no spread over
[`baseline_p95_ms`](../../../README.md#the-measured-floor). Whether 50 req/s
clears that depends on the ceiling this shape turns out to have: at ~600 req/s
sustained it is ~8%, close to the single-event cell's ~12%; at ~2,500 it is 2% and
the baseline is measuring idle.

All three get replaced by figures read off [`load/batch/`](../../load/batch)'s
journal once it has rows, the way the read spike cells' rates were. Until then a
red verdict from this cell is as likely to be a wrong constant as a real finding,
and the fix is the derivation, never a wider constant.

The rate started at 600 and was raised before the cell had ever been journalled: a
plumbing check on an empty table, at a quarter of the batch size and a 5s window,
absorbed 600 req/s with nothing dropped and p95 at 3ms. That is not a measurement
of anything — but a surge the app does not notice makes a cell that always passes,
which is worse than a wrong number, because it looks like a result.

## It gates, like its sibling

The write path recovers, so `recovered: false` here is news. But the precondition
is the clause to watch first: a `NO VALID BASELINE` verdict means the baseline
ceiling above is wrong for a hundred-event request, which is a fact about this
file rather than about the app.

## What a healthy run looks like

The same shape as the single-event cell: latency climbing in the spike phase, a
large `spike_dropped` count as the client caps in-flight requests, ~0% failed, then
recovery p95 back at the baseline's. Each request holds a pooled connection for a
hundred inserts rather than one, so the pool saturates at a far lower request rate
— the queue should build sooner in requests and at a similar point in events.
