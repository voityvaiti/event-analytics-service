# Read testing — `/api/v1/stats` latency

Measures analytics query latency against a populated database to detect performance regressions.

## Layout: workload, then endpoint

```
read/
  load/                     steady-state latency
    stats-read.js           the scenario all three load cells run
    measure-cell.sh         the measuring routine all three share
    event-counts/  active-users/  top-pages/
  spike/                    surge and recovery
    stats-spike.js          the scenario all three spike cells run
    measure-cell.sh         the measuring routine all three share
    event-counts/  active-users/  top-pages/
```

As in [`write/`](../write), cells are grouped by workload, then endpoint. Each workload shares a scenario and measurement routine. Load measures a steady window; spike measures phases and recovery. Both use the shared harness.

## What a load run does

[`lib/query-generator.js`](../lib/query-generator.js) varies:

- Window size: a mix of 1h, 1d, 7d, and 30d, favouring short windows.
- Position: a 1/rank distribution favouring recent data but reaching the corpus start.

Both depend only on iteration number, so A/B runs use identical query sequences.

## Rounds

`ROUNDS` defaults to 1. Repeated load cells report `p95_ms` spread; `event-counts` groups it by `group_by` to avoid mixing different query plans. Spike cells repeat without reporting spread. See the [suite README](../README.md).

## Reading a load journal row

Rows include CPU, cores, pool, schema, `start_rows`, `request_metrics`, and `scrape`, plus:

- `windows`: latency by window size, exposing regressions hidden by a mixed overall percentile.
- `index_scans` / `seq_scans`: PostgreSQL scan counts during actual app queries. Zero index scans with an index present means the planner declined it.
- `index_scans_by_index`: counts per index; `index_scans` is their sum, retained for compatibility with older rows.

Spike-specific fields are described in [read/spike](./spike).

## Why the corpus matters here

The [index experiment](../index-experiment.md) got sub-millisecond reads on an empty table with and without indexes. A seeded corpus makes index effects measurable. Windows cover part of the corpus; broad windows can favour sequential scans. See [corpus setup](../README.md#the-corpus).
