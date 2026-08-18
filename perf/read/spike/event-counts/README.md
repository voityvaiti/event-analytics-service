# Read spike — `event-counts`

Surges `GET /api/v1/stats/event-counts` with `groupBy=type`. How a spike is
applied, judged and journalled is the same for every cell here and is described
[one level up](..); this page is the query.

## The lightest read, so the highest ceiling

`groupBy=type` is answered entirely out of `idx_events_source_occurred_at`: the
tenant equality and the range predicate drive its leading columns, the grouping
key is its trailing `event_type`, and counting needs nothing else — `EXPLAIN`
reports `Heap Fetches: 0`. At a 1d `p95` of 12.5ms it serves ~800 req/s where
`active-users` serves ~81, so this cell steps to 4000 req/s to overload it at all.

That is the whole reason it exists. It is the far end of the range from
`active-users`, and it asks whether a shape that costs a tenth as much per query
also drains a burst — or whether what a surge leaves behind depends on the depth
of the queue rather than on the price of the query.

The answer is the price of the query, and this cell has now demonstrated it in
both directions. Before tenancy all three rounds recovered, shedding ~98 000
requests during the surge and returning to within 0.2ms of a 14.4ms baseline.
Scoping reads to a tenant took the index-only scan away — `source` was not in the
V2 index, so checking it cost a heap visit per row — and the same surge served
~260 req/s instead of ~717, leaving recovery at ~655ms: the verdict flipped to
`STILL DRAINING` on per-query cost alone, with queue depth and overload
unchanged. The tenant-led index restored both, ~693 req/s and 15.2ms, in all
three rounds.

Its siblings recovered in none of the three states, so read this row beside
theirs at the same corpus.

The three states also test the ceiling model the sibling cells are rated by —
pool size over the 1d `p95`. It predicted 837, 241 and 803 req/s against 717,
260 and 693 served: tight where the cell is overloaded most, ~15% high at the
top, and correct about every ordering.

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
