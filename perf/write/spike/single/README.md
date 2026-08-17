# Write spike — `single`

Surges `POST /api/v1/events`, one event per request. How a spike is applied,
judged and journalled is the same for every cell here and is described [one level
up](..); this page is the request shape.

## 2x its ceiling, not 5x

`SPIKE_RATE` is 8000 against the ~4,100 req/s this shape sustains in its
[load journal](../../load/single) — a little over 2x. The read cells surge at ~5x
their own ceilings because their ceilings are tens of requests per second, where a
smaller multiple is inside the jitter. Here 2x already offers twice what the pool
can absorb and leaves a queue behind it, and the phases run at rates the client
can actually issue.

## The one cell that gates

Every other spike cell in the suite reports and none of them gate, because a read
surge past the pool's ceiling leaves a tail on every run. This one gates: the
write path genuinely recovers, so `recovered: false` is news rather than the
expected outcome, and the action exits non-zero on it.

`BASELINE_MAX_P95_MS` is 50 against the ~2ms a single insert actually answers in —
more than an order of magnitude of headroom, so the precondition marks real
saturation of the write path rather than jitter around a small number.

## What a healthy run looks like

High latency in the spike phase (hundreds of ms), a large `spike_dropped` count,
~0% failed, then recovery p95 back at the baseline's. The drops are the client
capping in-flight requests at `MAX_VUS` before the queue ever reaches Hikari's
30s connection timeout — the app absorbing the surge by slowing down, which is a
pass. Driving actual `500`s takes a `MAX_VUS` high enough that queued requests
wait past that timeout.
