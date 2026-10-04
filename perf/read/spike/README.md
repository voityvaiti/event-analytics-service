# Read spike — surviving a burst of dashboard traffic

Measures overload and recovery per endpoint using [`stats-spike.js`](./stats-spike.js) and [`measure-cell.sh`](./measure-cell.sh). Cell READMEs cover the query-specific results.

## Why one cell per endpoint

At 20M rows, per-query cost varies 11x across endpoints. Capacity is approximately pool size divided by query latency, so endpoints saturate at different rates and recover differently. The [index experiment](../../index-experiment.md) established why testing only the heaviest query was insufficient.

## Each cell surges at ~5x its own ceiling

Rates target roughly 5x each endpoint's capacity at the reference corpus:

| Cell | Query | 1d `p95` | Ceiling | `SPIKE_RATE` |
|---|---|---|---|---|
| [`event-counts/`](./event-counts) | `groupBy=type` | 11.9 ms | ~840 req/s | 4000 |
| [`top-pages/`](./top-pages) | `limit=10` | 32.6 ms | ~310 req/s | 1500 |
| [`active-users/`](./active-users) | `groupBy=day` | 126 ms | ~79 req/s | 400 |

Ceilings use pool size 10 divided by the indexed 1d-window p95 from each load journal. The index experiment checked predictions within 2.7% and 6.1% at this density. Rounded rates give 4.8x, 4.9x, and 5.1x overload; `active-users` retains its historical 400 req/s rate.

Pool, corpus, and query-plan changes can invalidate these rates. If `spike_achieved_rps` approaches `spike_rate` with `spike_dropped: 0`, re-derive the rate: the run did not overload the service. Older rows diluted achieved rates across the entire run; identify them by `commit`.

## What the first rows measured

Medians of three rounds each, at 20M rows with the index:

| Cell | Baseline `p95` | Sustained | Predicted ceiling | Recovery `p95` | Verdict |
|---|---|---|---|---|---|
| `event-counts` | 14.4 ms | 717 req/s | ~840 | 14.4 ms (1.0x) | recovered |
| `top-pages` | 30.9 ms | 284 req/s | ~310 | 447 ms (14.5x) | STILL DRAINING |
| `active-users` | 124 ms | 84 req/s | ~79 | 6.56 s (53x) | STILL DRAINING |

Measured rates were within 15% of the predicted ceilings. At roughly 5x overload, `event-counts` recovered, while the more expensive queries left a tail.

`top-pages` is closer to recovery than `active-users`. A statement timeout did not resolve it: with the timeout, recovery was 441.8 ms against a 30.6 ms baseline, still ~14x.

## What is deliberately equal across the cells

Only query and surge rate vary:

- **`BASELINE_RATE` 20 req/s:** below every ceiling, providing an unqueued reference.
- **`MAX_VUS` 500:** equal client concurrency bounds queue depth. At 20M rows, `active-users` served 2,535 and dropped 9,459 of 12,000 offered requests. Drops are client-side; changing VUs changes the backlog being measured.
- **1d window:** fixes query size while varying traffic. Position still changes to avoid repeatedly querying one cached slice.

Warm-up runs once per process on the first cell. Later cells may use a different query, but any cold-query cost appears in baseline before the surge.

## Reading a row

Each cell's task writes its own journal; keep it separate from other cells and load journals. Phases are `baseline`, `spike`, and `recovery`.

`recovered` requires low `recovery_failed_rate`, recovery p95 within 5x baseline, and baseline p95 under `BASELINE_MAX_P95_MS` (1000 ms). The surge may shed work; `spike_dropped` counts requests k6 could not issue.

The baseline precondition matters: the experiment's unindexed 20M arm had a 16.7s baseline and 29.6s recovery. Their 1.8x ratio passes a 5x rule despite both being unhealthy. The indexed arm's 6.3s recovery against 124 ms correctly fails. Use `commit` to identify older verdicts predating the precondition.

`index_scans` and `seq_scans` distinguish index use from full-table scans.

## Why none of them gate

Read cells journal verdicts without failing the action because some retain a tail at the default corpus. Write spikes recover and do gate.

A 10s `statement_timeout` changed none of the nine verdicts across three rounds per cell, and no phase returned 503. The tail comes from waiting for connections. A future queue bound needs a revised recovery rule: fast 503 shedding should not be confused with a queue that fails to drain.

Results depend on corpus size. At 2M rows the indexed `active-users` arm absorbed 400 req/s and recovered to its 13 ms baseline; at 20M it did not recover with or without the index.
