# `event-counts` — counting events in a window

`GET /api/v1/stats/event-counts`. One journal row per grouping; see the [read README](../../README.md) for how a row is read and how the questions are generated.

Each grouping has a separate journal row:

- **`groupBy=type`:** the current `(tenant_name, occurred_at, event_type, user_id)` index covers filtering and counting without heap access. Tenant filtering broke this under V2; leading the index with the tenant restored it.
- **`groupBy=hour` / `groupBy=day`:** still index-only, with `date_trunc` and per-bucket aggregation. Bucket counts differ between the two.

What to watch: a jump in `seq_scans` means the planner stopped using the index — usually stale statistics or a window grown large enough that sweeping the table looks cheaper. Either way the latency change that comes with it is not a query regression but a planning one.
