# Write steady — a fixed offered rate

Offers events at a fixed rate and asks whether the persistence consumer stores them as they arrive. This is Stage 3's throughput criterion: the async path has to persist the sync path's closed-model throughput, ~3,700 events/s, without lag growing. Cells share [`ingest-at-rate.js`](./ingest-at-rate.js) and [`measure-cell.sh`](./measure-cell.sh).

## How a run is applied

- **Open model.** k6 starts `RATE` requests a second however long the answers take. The [load cells](../load) are closed-model: ten VUs that each wait for an answer, so a slower server is offered less and the slowdown hides in the rate. They remain the way to find the maximum.
- **Phases.** `warmup` (30 s) and `measure` (180 s) are one stream, so the consumer enters the measure phase already running at the offered rate. A request counts in the phase it started in; only `measure` is judged.
- **Batches.** `RATE` counts events. With `BATCH_SIZE` above 1, each request carries that many events to the batch endpoint at `RATE / BATCH_SIZE` requests a second; the row adds `batch_size` and `request_rate`. Request counts (`*_scheduled`, `*_sent`, `*_accepted`) count requests, `accepted_events` counts events. No cell runs batches yet.
- **Deadline.** A write is accepted when it returns 202 within `write_timeout_seconds` (1 s). Kafka acknowledges in milliseconds, so the deadline cuts off nothing healthy. k6 preallocates `1.1 × requests/s × deadline` VUs (4,070 at 3,700/s) so it cannot run out of them before the server misses the deadline; a 2 s deadline would reach Tomcat's 8,192 connections.
- **Lag** is sampled by the harness once a second from `persistence.consumer.lag`, each sample with its own time. An unanswered read is a failed sample, never 0.

## Verdict

`kept up` when, in the measure phase:

- every 30 s segment has at least 25 valid samples, otherwise `TOO FEW SAMPLES` and the round is rerun;
- every segment's median lag is under one second of intake, otherwise `FELL BEHIND`. Lag counts records and a batch request is one record, so `lag_bound` is the request rate. A median, because lag saw-tooths between commits (up to 500 records a poll) and short spikes are fluctuation. Held over 180 s, the bound catches a consumer persisting under ~99.4% of the offered rate; 60 s would catch only ~98.3%;
- k6 sent all but under 0.1% of the scheduled requests, otherwise `RATE NOT OFFERED`: k6 ran out of VUs or CPU, so the rate was never really offered and the row says nothing about the server;
- under 0.1% of writes went unaccepted, otherwise `WRITES NOT ACCEPTED`.

As in every write cell, an accepted event missing after the drain fails the cell.

## Reading a row

- `measure_lag_segment_medians` and `measure_lag_segment_samples`: one entry per segment.
- `measure_persisted_events_per_sec`: `events.persisted{outcome=inserted}` across the window. Recorded, not judged, as are `measure_lag_max`, accepted-write `measure_p95_ms`/`measure_p99_ms`, and `drain_seconds`.
- `lag.jsonl` keeps each run's curve: `[seconds since the run started, lag, inserted-events counter]` per sample.
- `measure_saturation`: what each part of the rig did over the measure phase, and `saturation.jsonl` its per-second curve. See [Kafka settings and saturation](../../README.md#kafka-settings-and-saturation).
