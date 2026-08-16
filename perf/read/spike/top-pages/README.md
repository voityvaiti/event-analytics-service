# Read spike — `top-pages`

Surges `GET /api/v1/stats/top-pages` with `limit=10`. How a spike is applied,
judged and journalled is the same for every cell here and is described
[one level up](..); this page is the query.

## The one read whose grouping key lives in JSONB

`properties->>'page_url'` is extracted from every matching row and ranked, so the
index narrows the window and then stops helping — the heap visit and the sort are
the cost. That lands it between the siblings at 32.6ms for a 1d window, a ceiling
of ~310 req/s, and a surge of 1500.

`limit` bounds what comes back, not what is read: every row in the window is
still extracted and ranked before ten of them are returned. So it is journalled
at the endpoint default of 10 and left alone — `LIMIT=100` is a tenth of a
percent more response, not a different amount of work, and it is not the knob
that moves this cell's ceiling.

## The cell sitting closest to the line

Its first three rounds shed like `active-users` and drain like neither: 284 req/s
sustained, ~36 500 requests shed, and a recovery `p95` of 447ms against a 31ms
baseline. That is 14.5x — over the 5x the verdict allows, so `STILL DRAINING`,
but two orders of magnitude nearer to passing than `active-users`' 53x.

Which makes this the cell to watch once heavy-query protection lands. It is the
one whose verdict a statement timeout or a bounded queue would flip first, so it
is the natural place for the first gate — ahead of `active-users`, which needs
the query to get cheaper and not merely bounded.

## What this cell would show

If an expression or GIN index on `page_url` is ever considered, the load cell
answers whether it makes the query cheaper and this one answers the question that
actually decides a dashboard's fate: whether it moves the ceiling far enough that
a burst is absorbed rather than queued. The [index experiment](../../../index-experiment.md)
is the precedent — at 20M rows `idx_events_occurred_at` bought this endpoint 17.9x
on latency and still left the read path unable to hold a surge.
