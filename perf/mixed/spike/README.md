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
  the schedule asked for that did not come back 202.
- The rest of each phase's writes say how they ended: `dropped` (never sent,
  because every VU in the budget was waiting on an earlier write),
  `server_errors`, `rejected` (any other status, which is a harness fault rather
  than the app's), `timeouts` (no response within k6's 60 s), `transport_errors`.
- **`connection_timeouts`** is how often the pool failed to hand out a connection
  within Hikari's 30 s during the run. The write path answers that with the same
  500 as any other failure, so this is what says whether `server_errors` waited
  out the pool.
- **Write latency is of accepted writes only.** A dropped write has none, so
  under drops the percentiles describe the writes that got through.

The write VU budget (`write_max_vus`, default 6,000: 1,000 req/s held for the
~6 s a read waits in read/spike's surge) is a condition of the run, not a
property of the app, and drops mean the wait outran it. It is allocated before
the run starts, so a drop never measures how fast k6 grows VUs; that costs k6
about 2 GB and under one core on the reference rig.

Compare each phase with the baseline phase of the same row, not with
read/spike's journal: the writes take connections, so the read side is not
surging against the ceiling that cell derives its rate from.

## No verdict yet

A row records numbers and no pass or fail, because nothing has been measured to
set a threshold by: Stage 3's target is what the first rows will be read for.
The cell stays out of the CI comparison for the reason every spike cell does.
