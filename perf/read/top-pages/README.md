# `top-pages` — ranking pages in a window

`GET /api/v1/stats/top-pages`. See the [read README](../README.md) for how a row
is read and how the questions are generated.

The one read whose grouping key lives inside JSONB: `properties->>'page_url'` is
extracted per row and ranked. The index narrows the window and nothing more —
there is no index on that expression, so extraction and aggregation scale with
how many rows the window holds.

`limit` is fixed at 10 rather than varied per request. It barely moves the cost:
the query probes one row past the limit to learn whether the ranking was
truncated, so the work is dominated by scanning and grouping the window, not by
how much of the ranking is returned. Varying it would add an axis that measures
nothing.

If an expression or GIN index on `page_url` is ever proposed, this is the cell
that would show whether it earns its keep — and the same before/after protocol
used for `idx_events_occurred_at` applies unchanged.
