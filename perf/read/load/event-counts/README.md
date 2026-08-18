# `event-counts` — counting events in a window

`GET /api/v1/stats/event-counts`. One journal row per grouping; see the
[read README](../../README.md) for how a row is read and how the questions are
generated.

The groupings are separate rows because they are separate query plans, not
cosmetic variants:

- **`groupBy=type`** aggregates on `event_type`, the trailing column of
  `(source, occurred_at, event_type)`. The index answers it end to end — no heap
  access at all — which makes this the cell most sensitive to what that index
  carries. It is the one that lost its index-only scan when reads were scoped to a
  tenant the V2 index did not name, and got it back when the tenant led the key.
- **`groupBy=hour` / `groupBy=day`** bucket through `date_trunc`. Still served
  from the index, but with per-bucket aggregation on top, and the two differ in
  how many buckets a window produces rather than in plan shape.

What to watch: a jump in `seq_scans` means the planner stopped using the index —
usually stale statistics or a window grown large enough that sweeping the table
looks cheaper. Either way the latency change that comes with it is not a query
regression but a planning one.
