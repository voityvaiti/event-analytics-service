# Read spike — `active-users`

Surges `GET /api/v1/stats/active-users` with `groupBy=day`. See [shared spike methodology](..) for execution, verdicts, and journal fields.

## The heaviest read, so the lowest ceiling

Before V7, every matching row required a heap fetch for `user_id` before `COUNT(DISTINCT)`. At 1d p95 123 ms, capacity was ~81 req/s, motivating the 400 req/s surge. V7 made scans index-only and raised served throughput to ~90 req/s. The 14% query improvement was insufficient: recovery needs ~25 ms, and all nine [experiment](../../../active-users-index-experiment.md) rounds stayed `STILL DRAINING`.

Earlier tenant filtering cost ~3%, then the tenant-led index recovered it; this query already required heap access in both cases.

The [first index experiment](../../../index-experiment.md) contributed 30 rows across five densities. At 20M, recovery was 6.3s against 124 ms; at 2M the indexed arm absorbed the same surge. Later sibling-cell runs reproduced 6.56s recovery, 124 ms baseline, and 84 req/s, versus the experiment's corrected 84.5 req/s.

## One point on a curve

The window is pinned at 1d for the reason given one level up, but for this query in particular that pin hides a lot: the index is worth 62x on a 1h window at 20M rows and 0.73x on a 30d one — it *costs* 27% there. The 1d ceiling this cell surges against therefore describes 1d and not the endpoint, and `SPIKE_WINDOW` with a re-derived `SPIKE_RATE` is how the rest of that curve gets measured.
