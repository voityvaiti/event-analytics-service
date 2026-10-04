# Write spike — `batch`

Surges `POST /api/v1/events/batch` at 100 events per request. See [shared spike methodology](..) for execution, verdicts, and journal fields.

## The rate surges, the batch size does not

Keep batch size fixed at 100 while increasing request rate. Changing both would alter work per request and make spike latency incomparable to baseline.

## Every constant comes from this shape's own load journal

[`load/batch/`](../../load/batch) measured 1,252 req/s at p95 7.78 ms (three-round medians, 20M rows). Constants use the same multiples as the single-event cell:

| Constant | Here | Rule | Sibling |
|---|---|---|---|
| `SPIKE_RATE` | 2500 | ~2x sustained | 8000 = 2.1x of 3756 |
| `BASELINE_RATE` | 150 | ~12% of sustained | 500 = 13% of 3756 |
| `baseline_max_p95_ms` | 100 | ~13x the cell's load p95 | 50 = 12.7x of 3.93 |

The shapes reach pool saturation at different request rates. Baseline must be healthy but measurable; values near the timing floor make the 5x recovery ratio sensitive to scheduler jitter.

An initial, uncalibrated 600 req/s surge dropped no requests, demonstrating why rates must come from the load journal.

## It gates, like its sibling

This cell gates on `recovered`, like single-event ingestion. A `NO VALID BASELINE` verdict means baseline was unhealthy; check the configured bound and environment before interpreting recovery.

## What a healthy run looks like

The same shape as the single-event cell, and the first three rounds are it: baseline 150 req/s answered at p95 4.6–5.1ms, the surge offering 2,500 and carrying 1,431–1,450 req/s of it (143,100–145,000 events/s), ~32,000 dropped at the client, 0% failed, p99 around 1.37s, then recovery p95 back at 5.2–6.9ms. All three recovered.

Two figures in there are worth reading twice. The surge carries **more** events per second than the [load cell](../../load/batch) sustains — 143,600 against 125,290 — because a surge runs with hundreds of VUs where that cell runs ten, so the pool queue stays full; steady-state throughput at the pool's width is a different question from what the path absorbs under pressure. And p99 climbs to 1.37s where the single-event cell reaches 0.5s: each queued request here is a hundred inserts of work, so waiting behind one costs proportionally more.
