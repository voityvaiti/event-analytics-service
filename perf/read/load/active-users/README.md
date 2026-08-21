# `active-users` — distinct users per bucket

`GET /api/v1/stats/active-users`. See the [read README](../../README.md) for
how a row is read and how the questions are generated.

The heaviest read. Until V7 it was the only one whose cost was dominated by
something the index could not serve: `user_id` was not in the index, so every
row in the window was fetched from the heap — and past a wide enough window the
planner preferred sweeping the table, which is the ~3.7s 30-day p95 the rows
before V7 record. Since `user_id` entered the index the scan is index-only, and
what dominates instead is the `COUNT(DISTINCT)` sort itself: the 1-hour window
nearly halved while the 30-day window kept ~2.6s of sort on disk, the split
[the experiment](../../../active-users-index-experiment.md) measures.

That still makes this the cell where the index is worth least in relative
terms — a useful counterweight to `event-counts groupBy=type`, which it serves
completely.

Journalled with `groupBy=day`, the endpoint default. `hour` is the same plan
over more buckets and is reachable with `GROUP_BY=hour` rather than as a second
row.
