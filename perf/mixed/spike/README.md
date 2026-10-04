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
connections eight seconds into the baseline. At 5 s the run holds 5,500 write
and 500 read connections. The deadline only moves how many of the surge's
writes count as unaccepted, not whether the surge blocks them, and it stays the
same for every row of the series, Stage 3's included.

Compare each phase with the baseline phase of the same row, not with
read/spike's journal: the writes take connections, so the read side is not
surging against the ceiling that cell derives its rate from.

## No verdict yet

A row records numbers and no pass or fail, because nothing has been measured to
set a threshold by: Stage 3's target is what the first rows will be read for.
The cell stays out of the CI comparison for the reason every spike cell does.
