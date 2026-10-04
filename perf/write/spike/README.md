# Write spike — surge and recovery

Measures overload and recovery for each ingest request shape:

| Cell | Request shape | Surge |
|---|---|---|
| [`single/`](./single) | `POST /api/v1/events` — one event per request | 8000 req/s |
| [`batch/`](./batch) | `POST /api/v1/events/batch` — 100 events per request | 2500 req/s |

The k6 scenarios sit here beside [`measure-cell.sh`](./measure-cell.sh), the routine that applies a surge and judges it. A run is three back-to-back phases, each a k6 scenario so its metrics are tagged and read separately:

1. **baseline** — a low rate well under capacity, to establish the healthy number.
2. **spike** — a sudden step to `SPIKE_RATE`, held for `SPIKE_SECONDS`, then off. No ramp: the point is the shock.
3. **recovery** — back to the baseline rate, to see whether the app returns to healthy service after the surge.

## Open model, on purpose

k6's `constant-arrival-rate` executor offers a fixed request rate independent of server latency. A constant-VU model instead slows its arrivals as requests queue.

Set `SPIKE_RATE` above the cell's measured load throughput. Request rates differ by shape; compare cells using `spike_achieved_events_per_sec`.

## What the app does under surge — and what this asserts

Synchronous ingestion has no explicit backpressure. Excess requests wait for a Hikari connection (default timeout 30s), raising latency before producing `500`s. The surge is observed, not gated; it may drop work or return errors.

Recovery is the hard assertion:

1. Baseline p95 must satisfy the cell's `baseline_max_p95_ms`.
2. Recovery failure rate must be below 1%.
3. Recovery p95 must be within 5x baseline p95.

The baseline check prevents a saturated baseline from making recovery look healthy. The 5x margin tolerates jitter, which can roughly double baseline latency.

`dropped_iterations` means k6 exhausted `MAX_VUS` and could not issue the offered rate. Raise `MAX_VUS` to increase pressure on the server.

## The journal

Each cell has a separate journal stamped with CPU, cores, pool, schema, rates, `baseline_max_p95_ms`, `start_rows`, `request_metrics`, and `scrape`.

`recovered` is derived from `baseline_p95_ms`, `recovery_p95_ms`, `recovery_failed_rate`, and `baseline_max_p95_ms`. The `commit` identifies the rule version, including older rows that predate a condition.

Spike cells report no spread because recovery combines several metrics; see [the suite README](../../README.md#rounds-and-the-noise-floor).

## Running

```bash
# App must be running on the host; the action brings backing services up itself.
scripts/actions/perf/write/spike/single      # one event per request
scripts/actions/perf/write/spike/batch       # 100 events per request
scripts/actions/perf/write/spike/all         # every write spike cell

# Tunables via env (each cell's defaults are sized for its own ceiling):
SPIKE_RATE=12000 SPIKE_SECONDS=45 MAX_VUS=2000 scripts/actions/perf/write/spike/single
```

Tunables: `SPIKE_RATE` (per cell), `BASELINE_RATE`, `BASELINE_SECONDS` (20), `SPIKE_SECONDS` (30), `RECOVERY_SECONDS` (30), `MAX_VUS` (1000). The raw k6 summary of the last run lands in `perf/write/spike/last-summary.json` (gitignored).

Not run in CI: see the [suite README](../../README.md#what-runs-in-ci) for why the spike is local / journalled only.
