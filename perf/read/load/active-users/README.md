# `active-users` — distinct users per bucket

`GET /api/v1/stats/active-users`. See the [read README](../../README.md) for how a row is read and how the questions are generated.

The heaviest read. Before V7, each matching row required a heap fetch for `user_id`; wide windows could favour full scans (~3.7s 30d p95). V7 made the scan index-only, nearly halving 1h latency, but the 30d distinct-count sort still took ~2.6s on disk. See [the experiment](../../../active-users-index-experiment.md). The remaining sort makes this less responsive to indexing than type counts.

Journalled with `groupBy=day`, the endpoint default. `hour` is the same plan over more buckets and is reachable with `GROUP_BY=hour` rather than as a second row.
