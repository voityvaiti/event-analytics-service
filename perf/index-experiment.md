# Is `idx_events_occurred_at` worth its write tax?

Measured 2026-08-08 on the reference rig (Ryzen 7 7700, 16 cores, pool 10,
`shared_buffers` 128MB), over a corpus spread across 180 days in every case.

Six passes: two arms — index and no index — against three corpus densities: no
rows at all, 2M, and the default 20M. Three rounds of all six cells per pass,
`ROUNDS=3`, so 144 journal rows. Arm A stamps `schema_version` 2 and arm B stamps
3, `start_rows` carries the density, and between them every row says which cell of
the grid produced it.

The index has been carried since V2 on the reasoning that every `/stats` query
filters on an `occurred_at` range. Sound, but never measured. Density is in the
grid because a single table size cannot tell an answer about the index from an
answer about the size of the table.

## Verdict: keep it — and the 20M read spike is the case for a statement timeout

The read side of the trade-off is not a latency improvement, it is the difference
between serving a traffic burst and not serving it. The write side is at most a
couple of percent, in a direction this rig cannot resolve. Nothing here needs
weighing.

## The surge is where the index earns its keep

Read spike, `active-users`, 1d window, stepping 20 → 400 → 20 req/s. Medians of
three rounds; `achieved` is counted over the whole 80s run, so **150 means the
entire 400 req/s surge was served** and nothing was shed.

| Density | Arm | Achieved | Dropped | Failed in surge | Baseline p95 | Recovery p95 | Verdict |
|---|---|---|---|---|---|---|---|
| none | either | 150.0 | 0 | 0% | 1.3 ms | 1.3 ms | recovered |
| 2M | **A, index** | **150.0** | **0** | 0% | 13 ms | 13 ms | **recovered** |
| 2M | B, no index | 35.7 | 9 141 | 0% | 53 ms | 5 272 ms | STILL DRAINING |
| 20M | A, index | 31.7 | 9 459 | 0% | 124 ms | 6 270 ms | STILL DRAINING |
| 20M | B, no index | 5.6 | 11 384 | **57.0%** | **16 680 ms** | 29 600 ms | NO VALID BASELINE |

At 2M the arms are on opposite sides of the line: with the index the read path
absorbs the whole surge and comes back at its baseline, and without it four out of
five requests never leave the client and recovery sits a hundred times above
baseline. Same rig, same surge, one query plan apart.

At 20M **both arms fail**. The index no longer buys survival, only degradation
instead of collapse — 6.3s recovery against a healthy baseline versus a
system whose baseline has itself collapsed to 16.7s and which sheds 57% of the
surge outright. Reproduced in all three rounds of each.

That is the measurement behind the protection this suite has been deferring: at
the default corpus, nothing in the query plan saves the read path from a
dashboard burst, because the ceiling is pool size over query latency and a 1d
`active-users` question costs 124ms even served from the index. A statement
timeout or a bounded queue is the mechanism that helps here, not another index.

The **write spike is untouched** by either variable: all 18 rounds recovered,
absorbing ~1480 of 8000 req/s with a 2-3ms baseline and a 2-3ms recovery, at every
density and in both arms.

## Reads: the ratio belongs to the query, the cost belongs to the table

Median latency, medians of three rounds:

| Cell | 2M: A → B | Ratio | 20M: A → B | Ratio |
|---|---|---|---|---|
| `event-counts` groupBy=type | 1.5 → 66.2 ms | **45.0x** | 11.2 → 451.5 ms | **40.3x** |
| `event-counts` groupBy=hour | 3.2 → 67.8 ms | 21.2x | 27.4 → 487.5 ms | 17.8x |
| `event-counts` groupBy=day | 3.3 → 67.6 ms | 20.8x | 28.1 → 488.1 ms | 17.3x |
| `top-pages` | 3.1 → 71.2 ms | 23.0x | 31.2 → 559.5 ms | 17.9x |
| `active-users` | 10.1 → 71.0 ms | 7.0x | 120.1 → 513.3 ms | 4.3x |

Two readings, and the second is the one density was added to get:

The **ratio is roughly invariant** across a tenfold change in table size — 45x
against 40x, 21x against 18x, 7.0x against 4.3x. It is a property of the query
shape, and `active-users` is low in both columns for the same reason it always
was: `user_id` is not in the index, so every matching row is fetched from the heap
regardless.

The **absolute cost scales with the corpus** in both arms, near enough
proportionally: ten times the rows costs the indexed arm 8.6x and the sweeping arm
7.3x. Neither arm has a size at which it stops caring about the table; what
changes is which absolute numbers are survivable, which is what the spike above
measured.

Aggregate `p95` again disagrees with all of this — it is *better* without the
index for the time groupings and for `active-users`, at both densities. Not a
contradiction, and the next section is why.

## The crossover is a share of the corpus, not a window size

`p95` without the index ÷ `p95` with it, per window size. Above 1 the index wins:

| Cell | | 1h | 1d | 7d | 30d |
|---|---|---|---|---|---|
| `event-counts` type | 2M | 148x | 49x | 10.3x | 3.1x |
| | 20M | 476x | 50x | 7.6x | 2.2x |
| `event-counts` hour | 2M | 122x | 24x | 4.7x | 1.5x |
| | 20M | 292x | 22x | 3.4x | 1.05x |
| `event-counts` day | 2M | 119x | 24x | 4.7x | 1.4x |
| | 20M | 298x | 23x | 3.3x | 1.06x |
| `top-pages` | 2M | 91x | 25x | 5.1x | 2.0x |
| | 20M | 94x | 21x | 4.3x | 1.7x |
| `active-users` | 2M | 75x | 8.1x | 1.7x | **0.87x** |
| | 20M | 62x | 5.5x | 1.3x | **0.73x** |

The 30d column decides where the index stops paying, and it lands in the same
place at both densities: `active-users` loses (0.87x, 0.73x), everything else is
marginal. 30d is a sixth of the 180-day corpus either way — so the crossover
tracks the **fraction of the table** a question touches, not the number of rows or
the number of days. A range scan plus a heap visit per row beats a sequential scan
until the range stops being selective, and selectivity is a share.

The 1h column moves the other way, 119x → 298x with density, and that is not the
index improving. The indexed arm is against a floor there — 0.70ms at 2M, 2.11ms
at 20M, where a bare request costs ~0.4ms — while the sweeping arm scales with the
table. The gap widens because one side has nowhere left to go.

## The empty table measures nothing, as claimed

Both arms, at zero rows, over 15 rows each: every read cell answers in 0.37 to
0.46ms and the two arms are within 2% of each other everywhere — a spread that is
sub-millisecond quantization, not a difference. The scan counters say why: the
planner sweeps in both arms, and in three of arm A's 15 rows it reached for the
index a few dozen times out of ~256 000 queries.

The suite seeds a corpus because of this, and asserted it without a measurement
until now. On an empty table an index test measures the HTTP round trip.

## Writes: bounded under 2%, and for once with a plausible sign

Throughput, `events/s`, medians of three rounds:

| Density | B, no index | A, index | A − B |
|---|---|---|---|
| none | 3972.1 | 3895.6 | −1.93% |
| 2M | 3969.5 | 3946.1 | −0.59% |
| 20M | 3963.0 | 3867.1 | −2.42% |
| pooled, 9 rounds each | 3965.0 | 3895.6 | −1.75% |

The indexed arm is lower at all three densities, and the pooled gap is 1.75% —
against a peak-to-peak of 5.82% over the 18 rounds. So: **the write cost is real
in sign, at most about 2%, and not resolvable by this rig.** That is a firmer
statement than the discarded first run of this experiment could make, which had
the *indexed* arm coming out 0.36% faster — an impossible result that could only
be read as jitter.

Do not read the pooled figure as significance. Arm B ran entirely before arm A,
with a database drop, a re-migration and a fresh COPY in between, so any drift
across that boundary lands on the arm variable. Arm A's own rounds are also the
wider ones (5.56% peak-to-peak against arm B's 3.52%), and whether that is index
maintenance or pass-order drift cannot be told apart here.

At ~3900 single-row inserts/s through a pool of 10, per-insert cost is dominated
by the HTTP round trip, WAL and connection wait; maintaining one b-tree is a few
percent of that at most. The tax that *is* unambiguous is storage: the index is
736MB against a 4224MB heap, 17% on top of the table.

This is a statement about *this* workload. A wider pool, batched inserts, or
several secondary indexes would each move it.

## Method notes

- **The database was dropped between arms**, not repaired. The first attempt at
  this experiment had to recreate the index by hand and delete the applied V3 row,
  because a `git revert` restores the migration file and leaves the schema history
  behind, failing Flyway validation on the next start. Rebuilding from V1 avoids
  all of it, and costs nothing here: the corpus is reseeded per density anyway and
  arm A's first pass wants an empty table.
- **The rig reproduces across that rebuild.** Arm A at 20M returned 11.2, 27.4,
  28.1, 31.2 and 120.0ms medians, within 0.4ms of the discarded run's 11.2, 27.1,
  27.8, 31.0 and 119.8ms — measured against a database that had been destroyed and
  rebuilt in between.
- **The planner's choice is evidenced by counters**, not by `EXPLAIN` on a second
  copy of the SQL kept in step by hand: `idx_scan` and `seq_scan` deltas over the
  queries the app really ran. Every arm-A read row at 2M and 20M shows `seq_scans`
  0; every arm-B row shows `index_scans` 0.
- **Two harness changes were needed first.** `SEED_ROWS=0` now empties the table
  instead of failing in the generator, and a write cell now `ANALYZE`s as well as
  `VACUUM`s when it puts the corpus back — otherwise the read cells that follow it
  in the same pass would plan against a row estimate inflated by a batch that is
  no longer there. Irrelevant at 20M, a tenth of the table at 2M.
- **Arms were not interleaved**, because switching one needs a migration and a
  restart. The read effects are three orders of magnitude too large for that to
  matter; the write figure is the one it limits, as above.
- **No spread is published for either spike cell.** Their inputs repeat tightly
  (`spike_achieved_rps` and `spike_dropped` to within 0.2%) but the result is a
  compound verdict, and a spread over one of its inputs would be read as a spread
  over the verdict.
- `perf_read_spike_active_users` returns 0 whether or not the app recovered, which
  is why all 18 read-spike rows exist despite 12 red verdicts: a failing cell stops
  its remaining rounds. Its header comment claimed the opposite and was corrected.
  A second wart is left standing: `baseline_achieved_rps` journals as `null` in
  both spike cells, because only the spike phase's `http_reqs` is materialised by
  a threshold.

## Follow-ups

1. **Heavy-query protection**: a statement timeout or a bounded queue for
   `/stats`. The 20M spike shows the indexed arm cannot absorb the surge either,
   so this is the next thing that changes the outcome — and it is what would let
   the read spike cell become a gate.
2. **Sibling read spike cells.** The cell's
   [README](./read/spike/active-users) allows one only if another endpoint's shape
   sheds differently. Per-query cost differs 4x to 45x across the endpoints and
   the ceiling is roughly pool size over query latency, so they do. The pinned 1d
   window deserves the same treatment: the tables above swing from 476x to 0.73x
   across window sizes.
3. **Journal the per-phase arrival rate** for both spike cells, so `achieved`
   stops being a figure a reader has to rescale from the whole run, and
   `baseline_achieved_rps` stops being `null`.
