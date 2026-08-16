# Read spike — `event-counts`

Surges `GET /api/v1/stats/event-counts` with `groupBy=type`. How a spike is
applied, judged and journalled is the same for every cell here and is described
[one level up](..); this page is the query.

## The lightest read, so the highest ceiling

`groupBy=type` is answered entirely out of `idx_events_occurred_at`: the range
predicate drives its leading `occurred_at` column, the grouping key is its
trailing `event_type`, and counting needs nothing else — `EXPLAIN` reports
`Heap Fetches: 0`. At 11.9ms for a 1d window it serves ~840 req/s where
`active-users` serves ~79, so this cell steps to 4000 req/s to overload it at all.

That is the whole reason it exists. It is the far end of the range from
`active-users`, and it asks whether a shape that costs a tenth as much per query
also drains a burst — or whether what a surge leaves behind depends on the depth
of the queue rather than on the price of the query.

The first three rounds answered it: the price of the query. All three recovered,
with ~98 000 requests shed during the surge and recovery `p95` back within
0.2ms of a 14.4ms baseline. Same queue depth as its siblings, same 5x overload,
and the only cell that comes back — so read this row beside theirs at the same
corpus.

## The other groupings are not this cell

`hour` and `day` bucket through `date_trunc` and sort on the computed value, at
roughly 2.5x the cost — a ceiling near 350 req/s rather than 840. `GROUP_BY=hour`
repoints the cell, but leaving `SPIKE_RATE` at 4000 then applies 11x their
ceiling instead of 5x, which is a different experiment from the one the sibling
cells are running. Re-derive the rate with the grouping, or the comparison is
between two surges rather than between two query shapes.

## Watch the recovery margin here

The verdict allows recovery to sit 5x above baseline. Against this cell's 14ms
that is a band of some 70ms in absolute terms, where the same rule around
`active-users`' 124ms baseline is half a second wide. The measured rounds land
nowhere near it — recovery returns to within 1.5% of baseline, not to 500% — so
the margin is not as fragile as its width suggests. It stays worth knowing:
this is the one cell where a red verdict could plausibly be jitter rather than a
tail, so read `recovery_failed_rate` and `spike_dropped` before concluding
anything from a lone row.
