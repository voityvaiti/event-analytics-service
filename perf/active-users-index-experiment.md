# Which index shape earns `active-users` its `user_id`?

Measured 2026-08-20 on the reference rig (Ryzen 7 7700, 16 cores, pool 10,
`shared_buffers` 128MB, `work_mem` 4MB), against the default corpus: 20M rows
over 180 days, reseeded fresh before the first arm. Three arms, three rounds of
all ten cells per arm, 108 journal rows. Reads ran before writes in every pass,
after `VACUUM ANALYZE` and a `REINDEX`, so no arm's reads paid for another
arm's write bloat.

| Arm | Secondary indexes on `events` | `schema_version` | rows stamp |
|---|---|---|---|
| A — base | `(tenant_name, occurred_at, event_type)` | 5 | `ea78cd8` |
| B — second tree | A's + `(tenant_name, occurred_at, user_id)` | 6 | `7ff9d71` |
| C — one wide tree | `(tenant_name, occurred_at, event_type, user_id)` only | 7 | `c92df02` |

The branch ships arm C as its only migration, so the as-run chain was
rewritten: commits `7ff9d71` and `c92df02` live under the
`active-users-index-run-order` tag, which is where the stamps above resolve.
The shipped migration is numbered V7 with version 6 deliberately skipped:
arm B ran as a real version 6 and its rows stamp that number, so reusing 6
for a different schema would leave one number meaning two things — as already
happened to version 3, whose 2026-08-08 rows mean "V2 minus its index" and
whose later rows mean the tenant-led index. With the gap, 7 means the wide
tree everywhere and 6 means only the arm that never shipped.

## Verdict: widen the one tree

| | B — second tree | C — one wide tree |
|---|---|---|
| `active-users` p95, all four windows | 5.3 / 109.5 / 767 / 3405 ms | 5.0 / 110 / 768 / 3418 ms |
| `event-counts` tax vs A | none | +3.2% type@30d, ~+2% @7d, floor at 1h/1d |
| batch ingest vs A's 125,771 events/s | **119,182 (−5.2%)** | 121,942 (−3.0%) |
| secondary index disk | **2,182 MB** | 1,288 MB |

The two shapes serve `active-users` identically — every window within 0.4%,
inside the arms' own spreads. So the only thing B's second tree actually buys
is `event-counts` keeping its narrower entries, and that is worth ~3% on the
two widest windows and nothing measurable on the 1h/1d windows that dominate
traffic. For it, B pays a batch-ingest tax nearly twice C's and carries 894MB
more disk. The trade is one-sided once the reads tie: C wins on every column
that separates them, and the wide windows where it pays are the ones Stage 4's
rollups are already scheduled to take over.

## What `user_id` in the index bought

`active-users` p95 per window, medians of three rounds:

| Window | A — heap per row | C — index only | Ratio |
|---|---|---|---|
| 1h | 9.6 ms | 5.0 ms | 1.9x |
| 1d | 127.6 ms | 109.8 ms | 1.16x |
| 7d | 862.8 ms | 768.0 ms | 1.12x |
| 30d | 3,774 ms | 3,418 ms | 1.10x |

Every scan in arms B and C landed on the `user_id`-carrying tree with zero heap
fetches and zero sequential scans — the per-index counters added for this
experiment say so directly, where the old single-name counter would have
recorded `index_scans: 0` and left the plan change invisible. The narrowest
window nearly halved. The wide ones barely moved, and the section below is why.

## The target was 1 second, and no index reaches it

Stage 1's exit condition asked for the 30d window under 1s p95, flagged in
advance as a hypothesis. It is refuted, and cleanly: `EXPLAIN (ANALYZE,
BUFFERS)` on the 30d shape in arm B shows an index-only scan producing
3,333,333 rows in **0.87s** with zero heap fetches — the part an index can fix,
fixed — feeding a sort for `COUNT(DISTINCT user_id)` that spills 94MB to disk
past `work_mem` and takes the remaining **~2.6s**. The index removed the I/O;
the aggregation was the bottleneck underneath it.

That 3.4s is the number ROADMAP said would re-derive Stage 4's rollup target
if this landed above 1s: a rollup owns the sort, not just the scan, so its
target stays what it was for `event-counts` — pre-aggregated rows measured in
buckets, not events. Two cheaper follow-ups fall out as well, noted not
scheduled: `work_mem` sized so a month of one tenant does not sort on disk,
and the query shape (`GROUP BY bucket, user_id` first) that lets the count
hash instead of sort. Both attack the 2.6s the index cannot.

## The first write tax this suite has resolved

Single-event ingest saw nothing, again: 3,711 → 3,801 → 3,695 req/s across the
arms, inside the ~6% floor that cell carries, sign not established. Batch is a
different story — at 100 events per request the per-row work is all that is
left, and the arms separate cleanly against spreads of 0.3–2.6%:

| Arm | events/s | vs A |
|---|---|---|
| A — one tree | 125,771 | — |
| B — two trees | 119,182 | −5.2% |
| C — one wide tree | 121,942 | −3.0% |

Maintaining a second b-tree costs about as much again as widening the existing
one — and the widening half is bounded honestly by the same cell. Disk says the
same thing: A 1,060MB, B 2,182MB, C 1,288MB of secondary index for the same
20M rows. Build time on the corpus: 9.6s (B's tree), 9.9s (C's) — both past the
10s statement timeout the migrations lift for exactly that reason.

## The spike did not flip, and the arithmetic says it could not

The `active-users` surge stays `STILL DRAINING` in all nine rounds: the 1d
ceiling moved from ~83 to ~90 req/s served of 400 offered, recovery p95 from
6.5s to 5.7s, baseline from 123ms to 110ms. Recovery needs the 1d query below
~25ms (pool ÷ recovery bound) and `user_id` in the index bought 14%, not 5x —
the heap fetches it removed were never most of that query. `event-counts` and
`top-pages` spikes reproduced their verdicts in every arm, which is the
experiment's control: nothing moved that the index does not touch.

## Method notes

- Arms ran sequentially, A → B → C, one commit per pass so the row's `commit`
  stamp names its arm. Both migrations were real Flyway migrations; the DB was
  rebuilt from V1 and reseeded once, after the history rewrite, rather than
  toggled by hand.
- The batch delta is causal, not drift: C ran last and came out *above* B, in
  tree-count order rather than time order.
- A sanity round preceded each migrated pass — one `active-users` round to
  confirm the planner had actually moved (all scans on the new tree, zero
  sequential) before three measured rounds were spent. Sanity rows were
  discarded, not journalled.
- Arm A's own spread is the floor for the regime this experiment created
  (index-only scan under a disk sort): 30d p95 peak-to-peak 1.77% in arm A,
  0.18% and 0.07% in B and C. Every delta reported above clears its cell's
  floor or is reported as unresolved.
- 20M-row caveat, same as the corpus README's: one tenant owns every row, so a
  30d window is 3.33M rows and 100k users appear in every day-bucket. Real
  tenants are smaller; the sort that dominates here shrinks with them.

## Follow-ups

- Stage 4 rollups own the remaining 2.6s of the 30d window; the measured
  post-index number to beat is ~3.4s p95, of which 0.9s is the scan.
- `work_mem` and the hash-friendly query shape, above — cheaper than rollups,
  unmeasured, and attacking the same 2.6s.
- `SPIKE_WINDOW` sweeps for `active-users` now describe a different curve: the
  1h window nearly halved while 1d moved 14%, so the ceiling-vs-window curve
  from the first index experiment no longer holds above this migration.
