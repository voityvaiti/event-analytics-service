# Is `idx_events_occurred_at` worth its write tax?

Measured 2026-08-08 on the reference rig (Ryzen 7 7700, 16 cores, 30GB RAM, pool
10, `shared_buffers` 128MB), against a corpus spread over 180 days in every case.

Ten passes: two arms — index and no index — across five corpus densities — none,
20k, 200k, 2M and the default 20M rows. Three rounds of all six cells per pass, so
240 journal rows. Arm A stamps `schema_version` 2 and arm B stamps 3, `start_rows`
carries the density, and between them every row says which cell of the grid
produced it.

The index has been carried since V2 on the reasoning that every `/stats` query
filters on an `occurred_at` range. Sound, but never measured. Density is in the
grid because one table size cannot separate an answer about the index from an
answer about the size of the table — and because the interesting quantities here
turned out not to be ratios at all, but the densities at which behaviour changes.

## Verdict: keep it. It buys a decade of data, not milliseconds

| Density | Ceiling without index | Ceiling with index | Survives 400 req/s? |
|---|---|---|---|
| none | ~20 000 req/s | ~20 000 req/s | both |
| 20k | ~7 000 | ~17 000 | both |
| 200k | 830 | ~6 400 | both |
| 2M | **114** | 927 | **index only** |
| 20M | 14.5 | **80** | **neither** |

The ceiling is pool size divided by what one query costs — 10 connections over the
1d-window `p95` of `active-users`. It is not a model fitted afterwards: at 20M the
surge sustained 14.9 req/s against 14.5 predicted and 84.5 against 79.7 — within
2.7% and 6.1%. At 2M it sustained 95 against 114 — 17% under, the one density where
the ceiling reads high rather than tight. The rows above a few thousand are rounded
on purpose: they divide by latencies of one or two milliseconds, where a hundredth
of a millisecond moves the result by percent, so they carry an order of magnitude
and not four digits.

Read the table as one sentence: **the index moves the amount of data the read path
can hold before a dashboard burst stops being servable from somewhere under 2M
rows to somewhere under 20M.** One order of magnitude of headroom. Everything
below is detail about why, and the write side is the price.

## The surge, in full

Read spike, `active-users`, 1d window, 20 → 400 → 20 req/s. Medians of three
rounds; `served` is the rate during the 30s surge.

| Density | Arm | Served | Dropped | Failed | Baseline p95 | Recovery p95 | Verdict |
|---|---|---|---|---|---|---|---|
| 200k | no index | 400 | 0 | 0% | 11 ms | 11 ms | recovered |
| 200k | index | 400 | 0 | 0% | 2.4 ms | 2.4 ms | recovered |
| 2M | no index | 95 | 9 141 | 0% | 53 ms | 5 272 ms | STILL DRAINING |
| 2M | **index** | **400** | **0** | 0% | 13 ms | 13 ms | **recovered** |
| 20M | index | 85 | 9 459 | 0% | 124 ms | 6 270 ms | STILL DRAINING |
| 20M | no index | 15 | 11 384 | **57%** | **16 680 ms** | 29 600 ms | NO VALID BASELINE |

Two things only a density sweep shows.

**The cliff is a cliff.** Between 200k and 2M the unindexed read path goes from
absorbing the whole surge to shedding three requests in four, and its recovery goes
from 11ms to 5.3 seconds. Nothing about the underlying latency is discontinuous —
it grows smoothly, 10.5ms to 71ms — but the outcome is, because a queue only
starts forming once demand crosses the ceiling. Latency is linear; survival is a
threshold.

**At 20M both arms are over it.** The index no longer buys survival, only
degradation instead of collapse: 6.3s recovery against a healthy baseline, versus
a system whose baseline has itself collapsed to 16.7s and which sheds 57% of the
surge outright. This is the measurement behind the protection this suite keeps
deferring — at the default corpus no query plan saves the read path from a burst,
because the ceiling is 80 req/s and the burst is 400. A statement timeout or a
bounded queue is the mechanism that helps, not another index.

The **write spike is untouched** by either variable: all 30 rounds recovered, at
every density and in both arms, absorbing ~4000 of 8000 req/s with baseline and
recovery `p95` alike between 2.0 and 4.5ms.

## Reads: the advantage is a curve with a peak

Median latency without the index ÷ with it:

| Cell | none | 20k | 200k | 2M | 20M |
|---|---|---|---|---|---|
| `event-counts` groupBy=type | 1.0x | 2.5x | 18.8x | **45.0x** | 40.3x |
| `event-counts` groupBy=hour | 1.0x | 2.4x | 14.2x | **21.2x** | 17.8x |
| `event-counts` groupBy=day | 1.0x | 2.4x | 14.3x | **20.8x** | 17.3x |
| `top-pages` | 1.0x | 2.6x | 14.4x | **23.0x** | 17.9x |
| `active-users` | 1.0x | 2.2x | **8.3x** | 7.0x | 4.3x |

Three densities would have called this a constant. Five show a rise, a peak around
2M, and a decline, and both ends have a cause:

- **At the small end a fixed cost dominates.** An empty request costs ~0.4ms of
  HTTP round trip, and at 20k a full scan adds only ~0.7ms on top. Both arms pay
  the same floor, so the ratio is squeezed toward 1 no matter how much better the
  plan is.
- **At the large end the indexed arm starts scaling too.** Ten times the rows in
  the same 180 days means ten times the rows inside any window, so the index arm
  grows 7.6x to 11.9x from 2M to 20M while the sweeping arm grows 6.8x to 7.9x.
  The index narrows *which* rows are read; it never stops them being read.

The order of the column is set by the index being composite.
`idx_events_occurred_at` is `(occurred_at, event_type)`, led by `occurred_at` so a
range predicate can drive it when no event type is asked for. Every `event-counts`
grouping therefore runs as an index-only scan — `EXPLAIN` reports `Heap Fetches: 0`
— because predicate, grouping key and count all come out of the index. `top-pages`
and `active-users` cannot: `properties` and `user_id` are not in it, so every
matching row is fetched from the heap.

That ordering is the whole ratio column, because the arm being divided by is flat:
without the index every cell pays the same full sweep, 66 to 71ms at 2M. So each
ratio is the inverse of what the indexed plan costs. `groupBy=type` heads every
table because it aggregates straight off the indexed column into four groups, where
the time buckets sort theirs on a computed `date_trunc` first; `active-users` peaks
earliest and lowest because it is the query the index helps least by construction —
a heap visit per row, then `COUNT(DISTINCT)`.

Aggregate `p95` disagrees with all of the above — it is *better* without the index
for the time groupings at 20M — which is why the tables here use medians and the
next section uses per-window figures. A single percentile over a deliberately
mixed workload describes its widest windows and nothing else.

## Density does not move the crossover, it creates it

`p95` without the index ÷ with it, for the narrowest and widest window the read
scenarios ask for. Above 1 the index wins:

| Cell | Window | none | 20k | 200k | 2M | 20M |
|---|---|---|---|---|---|---|
| `event-counts` type | 1h | 1.00x | 2.85x | 23.9x | 148x | **476x** |
| | 30d | 1.00x | 2.02x | 2.95x | 3.14x | 2.21x |
| `event-counts` day | 1h | 1.00x | 2.79x | 22.8x | 119x | **298x** |
| | 30d | 1.00x | 1.45x | 1.61x | 1.44x | 1.06x |
| `active-users` | 1h | 0.98x | 2.66x | 20.9x | 75.5x | 62.1x |
| | 30d | 0.98x | 1.17x | 1.11x | **0.87x** | **0.73x** |

At 20k the index is worth the same ~2.5x whatever you ask for — the whole table is
six megabytes, so no window is expensive. The spread between window sizes then
opens with density, until at 20M the same index is worth 476x on an hour and
*costs* 27% on a month.

So the crossover where an index stops paying is not a property of the query, and
not a fixed window: it appears as the table grows, and it lands where a window
stops being selective. 30d is a sixth of the 180-day corpus, and that is the
column that goes below 1 for `active-users` — between 200k and 2M — and keeps
falling. A range scan plus a heap visit per row beats a sequential scan only while
the range is a small share of the table, and "small share" is what changes.

## Where the index starts, and why the empty table measures nothing

At zero rows the two arms are identical — every cell answers in 0.37 to 0.46ms,
within 2% of each other, and the counters show the planner sweeping in both arms
(in three of arm A's 15 empty-table rows it reached for the index a few dozen times
out of ~256 000 queries). That is the HTTP round trip being measured, not a query.

At 20k it has already switched: every arm-A row shows `seq_scans` 0, and the index
is already worth 2.4x. So the planner's decision flips below twenty thousand rows,
while the *payoff* keeps growing for another two orders of magnitude.

The suite seeds a corpus because of this, and asserted it without a measurement
until now. A read test on an empty table cannot fail.

## Writes: under 2%, and the sign is not established

Throughput, `events/s`, medians of three rounds:

| Density | No index | Index | Delta |
|---|---|---|---|
| none | 3972.1 | 3895.6 | −1.93% |
| 20k | 3896.3 | 3924.6 | **+0.73%** |
| 200k | 3956.7 | 3884.3 | −1.83% |
| 2M | 3969.5 | 3946.1 | −0.59% |
| 20M | 3963.0 | 3867.1 | −2.42% |
| **pooled, 15 rounds each** | **3961.2** | **3895.6** | **−1.66%** |

Four densities out of five put the indexed arm lower, the fifth puts it higher, and
the pooled gap is 1.66% against a peak-to-peak of 5.84% over the 30 rounds. The
honest reading: **the write cost is at most about 2%, and this rig cannot establish
even its sign.** A three-density version of this table looked like a consistent
negative sign; the two extra densities took that away, which is the more useful
result.

Do not reach for significance here. Arm B ran before arm A with a database drop and
re-migration in between, so drift across that boundary lands on the arm variable,
and arm A's rounds are the wider ones (5.56% peak-to-peak against 4.22%) without
anything to say whether that is index maintenance or pass order.

At ~3900 single-row inserts/s through a pool of 10, per-insert cost is dominated by
the HTTP round trip, WAL and connection wait; maintaining one b-tree does not
surface through that. Its depth also grows logarithmically, which is why density
changes nothing here — the 20M table's index is three or four levels where the 20k
table's is two.

The tax that *is* unambiguous is storage: 39 bytes per row, 736MB at 20M, 17% on
top of the heap.

## What this experiment cannot see: the disk

Every number above is memory speed. At 20M the table and its indexes are 6GB
against 30GB of RAM, so `EXPLAIN (ANALYZE, BUFFERS)` on the unindexed plan asks
the operating system for 4.1GB and gets it in 287ms — 14GB/s, well past what the
NVMe can deliver — while the device itself serves 28KB during the whole query.

So "sequential scan" here means scanning RAM. Beyond the page cache the unindexed
arm has to read gigabytes off disk per query while the indexed one still reads a
few pages, and the declining tail of the ratio curve above should reverse. That is
the untested half of the picture, and the first follow-up.

The same check explained the one loose end in the growth figures: `Workers
Launched: 2`. Postgres runs the large scans across three processes, which is why
ten times the rows costs the sweeping arm only ~7x — and why one unindexed query
occupies three cores, so the pool ceiling is not the only thing a burst of them
exhausts.

## The noise floor, by regime

Peak-to-peak over three rounds. These are the numbers the
[suite README](./README.md) publishes as the floor:

| Regime | Spread |
|---|---|
| Write load, `throughput_rps`, per pass | 1.35% – 4.76% |
| Write load, pooled over all 30 rounds | **5.84%** |
| Read load `p95_ms`, served from the index | 0% – 1.37% |
| Read load `p95_ms`, sequential scan, 2M and 20M | 1.64% – 9.99% |
| Read load `p95_ms`, sequential scan, 20k and 200k | 0% – 0.99% |
| Read load `p95_ms`, empty table | 0% – 4.55% |

## Method notes

- **Commit order is density order; measurement order was not.** The passes ran arm
  B at none/2M/20M, then arm A at none/2M/20M, then arm B at 20k/200k, then arm A
  at 20k/200k, and the history was then rebased so each arm reads from empty to
  20M. This costs nothing in validity — every pass truncates, reseeds and
  `ANALYZE`s its own corpus, so a pass does not inherit anything from the pass
  before it — but it does mean the `commit` stamp in about a hundred rows names a
  commit that the rebase replaced. The **`experiment-run-order` tag** preserves the
  pre-rebase history so those stamps still resolve. The fields that identify what a
  row measured — `schema_version` and `start_rows` — are read from the database
  itself and are unaffected. The one ordering that does matter is arm B before arm
  A, and that is preserved.
- **Passes ran in density order, not interleaved by arm**, because switching arms
  needs a migration and a restart. Read effects are three orders of magnitude too
  large for that to matter; the write figure is the one it limits, as above.
- **The database was dropped between arms**, not repaired. A `git revert` restores
  the migration file and leaves the applied V3 row behind, which fails Flyway
  validation on the next start — the first attempt at this experiment had to
  recreate the index and delete that row by hand. Rebuilding from V1 costs nothing
  here, because every pass reseeds anyway and each arm's first pass wants an empty
  table.
- **The rig reproduces across that rebuild.** Arm A at 20M returned 11.2, 27.4,
  28.1, 31.2 and 120.0ms medians against the discarded first run's 11.2, 27.1,
  27.8, 31.0 and 119.8ms — measured on a database that had been destroyed and
  rebuilt in between.
- **The planner's choice is evidenced by counters**, not by `EXPLAIN` on a second
  copy of the SQL kept in step by hand: `idx_scan` and `seq_scan` deltas over the
  queries the app really ran. Every arm-B row shows `index_scans` 0, and every
  arm-A read row from 20k up shows `seq_scans` 0 but four — each the first round of
  its cell, against latencies identical to the two rounds after it, so what those
  sweeps counted was not the measured queries.
- **Two harness changes were needed first.** `SEED_ROWS=0` now empties the table
  instead of failing in the generator, and a write cell now `ANALYZE`s as well as
  `VACUUM`s when it puts the corpus back — otherwise the read cells that follow it
  in the same pass plan against a row estimate inflated by a batch that is no
  longer there. Irrelevant at 20M, a tenth of the table at 2M, and the whole table
  at 20k.
- **The read spike's severity was not constant across this grid**, which is a flaw
  in the test rather than in the data. `SPIKE_RATE` is an absolute 400 req/s while
  the ceiling moves with density, so the same cell applied 2% of capacity at 20k
  and 500% at 20M. Follow-up 3.
- `perf_read_spike_active_users` returns 0 whether or not the app recovered, which
  is why all 30 read-spike rows exist despite 9 red verdicts: a failing cell stops
  its remaining rounds. Its header comment claimed the opposite and was corrected.
  A second wart is left standing: `baseline_achieved_rps` journals as `null` in both
  spike cells, because only the spike phase's `http_reqs` is materialised by a
  threshold.

## The passes

| Density | No index | Index |
|---|---|---|
| none | `ec79453` | `d2eac31` |
| 20k | `77aaeb4` | `f457582` |
| 200k | `f6b589b` | `4d9631c` |
| 2M | `060ba18` | `41e32ed` |
| 20M | `7ec5b0f` | `1c8fdd7` |

## Follow-ups

1. **A density past the page cache — 100M rows, ~26GB against 30GB of RAM.** The
   only point that can reverse a trend here rather than extend it, because it is
   the first one where a sequential scan means the disk. It needs the bands in
   `lib/seq-space.js` moved first: `CORPUS_SEQ_LIMIT` caps the corpus at 30M so it
   cannot reach into a write scenario's sequence range.
2. **Heavy-query protection**: a statement timeout or a bounded queue for `/stats`.
   At 20M the indexed arm cannot absorb the surge either, so this is the next thing
   that changes an outcome — and it is what would let the read spike cell gate.
3. **Express both spikes as a multiple of the measured ceiling**, not as an
   absolute rate. Then a spike means the same thing across densities and across the
   read and write paths, which today it does not: the write spike's nominal 8000
   req/s is 2x its ceiling on paper but only ever applies 1x, because k6 sheds the
   rest client-side once the app slows.
4. **Sibling read spike cells.** The cell admitted one only if another endpoint's
   shape sheds differently, and its [README](./read/spike/active-users) now records
   that condition as met: per-query cost spreads 11x across the endpoints at 20M
   against a ceiling of pool size over query latency. The pinned 1d window deserves
   the same treatment — for `active-users` the index swings from 62x at 1h to 0.73x
   at 30d — and neither cell exists yet.
5. **Vary the pool.** It is the numerator of every ceiling in this document and the
   one input never swept. Whether 400 req/s at 20M is reachable with a pool of 40,
   or whether forty concurrent scans just make each query proportionally slower, is
   a cheap experiment on an already-seeded corpus and it decides how much of the
   protection in follow-up 2 has to be a timeout.
