# Which index shape earns `active-users` its `user_id`?

Measured 2026-08-20 on the reference rig (Ryzen 7 7700, 16 cores, pool 10, `shared_buffers` 128MB, `work_mem` 4MB), against the default corpus: 20M rows over 180 days, reseeded fresh before the first arm. Three arms, three rounds of all ten cells per arm, 108 journal rows. Reads ran before writes in every pass, after `VACUUM ANALYZE` and a `REINDEX`, so no arm's reads paid for another arm's write bloat.

| Arm | Secondary indexes on `events` | `schema_version` | rows stamp |
|---|---|---|---|
| A — base | `(tenant_name, occurred_at, event_type)` | 5 | `ea78cd8` |
| B — second tree | A's + `(tenant_name, occurred_at, user_id)` | 6 | `7ff9d71` |
| C — one wide tree | `(tenant_name, occurred_at, event_type, user_id)` only | 7 | `c92df02` |

Only C shipped, as V7. The rewritten measurement commits `7ff9d71` and `c92df02` remain under `active-users-index-run-order`. V6 is deliberately unused in the shipped chain because experimental B rows already identify that schema as 6. Reusing it would create the ambiguity seen with V3: 2026-08-08 rows mean “V2 without its index,” while later rows mean the tenant-led index.

## Verdict: widen the one tree

| | B — second tree | C — one wide tree |
|---|---|---|
| `active-users` p95, all four windows | 5.3 / 109.5 / 767 / 3405 ms | 5.0 / 110 / 768 / 3418 ms |
| `event-counts` tax vs A | none | +3.2% type@30d, ~+2% @7d, floor at 1h/1d |
| batch ingest vs A's 125,771 events/s | **119,182 (−5.2%)** | 121,942 (−3.0%) |
| secondary index disk | **2,182 MB** | 1,288 MB |

B and C differ by at most 0.4% on `active-users`, within measurement spread. B preserves narrower `event-counts` entries, saving ~3% on wide windows but nothing measurable at 1h/1d. C is preferred for its lower write cost and 894 MB less storage. Stage 4 rollups target the wide windows where C is slower.

## What `user_id` in the index bought

`active-users` p95 per window, medians of three rounds:

| Window | A — heap per row | C — index only | Ratio |
|---|---|---|---|
| 1h | 9.6 ms | 5.0 ms | 1.9x |
| 1d | 127.6 ms | 109.8 ms | 1.16x |
| 7d | 862.8 ms | 768.0 ms | 1.12x |
| 30d | 3,774 ms | 3,418 ms | 1.10x |

B and C used the `user_id` index with zero heap fetches and sequential scans. The new per-index counters captured this; the old counter, tied to one index name, would have reported `index_scans: 0`. The benefit is largest at 1h.

## The target was 1 second, and no index reaches it

The tested index shapes missed Stage 1's proposed 30d p95 target of 1s. Arm B's `EXPLAIN (ANALYZE, BUFFERS)` showed an index-only scan returning 3,333,333 rows in **0.87s**, with zero heap fetches. The distinct-count sort spilled 94 MB beyond `work_mem` and consumed the remaining **~2.6s**.

The ~3.4s result is Stage 4's rollup baseline. Cheaper, unmeasured alternatives are increasing `work_mem` and grouping by `bucket, user_id` first to enable hash aggregation. Both target sorting cost; neither is scheduled yet.

## The first write tax this suite has resolved

Single-event ingest saw nothing, again: 3,711 → 3,801 → 3,695 req/s across the arms, inside the ~6% floor that cell carries, sign not established. Batch is a different story — at 100 events per request the per-row work is all that is left, and the arms separate cleanly against spreads of 0.3–2.6%:

| Arm | events/s | vs A |
|---|---|---|
| A — one tree | 125,771 | — |
| B — two trees | 119,182 | −5.2% |
| C — one wide tree | 121,942 | −3.0% |

Secondary index storage was 1,060 MB for A, 2,182 MB for B, and 1,288 MB for C at 20M rows. Index builds took 9.6s for B and 9.9s for C, close to the 10s statement timeout that migrations lift.

## The spike did not flip, and the arithmetic says it could not

The `active-users` surge stays `STILL DRAINING` in all nine rounds: the 1d ceiling moved from ~83 to ~90 req/s served of 400 offered, recovery p95 from 6.5s to 5.7s, baseline from 123ms to 110ms. Recovery needs the 1d query below ~25ms (pool ÷ recovery bound) and `user_id` in the index bought 14%, not 5x — the heap fetches it removed were never most of that query. `event-counts` and `top-pages` spikes reproduced their verdicts in every arm, which is the experiment's control: nothing moved that the index does not touch.

## Method notes

- Arms ran sequentially, A → B → C, one commit per pass so the row's `commit` stamp names its arm. Both migrations were real Flyway migrations; the DB was rebuilt from V1 and reseeded once, after the history rewrite, rather than toggled by hand.
- The batch delta is causal, not drift: C ran last and came out *above* B, in tree-count order rather than time order.
- A sanity round preceded each migrated pass — one `active-users` round to confirm the planner had actually moved (all scans on the new tree, zero sequential) before three measured rounds were spent. Sanity rows were discarded, not journalled.
- Arm A's own spread is the floor for the regime this experiment created (index-only scan under a disk sort): 30d p95 peak-to-peak 1.77% in arm A, 0.18% and 0.07% in B and C. Every delta reported above clears its cell's floor or is reported as unresolved.
- 20M-row caveat, same as the corpus README's: one tenant owns every row, so a 30d window is 3.33M rows and 100k users appear in every day-bucket. Real tenants are smaller; the sort that dominates here shrinks with them.

## Follow-ups

- Stage 4 rollups own the remaining 2.6s of the 30d window; the measured post-index number to beat is ~3.4s p95, of which 0.9s is the scan.
- `work_mem` and the hash-friendly query shape, above — cheaper than rollups, unmeasured, and attacking the same 2.6s.
- `SPIKE_WINDOW` sweeps for `active-users` now describe a different curve: the 1h window nearly halved while 1d moved 14%, so the ceiling-vs-window curve from the first index experiment no longer holds above this migration.
