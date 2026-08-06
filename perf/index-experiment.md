# Is `idx_events_occurred_at` worth its write tax?

Measured 2026-08-06/07 on the reference rig (Ryzen 7 7700, 16 cores, pool 10,
20M-row corpus spread over 180 days). Three rounds of all six cells per arm,
`ROUNDS=3`. Arm A stamps `schema_version` 2, arm B stamps 3, so every journal row
says which arm produced it.

The index has been carried since V2 on the reasoning that every `/stats` query
filters on an `occurred_at` range. Sound, but never measured: nothing said what it
costs each insert or saves each read.

## Verdict: keep it

The trade-off is one-sided, so there is nothing to weigh. Reads gain one to two
orders of magnitude on the window sizes that dominate traffic; writes lose nothing
this rig can resolve.

## Writes — nothing was measured

| | Rounds (events/s) | Median | Range |
|---|---|---|---|
| A, index | 4003.4 / 4024.1 / 4032.3 | 4024.1 | [4003, 4032] |
| B, no index | 3956.6 / 4009.5 / 4018.8 | 4009.5 | [3957, 4019] |

The bands overlap and the *indexed* arm came out 0.36% higher. An index cannot
make inserts faster, so that gap is jitter — the cleanest available statement that
the write cost sits below what this setup resolves. At ~4000 single-row inserts/s
through a pool of 10, per-insert cost is dominated by HTTP round trips, WAL and
connection wait; maintaining one b-tree disappears into that. The write spike
agrees: both arms recover in all three rounds, absorbing ~1500 of 8000 req/s
either way.

This is a statement about *this* workload, not about indexes in general. A wider
pool, batched inserts, or several secondary indexes would each move the balance.

## Reads — one to two orders of magnitude

Median latency, three-round medians:

| Cell | A, index | B, no index | Ratio | req/s A → B |
|---|---|---|---|---|
| `event-counts` groupBy=type | 11.2 ms | 421.8 ms | **37.7x** | 74 → 9 |
| `event-counts` groupBy=hour | 27.1 ms | 462.7 ms | **17.1x** | 25 → 8 |
| `event-counts` groupBy=day | 27.8 ms | 460.4 ms | **16.6x** | 25 → 8 |
| `top-pages` | 31.0 ms | 537.0 ms | **17.3x** | 29 → 7 |
| `active-users` | 119.8 ms | 490.9 ms | **4.1x** | 7 → 6 |

Aggregate `p95` tells a different and initially confusing story — it is *better*
without the index for three of the five cells. Not a contradiction: `p95` is
dominated by the widest windows, which is exactly where the index helps least. The
per-window breakdown each row carries is where the real shape lives.

`p95` without the index ÷ `p95` with it; above 1 the index wins:

| Cell | 1h | 1d | 7d | 30d |
|---|---|---|---|---|
| `event-counts` type | 444x | 47x | 7.9x | 2.0x |
| `event-counts` hour | 282x | 20x | 3.2x | 0.96x |
| `event-counts` day | 277x | 19x | 3.0x | 1.10x |
| `top-pages` | 93x | 21x | 4.3x | 1.7x |
| `active-users` | 63x | 5.3x | 1.3x | **0.71x** |

The crossover sits between 7d and 30d. At 30d the index stops paying, and for
`active-users` it costs 40%: a range scan touching a large fraction of the table
plus a heap visit per row is worse than scanning the table outright — the case the
planner would switch on by itself given a wide enough estimate. Since the
generated traffic is mostly 1h and 1d, the index is priced against the windows
that dominate, and there it is 60x to 444x.

## The read spike found a flaw in its own gate

| | A, index | B, no index |
|---|---|---|
| Achieved of 400 req/s | 31.4 | **5.9** |
| Dropped iterations | ~9 480 | ~11 360 |
| Failed during surge | **0%** | **51.4%** |
| Baseline p95 | 123 ms | **15 196 ms** |
| Recovery p95 | 6 370 ms | 28 341 ms |
| Recovery ÷ baseline | 51.6x | 1.9x |
| `recovered` verdict | **false** | **true** |

Reproduced in all three rounds of each arm. The arm that is catastrophically
worse by every absolute measure — a 15-second *baseline*, half the surge shed —
passes the gate, while the healthy arm fails it. The gate compares recovery
against baseline, so a baseline that has itself collapsed makes the ratio look
fine.

Every measurement here is sound; only the derived `recovered` field was wrong.
The arm-B rows were written under the purely relative rule and say `true`; the
corrected rule gives `NO VALID BASELINE` for the same numbers. Their `commit`
stamp (`6c94a79`) says which rule produced them.

Two follow-ups. The first is done:

1. **The recovery verdict needed an absolute floor.** Both spike cells now require
   `baseline_p95_ms` under `BASELINE_MAX_P95_MS` before the ratio is consulted.
   Recomputed over all 21 journalled rows, that flips the three unindexed ones and
   leaves the rest unchanged.
2. **One spike endpoint is no longer defensible.** The cell's
   [README](./read/spike/active-users) justifies a single cell on the grounds that
   a spike measures the pool and the queue rather than a query shape, and allows a
   sibling "only if its shape turns out to shed differently". Per-query cost across
   endpoints differs 4x to 38x and the ceiling is roughly pool size over query
   latency, so they very likely do shed differently — `event-counts` may recover at
   a rate `active-users` cannot. The window is pinned at 1d as well, while the
   table above swings from 444x to 0.71x across windows.

## Method notes

- The noise floor was established alongside arm A rather than in a separate pass:
  with nothing changed between rounds, arm A's own spread *is* the floor. Recorded
  in the [suite README](./README.md).
- One earlier three-round write pass was discarded rather than journalled — a full
  table scan of the author's own landed inside its second measured window. Its
  rounds (4029/4005/3935) looked monotonically descending, which prompted a clean
  re-measurement; that came back 3967/4043/3966, so the trend was coincidence and
  not drift within a run.
- Arms ran sequentially, not interleaved, because switching arms needs a migration
  and an app restart. Read deltas are far too large for that to matter, and the
  write delta is inside the floor either way.
- The planner's choice is evidenced by `index_scans` / `seq_scans` counters read
  from `pg_stat_user_indexes` over the queries the app really ran, rather than by
  `EXPLAIN` on a second copy of the SQL kept in step by hand. Every arm-A read row
  shows `seq_scans` 0; every arm-B row shows `index_scans` 0.
- Arm B was measured first, so `git revert` of the drop restored the index for arm
  A. A revert alone does not restore the *database*: version 3 stays applied while
  its file is gone, which fails Flyway validation on the next start with the index
  still dropped. The development database was put back by hand — recreate the
  index, delete the version 3 row, `VACUUM ANALYZE` — which preserved the corpus a
  clean re-migration would have destroyed.
