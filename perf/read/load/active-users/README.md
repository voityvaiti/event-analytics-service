# `active-users` — distinct users per bucket

`GET /api/v1/stats/active-users`. See the [read README](../../README.md) for
how a row is read and how the questions are generated.

The heaviest read, and the only one whose cost is dominated by something the
index cannot serve: `user_id` is not in `(occurred_at, event_type)`, so every
row in the window is fetched from the heap and fed through `COUNT(DISTINCT)`.
The index narrows *which* rows; the work after that is the query.

That makes this the cell where the index is worth least in relative terms — a
useful counterweight to `event-counts groupBy=type`, which it serves completely.

Journalled with `groupBy=day`, the endpoint default. `hour` is the same plan
over more buckets and is reachable with `GROUP_BY=hour` rather than as a second
row.
