# Read spike — `active-users`

Surges `GET /api/v1/stats/active-users` with `groupBy=day`. How a spike is
applied, judged and journalled is the same for every cell here and is described
[one level up](..); this page is the query.

## The heaviest read, so the lowest ceiling

`user_id` is not in `idx_events_source_occurred_at`, so the index narrows the
window to one tenant's slice and every row inside it is then fetched from the heap
and fed through `COUNT(DISTINCT)`. At a 1d `p95` of 123ms that is a ceiling of
~81 req/s — an order of magnitude below the other two — which is why this endpoint
got the suite's first spike cell and why 400 req/s has always been its rate.

Being heap-bound is also why this cell barely noticed the two changes that moved
its siblings: scoping reads to a tenant cost it 3% and leading the index with the
tenant gave that back, because a row was being fetched either way.

It is also the cell with history: every read-spike row written before the
siblings existed is this query, including the thirty the
[index experiment](../../../index-experiment.md) produced across five corpus
densities and both index arms. At the default corpus it does not recover, with or
without the index — 6.3s recovery against a healthy 124ms baseline — and at a
tenth of it the indexed arm absorbed the same surge whole. The first rounds run
beside the siblings reproduced that exactly: 6.56s against 124ms, and 84 req/s
sustained where the experiment's hand-corrected figure was 84.5.

## One point on a curve

The window is pinned at 1d for the reason given one level up, but for this query
in particular that pin hides a lot: the index is worth 62x on a 1h window at 20M
rows and 0.73x on a 30d one — it *costs* 27% there. The 1d ceiling this cell
surges against therefore describes 1d and not the endpoint, and `SPIKE_WINDOW`
with a re-derived `SPIKE_RATE` is how the rest of that curve gets measured.
