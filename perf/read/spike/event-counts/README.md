# Read spike — `event-counts`

Surges `GET /api/v1/stats/event-counts` with `groupBy=type`. See [shared spike methodology](..) for execution, verdicts, and journal fields.

## The lightest read, so the highest ceiling

The tenant-led index covers the tenant filter, time range, and `event_type` grouping (`Heap Fetches: 0`). A measured 1d p95 of 12.5 ms implies ~800 req/s, versus ~81 for `active-users`; this cell surges at 4,000 req/s.

Plan changes altered recovery with the same client concurrency and offered rate:

- Before tenancy: all three rounds recovered within 0.2 ms of a 14.4 ms baseline, dropping ~98,000 requests during the surge.
- Tenant filtering under V2 required heap fetches: throughput fell from ~717 to ~260 req/s, and recovery rose to ~655 ms (`STILL DRAINING`).
- The tenant-led index restored ~693 req/s and 15.2 ms recovery in all rounds.

Its siblings recovered in none of the three states, so read this row beside theirs at the same corpus.

The three states also test the ceiling model the sibling cells are rated by — pool size over the 1d `p95`. It predicted 837, 241 and 803 req/s against 717, 260 and 693 served: tight where the cell is overloaded most, ~15% high at the top, and correct about every ordering.

## The other groupings are not this cell

`hour` and `day` use `date_trunc` and sorting, costing about 2.5x more (~350 req/s ceiling versus ~840). If using `GROUP_BY=hour`, re-derive `SPIKE_RATE`: 4,000 req/s would be ~11x overload rather than ~5x.

## Watch the recovery margin here

The 5x recovery bound is ~70 ms at this cell's 14 ms baseline, versus about half a second for `active-users`. Measured recovery was within 1.5% of baseline. For an isolated failure, also inspect `recovery_failed_rate` and `spike_dropped` to distinguish jitter from backlog.
