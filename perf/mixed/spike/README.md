# Mixed spike — ingest while a read surge holds the pool

Posts single events at a steady rate while the read path surges far past what the
pool can serve and back down. The read spike sends no writes and the write cells
send no reads, so this is the only cell that shows what a read surge does to
ingest — the measurement `DESIGN.md` names as missing before the pool is split or
a broker takes the writes. One cell per surged endpoint, all running
[`ingest-under-read-spike.js`](./ingest-under-read-spike.js) through
[`measure-cell.sh`](./measure-cell.sh).

## How a run is applied

- **Reads** repeat [read/spike](../../read/spike)'s surge against the seeded
  corpus: 20 s at 20 req/s, 30 s at the cell's surge rate, 30 s back at 20, with
  that cell's budget of 500 VUs.
- **Writes** are one open-model stream of single events at `WRITE_RATE` across
  all three phases, into the write tenant the harness deletes afterwards. A
  request counts in the phase it started in.

The tenants keep the writes from changing what a read counts. Everything else —
the app, its ten pooled connections, the CPU, the disk — the two flows share, so
what ties an effect to the pool is the pool's own timeout counter and the
dashboard's pool panels. A measuring pass therefore runs with the observability
stack up (`scrape: on`).

`WRITE_RATE` defaults to 1,000 req/s, about a quarter of what the single-event
load cell sustains, so the baseline phase is healthy by a wide margin and an
effect during the surge belongs to the reads. Stage 3's target will be stated at
this rate, so it stays fixed across rows.

## Reading a row

Fields are `<phase>_write_*` and `<phase>_read_*`, for `baseline`, `spike` and
`recovery`.

- **`<phase>_write_unaccepted_share`** is the headline: the share of the writes
  the schedule asked for that did not come back 202 within the write deadline
  (`write_timeout_seconds`).
- The rest of each phase's writes say how they ended: `timeouts` (not answered
  within the deadline, so the producer gave up, although the server may still
  have written it), `server_errors`, `rejected` (any other status, which is a
  harness fault rather than the app's), `transport_errors`, and `dropped` (never
  sent, which the budget below leaves only for k6 itself falling behind).
- **`connection_timeouts`** is how often the pool failed to hand out a connection
  within Hikari's 30 s during the run. The write path answers that with the same
  500 as any other failure, so this is what says whether `server_errors` waited
  out the pool.
- **Write latency is of accepted writes only**, so it never exceeds the
  deadline.

## The deadline

Every write is given up on after `write_timeout_seconds`, the way a producer's
client gives up. That is what makes the headline the app's. Without a deadline
a write waits as long as the app makes it, each wait holds one of k6's VUs, and
what a row reports as unaccepted is how many VUs k6 had: a smoke run with a
fixed 6,000 counted 18% of the surge's writes unaccepted, every one dropped by
k6 and none refused by the app.

The VU budget is therefore derived from the deadline, a tenth over rate times
deadline, so it runs out only after the deadline has. It is allocated before the
run starts, because k6 drops writes while it grows VUs mid-run.

The default of 5 s is set by the rig, not by what a producer would tolerate, and
it is stricter than common clients (OkHttp waits 10 s). k6 keeps a connection
per VU and hands work to every allocated VU in turn, so a run opens as many
connections as it has VUs, and Tomcat accepts 8,192. A 10 s deadline at
1,000 req/s needs 11,000 VUs, and a smoke run saw Tomcat stop accepting
connections eight seconds into the baseline. At 5 s the run opens at most
5,500 write and 500 read connections; the server can still end up holding more,
as the first rows show. The deadline only moves how many of the surge's
writes count as unaccepted, not whether the surge blocks them, and it stays the
same for every row of the series, Stage 3's included.

Compare each phase with the baseline phase of the same row, not with
read/spike's journal: the writes take connections, so the read side is not
surging against the ceiling that cell derives its rate from.

## What the first rows measured

Three rounds on `ab1f425` (2026-10-04), 20.1M rows, LiveAgent stopped and the
stack scraping. Ranges are across the rounds. The pool figures come from
Prometheus, which keeps 15 days, so they are kept in
[`pool-metrics.json`](./pool-metrics.json) with the queries that produced them.

| Phase | Writes not accepted within 5 s | Wait for a connection, mean | Read p95 |
|---|---|---|---|
| baseline | 0% | ~0 | 115–117 ms |
| spike | 93.7–94.1% | 7.3–7.7 s (max 16.9 s) | 8.5–8.6 s |
| recovery | 19.8–21.1% | 3.1–3.3 s | 7.6–7.8 s |

- **A read surge all but stops ingest.** Of the 30,000 writes scheduled during
  the surge, 1,775–1,877 were accepted within the deadline. The wait is the
  server's own figure, the time Hikari took to hand out a connection, so it does
  not depend on the deadline: a client patient for 10 s would still have lost a
  large share.
- **Nothing failed; everything waited.** No 5xx and no pool timeout. Every
  unaccepted write timed out, apart from 23–33 per round that could not connect
  at all.
- **The pool is what holds them.** All ten connections were busy for the whole
  surge, with up to 8,081 requests queued for one, while the app used under one
  core and the host about 60%, which by elimination is mostly Postgres's ten
  backends sorting for `active-users`.
- **The surge outlasts itself.** In the 30 s after it ended, a fifth of the
  writes still missed the deadline and reads ran at about 65x their baseline.

One reading is an inference rather than a measurement: **a request whose client
gave up keeps its place in the queue.** Nothing cancels it, so it waits for a
connection and runs its INSERT for nobody. The queue reached 8,081 while the
clients held at most 6,000 requests, and the pool handed out about 23,000
connections during the surge against roughly 1,800 writes accepted and 2,350
reads served, so most of the surge's write work went to clients that had already
left. Those requests are also why the server ran into Tomcat's 8,192 connections
when the run opens no more than 6,000: the connection failures above, and the
same 8,081 in every round, match that ceiling.

This is the baseline Stage 3 is measured against: the same cell with
`ingest_path: async` has to accept the surge's writes as it accepts the
baseline's.

## No verdict yet

A row records numbers and no pass or fail. The thresholds belong to Stage 3,
which states them against these rows, and a verdict here would judge the sync
path by a target set for the async one.
The cell stays out of the CI comparison for the reason every spike cell does.
