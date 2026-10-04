# Write spike — `single`

Surges `POST /api/v1/events`, one event per request. See [shared spike methodology](..) for execution, verdicts, and journal fields.

## 2x its ceiling, not 5x

`SPIKE_RATE` is 8000 against the ~3,800–4,100 req/s this shape sustains in its [load journal](../../load/single) — a little over 2x. The read cells surge at ~5x their own ceilings because their ceilings are tens of requests per second, where a smaller multiple is inside the jitter. Here 2x already offers twice what the pool can absorb and leaves a queue behind it, and the phases run at rates the client can actually issue.

## Recovery gates the run

Both write spike cells fail their actions on `recovered: false`. Read spikes only report verdicts because some remain queued at the default corpus.

`BASELINE_MAX_P95_MS` is 50 against the ~2ms a single insert actually answers in — more than an order of magnitude of headroom, so the precondition marks real saturation of the write path rather than jitter around a small number.

## What a healthy run looks like

High latency in the spike phase (hundreds of ms), a large `spike_dropped` count, ~0% failed, then recovery p95 back at the baseline's. The drops are the client capping in-flight requests at `MAX_VUS` before the queue ever reaches Hikari's 30s connection timeout — the app absorbing the surge by slowing down, which is a pass. Driving actual `500`s takes a `MAX_VUS` high enough that queued requests wait past that timeout.

Rows from 2026-08-17 on carry a corrected `spike_achieved_rps`. The surge absorbs ~3,760 req/s of the 8,000 offered; every earlier row reported that figure diluted across the whole 80s run and journalled ~1,490.
