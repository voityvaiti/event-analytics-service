# `top-pages` — ranking pages in a window

`GET /api/v1/stats/top-pages`. See the [read README](../../README.md) for how a row is read and how the questions are generated.

The query extracts and ranks `properties->>'page_url'` from matching rows. The index narrows the time range; extraction and aggregation still scale with window size.

`limit` stays at 10. The query reads one extra result to detect truncation, but scanning and grouping dominate its cost.

If an expression or GIN index on `page_url` is ever proposed, this is the cell that would show whether it earns its keep — and the same before/after protocol the two index migrations were measured by applies unchanged.
