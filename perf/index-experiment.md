# Is `idx_events_occurred_at` worth its write tax?

Measured 2026-08-08 on the reference rig (Ryzen 7 7700, 16 cores, 30GB RAM, pool 10, `shared_buffers` 128MB), against a corpus spread over 180 days in every case.

Ten passes compared indexed (A, `schema_version: 2`) and unindexed (B, version 3) arms at 0, 20k, 200k, 2M, and 20M rows. Each pass ran three rounds of six cells, producing 240 journal rows. `start_rows` identifies corpus density.

V2 added the index for `/stats` time-range filters. This experiment measures its benefit, write cost, and the densities at which plans and surge recovery change.

## Verdict: keep it. It buys a decade of data, not milliseconds

| Density | Ceiling without index | Ceiling with index | Survives 400 req/s? |
|---|---|---|---|
| none | ~20 000 req/s | ~20 000 req/s | both |
| 20k | ~7 000 | ~17 000 | both |
| 200k | 830 | ~6 400 | both |
| 2M | **114** | 927 | **index only** |
| 20M | 14.5 | **80** | **neither** |

Ceiling = 10 connections ÷ `active-users` 1d-window p95. At 20M rows, measured surge rates were 14.9 versus 14.5 predicted without the index, and 84.5 versus 79.7 with it (2.7% and 6.1% differences). At 2M the unindexed rate was 95 versus 114 predicted, 17% lower. High ceilings are rounded because small latency rounding errors materially change the quotient.

**The index shifts the failure threshold for a 400 req/s burst from below 2M rows to below 20M: roughly one order of magnitude more data.**

## The surge, in full

Read spike, `active-users`, 1d window, 20 → 400 → 20 req/s. Medians of three rounds; `served` is the rate during the 30s surge.

| Density | Arm | Served | Dropped | Failed | Baseline p95 | Recovery p95 | Verdict |
|---|---|---|---|---|---|---|---|
| 200k | no index | 400 | 0 | 0% | 11 ms | 11 ms | recovered |
| 200k | index | 400 | 0 | 0% | 2.4 ms | 2.4 ms | recovered |
| 2M | no index | 95 | 9 141 | 0% | 53 ms | 5 272 ms | STILL DRAINING |
| 2M | **index** | **400** | **0** | 0% | 13 ms | 13 ms | **recovered** |
| 20M | index | 85 | 9 459 | 0% | 124 ms | 6 270 ms | STILL DRAINING |
| 20M | no index | 15 | 11 384 | **57%** | **16 680 ms** | 29 600 ms | NO VALID BASELINE |

Without an index, moving from 200k to 2M rows raised query latency from 10.5 ms to 71 ms but recovery from 11 ms to 5.3s. Queueing begins when demand exceeds capacity, making recovery a threshold effect.

At 20M neither arm survives 400 req/s. The indexed arm recovers slowly (6.3s); the unindexed baseline is already 16.7s and 57% of surge requests fail. The indexed ceiling is ~80 req/s, so further protection is needed beyond the plan.

Write spikes recovered in all 30 rounds, across both arms and every density: ~4,000 of 8,000 req/s served, baseline and recovery p95 between 2.0 and 4.5 ms.

## Reads: the advantage is a curve with a peak

Median latency without the index ÷ with it:

| Cell | none | 20k | 200k | 2M | 20M |
|---|---|---|---|---|---|
| `event-counts` groupBy=type | 1.0x | 2.5x | 18.8x | **45.0x** | 40.3x |
| `event-counts` groupBy=hour | 1.0x | 2.4x | 14.2x | **21.2x** | 17.8x |
| `event-counts` groupBy=day | 1.0x | 2.4x | 14.3x | **20.8x** | 17.3x |
| `top-pages` | 1.0x | 2.6x | 14.4x | **23.0x** | 17.9x |
| `active-users` | 1.0x | 2.2x | **8.3x** | 7.0x | 4.3x |

The advantage rises, peaks around 2M rows, then falls:

- At low density, shared request overhead dominates: ~0.4 ms for an empty request plus ~0.7 ms for a 20k full scan.
- From 2M to 20M, indexed latency grows 7.6–11.9x and sequential-scan latency 6.8–7.9x. The index narrows the range, but each fixed window still contains ten times as many rows.

The measured index was `(occurred_at, event_type)`. It covered `event-counts` with `Heap Fetches: 0`; `top-pages` and `active-users` still fetched `properties` and `user_id` from the heap. Unindexed queries all took 66–71 ms at 2M, so the ratios largely reflect indexed-plan cost. Type grouping needs only four groups; time grouping sorts computed `date_trunc` values; distinct-user counting adds heap access and `COUNT(DISTINCT)`.

Overall p95 favoured the unindexed time groupings at 20M because it reflects the widest queries in the mixed workload. The table above therefore uses medians; the next section separates window sizes.

## Density does not move the crossover, it creates it

`p95` without the index ÷ with it, for the narrowest and widest window the read scenarios ask for. Above 1 the index wins:

| Cell | Window | none | 20k | 200k | 2M | 20M |
|---|---|---|---|---|---|---|
| `event-counts` type | 1h | 1.00x | 2.85x | 23.9x | 148x | **476x** |
| | 30d | 1.00x | 2.02x | 2.95x | 3.14x | 2.21x |
| `event-counts` day | 1h | 1.00x | 2.79x | 22.8x | 119x | **298x** |
| | 30d | 1.00x | 1.45x | 1.61x | 1.44x | 1.06x |
| `active-users` | 1h | 0.98x | 2.66x | 20.9x | 75.5x | 62.1x |
| | 30d | 0.98x | 1.17x | 1.11x | **0.87x** | **0.73x** |

At 20k rows (6 MB), benefits are similar across windows. With more data, narrow windows gain much more: at 20M, the type-count 1h ratio reaches 476x, while the `active-users` 30d ratio falls to 0.73x.

A 30d window covers one sixth of the corpus. For `active-users`, index range scans plus heap fetches become slower than sequential scans between 200k and 2M rows. The crossover depends on table size and window selectivity.

## Where the index starts, and why the empty table measures nothing

At zero rows, both arms answered in 0.37–0.46 ms, within 2%. Both mostly used sequential scans; only three of A's 15 rows recorded a few dozen index scans among ~256,000 queries. This primarily measures HTTP overhead.

At 20k, every A row had zero sequential scans and reads were already ~2.4x faster. The planner switched below 20k rows, while the benefit continued growing. This is why read tests need a populated corpus.

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

The indexed arm was slower at four densities and faster at one. The pooled 1.66% gap is below the 5.84% spread, so the experiment does not establish the sign of a small write effect.

Arms ran sequentially, with B before A and a database rebuild between them. Drift is therefore confounded with the index. A also had wider spread (5.56% versus 4.22%), whose cause cannot be isolated here.

At ~3,900 single inserts/s and pool 10, HTTP, WAL, and connection waits hide small index costs. B-tree depth grows logarithmically: roughly two levels at 20k versus three or four at 20M.

Storage cost is clear: 39 bytes per row, 736 MB at 20M, or 17% of heap size.

## What this experiment cannot see: the disk

The 20M table and indexes occupy 6 GB on a 30 GB machine. An unindexed `EXPLAIN (ANALYZE, BUFFERS)` scan obtained 4.1 GB from the OS in 287 ms (~14 GB/s), while the device read only 28 KB. These results describe page-cache performance.

Beyond RAM, sequential scans would read gigabytes from disk; the declining index advantage may reverse. That remains untested.

`Workers Launched: 2` also explains why a 10x row increase costs only ~7x for large scans: three PostgreSQL processes share the work. Under surge, CPU can therefore saturate alongside the connection pool.

## The noise floor, by regime

Peak-to-peak over three rounds. These are the numbers the [suite README](./README.md) publishes as the floor:

| Regime | Spread |
|---|---|
| Write load, `throughput_rps`, per pass | 1.35% – 4.76% |
| Write load, pooled over all 30 rounds | **5.84%** |
| Read load `p95_ms`, served from the index | 0% – 1.37% |
| Read load `p95_ms`, sequential scan, 2M and 20M | 1.64% – 9.99% |
| Read load `p95_ms`, sequential scan, 20k and 200k | 0% – 0.99% |
| Read load `p95_ms`, empty table | 0% – 4.55% |

## Method notes

- **Commit order is density order; measurement order was not.** The passes ran arm B at none/2M/20M, then arm A at none/2M/20M, then arm B at 20k/200k, then arm A at 20k/200k, and the history was then rebased so each arm reads from empty to 20M. This costs nothing in validity — every pass truncates, reseeds and `ANALYZE`s its own corpus, so a pass does not inherit anything from the pass before it — but it does mean the `commit` stamp in about a hundred rows names a commit that the rebase replaced. The **`experiment-run-order` tag** preserves the pre-rebase history so those stamps still resolve. The fields that identify what a row measured — `schema_version` and `start_rows` — are read from the database itself and are unaffected. The one ordering that does matter is arm B before arm A, and that is preserved.
- **Passes ran in density order, not interleaved by arm**, because switching arms needs a migration and a restart. Read effects are three orders of magnitude too large for that to matter; the write figure is the one it limits, as above.
- **The database was dropped between arms**, not repaired. A `git revert` restores the migration file and leaves the applied V3 row behind, which fails Flyway validation on the next start — the first attempt at this experiment had to recreate the index and delete that row by hand. Rebuilding from V1 costs nothing here, because every pass reseeds anyway and each arm's first pass wants an empty table.
- **The rig reproduces across that rebuild.** Arm A at 20M returned 11.2, 27.4, 28.1, 31.2 and 120.0ms medians against the discarded first run's 11.2, 27.1, 27.8, 31.0 and 119.8ms — measured on a database that had been destroyed and rebuilt in between.
- **The planner's choice is evidenced by counters**, not by `EXPLAIN` on a second copy of the SQL kept in step by hand: `idx_scan` and `seq_scan` deltas over the queries the app really ran. Every arm-B row shows `index_scans` 0, and every arm-A read row from 20k up shows `seq_scans` 0 but four — each the first round of its cell, against latencies identical to the two rounds after it, so what those sweeps counted was not the measured queries.
- **Two harness changes were needed first.** `SEED_ROWS=0` now empties the table instead of failing in the generator, and a write cell now `ANALYZE`s as well as `VACUUM`s when it puts the corpus back — otherwise the read cells that follow it in the same pass plan against a row estimate inflated by a batch that is no longer there. Irrelevant at 20M, a tenth of the table at 2M, and the whole table at 20k.
- **The read spike's severity was not constant across this grid**, which is a flaw in the test rather than in the data. `SPIKE_RATE` is an absolute 400 req/s while the ceiling moves with density, so the same cell applied 2% of capacity at 20k and 500% at 20M. Follow-up 3.
- `perf_read_spike_active_users` returns 0 whether or not the app recovered, which is why all 30 read-spike rows exist despite 9 red verdicts: a failing cell stops its remaining rounds. Its header comment claimed the opposite and was corrected. A second wart is left standing: `baseline_achieved_rps` journals as `null` in both spike cells, because only the spike phase's `http_reqs` is materialised by a threshold. (A third went unnoticed here: `spike_achieved_rps` is diluted across the whole 80s run rather than the 30s surge, which is why the 84.5 req/s quoted above is the row's 31.7 corrected by hand. The read scenario has since been fixed on both counts — all three phases materialised, each rate counted over its own phase — and the write spike still carries them. Every row this experiment produced is uncorrected.)

## The passes

| Density | No index | Index |
|---|---|---|
| none | `ec79453` | `d2eac31` |
| 20k | `77aaeb4` | `f457582` |
| 200k | `f6b589b` | `4d9631c` |
| 2M | `060ba18` | `41e32ed` |
| 20M | `7ec5b0f` | `1c8fdd7` |

## Follow-ups

1. **A density past the page cache — 100M rows, ~26GB against 30GB of RAM.** The only point that can reverse a trend here rather than extend it, because it is the first one where a sequential scan means the disk. It needs the bands in `lib/seq-space.js` moved first: `CORPUS_SEQ_LIMIT` caps the corpus at 30M so it cannot reach into a write scenario's sequence range.
2. **Heavy-query protection**: a statement timeout or a bounded queue for `/stats`. At 20M the indexed arm cannot absorb the surge either, so this is the next thing that changes an outcome — and it is what would let the read spike cell gate.
3. **Express every spike as a multiple of the measured ceiling**, not as an absolute rate. Done on the read path — each cell now derives its `SPIKE_RATE` as ~5x pool size over its own 1d `p95`, which is what makes three endpoints an order of magnitude apart comparable at all. Still open for the write path and across densities: the write spike's nominal 8000 req/s is 2x its ceiling on paper but only ever applies 1x, because k6 sheds the rest client-side once the app slows, and every read rate above is frozen to this rig's 20M-row corpus.
4. **Sibling read spike cells.** Done — `event-counts` and `top-pages` now have cells beside `active-users`, on the condition the old README set: per-query cost spreads 11x across the endpoints at 20M against a ceiling of pool size over query latency, so they cannot shed alike. The pinned 1d window still deserves the same treatment — for `active-users` the index swings from 62x at 1h to 0.73x at 30d — and that cell does not exist.
5. **Vary the pool.** It is the numerator of every ceiling in this document and the one input never swept. Whether 400 req/s at 20M is reachable with a pool of 40, or whether forty concurrent scans just make each query proportionally slower, is a cheap experiment on an already-seeded corpus and it decides how much of the protection in follow-up 2 has to be a timeout.
