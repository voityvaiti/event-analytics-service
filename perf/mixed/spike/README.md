# Mixed spike — ingest while a read surge holds the pool

Measures steady ingestion during a read surge to establish a baseline for pool isolation or async ingestion. Cells share [`ingest-under-read-spike.js`](./ingest-under-read-spike.js) and [`measure-cell.sh`](./measure-cell.sh).

## How a run is applied

- **Reads** repeat [read/spike](../../read/spike)'s surge against the seeded corpus: 20 s at 20 req/s, 30 s at the cell's surge rate, 30 s back at 20, with that cell's budget of 500 VUs.
- **Writes** are one open-model stream of single events at `WRITE_RATE` across all three phases, into the write tenant the harness deletes afterwards. A request counts in the phase it started in.
- **Lag** is sampled by the harness once a second from `persistence.consumer.lag`; each run's curve goes to `lag.jsonl`, as in [write steady](../../write/steady).

Separate tenants keep writes from changing read results; both streams share the app, ten connections, CPU, and disk. Run with observability enabled (`scrape: on`) to correlate waits with pool metrics.

`WRITE_RATE` stays at 1,000 req/s across the series, about a quarter of measured single-event capacity. This keeps baseline healthy and provides Stage 3's comparison rate.

## Reading a row

Fields are `<phase>_write_*` and `<phase>_read_*`, for `baseline`, `spike` and `recovery`.

- **`<phase>_write_unaccepted_share`** is the headline: the share of the writes the schedule asked for that did not come back 202 within the write deadline (`write_timeout_seconds`).
- The rest of each phase's writes say how they ended: `timeouts` (not answered within the deadline, so the producer gave up, although the server may still have written it), `server_errors`, `rejected` (any other status, which is a harness fault rather than the app's), `transport_errors`, and `dropped` (never sent, which the budget below leaves only for k6 itself falling behind).
- **`connection_timeouts`** is how often the pool failed to hand out a connection within Hikari's 30 s during the run. The write path answers that with the same 500 as any other failure, so this is what says whether `server_errors` waited out the pool.
- **Write latency is of accepted writes only**, so it never exceeds the deadline.
- **`<phase>_lag_median`, `<phase>_lag_max`** and their sample counts, against `lag_bound` (`WRITE_RATE` records, one second of intake). `recovery_lag_settled_seconds` is how far into recovery lag fell under the bound for good; empty if it never did.

## The deadline

Each write has a `write_timeout_seconds` deadline. Without it, waiting requests exhaust k6's VUs: a 6,000-VU smoke run reported 18% unaccepted writes, all client-side drops rather than server refusals.

Preallocate `1.1 × WRITE_RATE × deadline` VUs so the client can maintain the rate until requests time out. Allocating VUs during the run can itself drop work.

The 5s default is a rig constraint. A 10s deadline (as in OkHttp) at 1,000 req/s requires 11,000 VUs, exceeding Tomcat's 8,192 connections; a smoke run hit the limit eight seconds into baseline. At 5s, clients use at most 5,500 write and 500 read connections, though abandoned server requests can accumulate.

Keep the deadline fixed, including for Stage 3: it changes the unaccepted share. Compare phases against the same row's baseline; standalone read-spike journals have no competing write traffic.

## What the first rows measured

Three rounds on `ab1f425` (2026-10-04), 20.1M rows, LiveAgent stopped and the stack scraping. Ranges are across the rounds. The pool figures come from Prometheus, which keeps 15 days, so they are kept in [`pool-metrics.json`](./pool-metrics.json) with the queries that produced them.

| Phase | Writes not accepted within 5 s | Wait for a connection, mean | Read p95 |
|---|---|---|---|
| baseline | 0% | ~0 | 115–117 ms |
| spike | 93.7–94.1% | 7.3–7.7 s (max 16.9 s) | 8.5–8.6 s |
| recovery | 19.8–21.1% | 3.1–3.3 s | 7.6–7.8 s |

- **A read surge all but stops ingest.** Of the 30,000 writes scheduled during the surge, 1,775–1,877 were accepted within the deadline. The wait is the server's own figure, the time Hikari took to hand out a connection, so it does not depend on the deadline: a client patient for 10 s would still have lost a large share.
- **Nothing failed; everything waited.** No 5xx and no pool timeout. Every unaccepted write timed out, apart from 23–33 per round that could not connect at all.
- **The pool is what holds them.** All ten connections were busy for the whole surge, with up to 8,081 requests queued for one, while the app used under one core and the host about 60%, which by elimination is mostly Postgres's ten backends sorting for `active-users`.
- **The surge outlasts itself.** In the 30 s after it ended, a fifth of the writes still missed the deadline and reads ran at about 65x their baseline.

One reading is an inference rather than a measurement: **a request whose client gave up keeps its place in the queue.** Nothing cancels it, so it waits for a connection and runs its INSERT for nobody. The queue reached 8,081 while the clients held at most 6,000 requests, and the pool handed out about 23,000 connections during the surge against roughly 1,800 writes accepted and 2,350 reads served, so most of the surge's write work went to clients that had already left. Those requests are also why the server ran into Tomcat's 8,192 connections when the run opens no more than 6,000: the connection failures above, and the same 8,081 in every round, match that ceiling.

This is the baseline Stage 3 is measured against: the same cell with `ingest_path: async` has to accept the surge's writes as it accepts the baseline's.

## What the async rows measured

Three rounds on `a0d9a74` (2026-11-01), same corpus, rig and scrape; all three `survived`. Sync ranges in brackets; pool figures for both series are in [`pool-metrics.json`](./pool-metrics.json).

| Phase | Writes not accepted within 5 s | Accepted write p99 | Lag, median / max | Wait for a connection, mean | Read p95 |
|---|---|---|---|---|---|
| baseline | 0% | 5.9–7.4 ms | 5 / 6 | ~0 | 137–143 ms (115–117 ms) |
| spike | 0% (93.7–94.1%) | 6.9–7.6 ms | 14.4k–14.6k / 27.6k–28.4k | 5.9 s (7.3–7.7 s) | 7.2–7.3 s (8.5–8.6 s) |
| recovery | 0% (19.8–21.1%) | 5.8–5.9 ms | 3–6 / 35.9k–36.4k | 0.8–0.9 s (3.1–3.3 s) | 6.0–6.1 s (7.6–7.8 s) |

- **The surge no longer reaches ingest.** Every write k6 sent was accepted within milliseconds in every phase; one round's baseline had one write k6 never sent.
- **The wait moved to the consumer.** During the surge it inserted one poll of 500 records about once every 7 s (the curves' inserted counter), so lag grew at ~93% of intake for the whole surge. It kept growing until 8.5 s into recovery, while the queue for a connection ran down from 491 (empty by 12 s); the consumer then cleared ~36,000 records in under 2 s and stayed under 1,000 from 10.5 s on, in every round. Only the surge's length bounds this lag: extrapolated, a surge of minutes would hold back ~55,000 events a minute.
- **The queue for a connection is now the reads'.** At most 491 requests waited, against 8,081 on sync: the reads' 500 VUs, as writes no longer queue for a connection.
- **Reads gained less.** Surge and recovery p95 fell by 15–21%, but the baseline's rose by ~20%, from 115–117 to 137–143 ms, while the event-counts rig check before the pass stayed within 1.2% of its September rows. Not investigated; the consumer's batched inserts and the broker are what the baseline added.

## Verdict

Rows since the lag fields carry Stage 3's verdict. `survived` when:

- every phase has valid lag samples for 80% of its seconds, otherwise `TOO FEW SAMPLES`;
- under 0.1% of writes went unaccepted in every phase, otherwise `WRITES NOT ACCEPTED`;
- the baseline's median lag is under the bound, otherwise `BASELINE LAGGED`;
- lag is back under the bound for good before recovery ends, otherwise `LAG NOT RECOVERED`. The surge holds the pool the consumer writes through, so lag rising during it is expected.

The sync rows above predate the verdict. The cell stays out of the CI comparison for the reason every spike cell does.
