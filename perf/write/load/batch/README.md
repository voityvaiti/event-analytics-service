# Write load — `batch`

`POST /api/v1/events/batch`, 100 events per request. See [shared load methodology](..) for configuration and journal fields.

## The number this cell exists to produce

Compare batches with [single events](../single) using `events_per_sec`, the metric used for this cell's spread. Rows also include `throughput_rps`, `batch_size`, and `events`; single-event rows need no duplicate event counters.

Batching shares request parsing, round trips, and commits across 100 events. The first three rounds at 20M rows measured **125,290 events/s** (1,253 req/s), versus 3,777 events/s for single events on the same rig and day: **33x**.

## A regime of its own

The first three rounds had **0.79% peak-to-peak spread and 0.40% CV**, much less than the single-event ~6% floor. Batching reduces the proportion of request overhead that jitters. This is an initial estimate; more rounds can widen the range. See [noise by workload](../../../README.md#the-measured-floor).

## Why the window is 30s, not 60s

At 1,253 req/s, 30s adds **3.77M rows (18.9% of the corpus)**; 60s would add ~38%. Single-event runs add ~250k rows (1.2%) in 60s. Later requests therefore see a larger table, an effect included in each measurement. Use `events`, `start_rows`, and `duration` to compare the amount of growth.

## The async rows: accepting outruns persisting

Three rounds on `a0d9a74` (2026-11-01): **225,866 events/s** accepted (221,190–227,951, 3.0% peak-to-peak), 1.8x the sync rows' ~124,000, p95 3.2–3.5 ms per request.

Each 30 s window accepted 6.6M–6.8M events (a third of the corpus), and the consumer needed another 150–162 s to persist them. Window and drain together, it persisted ~35,700–37,600 events/s (`events ÷ (30 s + drain_seconds)`), under a third of what the sync path's ten concurrent inserts managed. The likely reason, not measured, is that one consumer thread commits one transaction at a time. A batch intake sustained above that rate grows lag for as long as it lasts. Stage 3 requires the single-event floor, not this; the figure is recorded for whoever sizes the consumer.

## What to watch

`p95_ms` is per *request* here, so 7.78ms sits above the single-event cell's 4.2ms: it covers a hundred inserts and a hundred rows of JSON parsing. Divided by `batch_size` it is **0.078ms per event**, a factor of 54 below the single-event figure, and that is the comparable one.

`VUS=10` is held at the sibling's value so the two cells differ in one thing only — but it is not this shape's ceiling. The [spike cell](../../spike/batch) reaches ~143,600 events/s during its surge, 15% above this cell's steady figure, because a surge runs with far more concurrency than ten VUs and keeps the pool queue full. What this cell measures is throughput at the pool's width, which is the number the single-event series has always reported; the higher figure belongs to the surge and is journalled there.
