# Read spike — `top-pages`

Surges `GET /api/v1/stats/top-pages` with `limit=10`. See [shared spike methodology](..) for execution, verdicts, and journal fields.

## The one read whose grouping key lives in JSONB

The index narrows the window, then the query fetches JSONB, extracts `properties->>'page_url'`, and ranks pages. At 1d p95 32.6 ms, the predicted ceiling is ~310 req/s; the surge rate is 1,500.

`limit=10` bounds output, while scanning and aggregation cover the full window. Increasing `LIMIT` does not remove that work.

## The cell sitting closest to the line

The first three rounds sustained 284 req/s, dropped ~36,500 requests, and had 447 ms recovery p95 against a 31 ms baseline (14.5x). This is above the 5x bound, but closer to recovery than `active-users` at 53x.

This makes it a useful early candidate for evaluating queue protection. The statement timeout did not change its verdict; see [shared results](..).

## What this cell would show

If an expression or GIN index on `page_url` is ever considered, the load cell answers whether it makes the query cheaper and this one answers the question that actually decides a dashboard's fate: whether it moves the ceiling far enough that a burst is absorbed rather than queued. The [index experiment](../../../index-experiment.md) is the precedent — at 20M rows `idx_events_occurred_at` bought this endpoint 17.9x on latency and still left the read path unable to hold a surge.
