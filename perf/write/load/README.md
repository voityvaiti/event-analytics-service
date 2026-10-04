# Write load — steady-state ingest throughput

Measures persisted events per second, with one cell per request shape:

| Cell | Request shape |
|---|---|
| [`single/`](./single) | `POST /api/v1/events` — one event per request |
| [`batch/`](./batch) | `POST /api/v1/events/batch` — 100 events per request |

Each cell owns a journal and uses the shared [`measure-cell.sh`](./measure-cell.sh). CI renders comparisons with [`compare-runs.mjs`](../../lib/compare-runs.mjs).

## Two ways the numbers are used

- **Journal:** actions under `scripts/actions/perf/write/load/` append absolute measurements. Compare rows only within a fixed rig and configuration.
- **CI:** `.github/workflows/perf.yml` compares `main` and the PR on the same runner. It reports relative deltas and does not write journal rows.

## Why the numbers only mean something with their config

Every row records the configuration needed for comparison:

- **`start_rows`:** actual starting corpus size. Primary-key insert cost grows with table size. Each run removes its own writes afterwards. Historical rows with `start_rows: 0` belong to the pre-seeding series.
- **Pool size:** caps concurrent JDBC writes (default **10**) regardless of VUs. Set `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20` when launching with `scripts/actions/perf/app`. The harness reads the live value from Actuator.
- **`scenario`:** `ingest-single` or `ingest-batch`, stamped by the scenario.
- **`batch_size`:** reported by batch scenarios, alongside derived `events` and `events_per_sec`. Single-event rows omit these because `requests` and `throughput_rps` already represent events and event rate.
- **`duration`:** a constant-VU measurement after a separate warm-up. Longer windows can capture more rare pauses; compare matching durations.
- **`ingest_path`:** `sync` means `202` follows persistence. With buffering, throughput measures acceptance before persistence. Set `INGEST_PATH` manually; the app does not expose this value yet.
- **`schema_version`:** latest applied Flyway migration, read from the database; identifies schema changes that may affect write cost.
- **`request_metrics` / `scrape`:** instrumentation state; see the [suite README](../../README.md#running).

## Throughput, and which throughput

Throughput is the comparison metric; latency percentiles provide context. Use events/s across request shapes and requests/s within a fixed shape. `single/` reports spread over `throughput_rps`; `batch/` uses `events_per_sec`.

Measure noise separately for each cell. The ~6% single-event floor does not apply to batches; see [the measured floor](../../README.md#the-measured-floor).

## Running

Requires Docker and the app running through `scripts/actions/perf/app`. The harness starts Compose dependencies and runs k6 in its pinned container (`K6_IMAGE`, default `grafana/k6:0.50.0`). See [suite setup](../../README.md#running).

```bash
# Start the app with scripts/actions/perf/app (IDE: PERF - App).
# The action brings backing services up itself.

scripts/actions/perf/write/load/single      # one event per request
scripts/actions/perf/write/load/batch       # 100 events per request
scripts/actions/perf/write/load/all         # every write load cell

# Tunables via env, e.g. push past the pool to see the saturation knee:
VUS=20 DURATION=120s scripts/actions/perf/write/load/single

# Rounds. Default 1. Ask for more when the number is going to be compared against
# something: one row per round, plus the spread across them. See the suite README.
ROUNDS=3 scripts/actions/perf/write/load/all

# Corpus knobs. An intact corpus is reused between runs; SEED_FORCE=1 rebuilds it,
# which is required after changing the event generator.
SEED_ROWS=5000000 scripts/actions/perf/write/load/single
SEED_FORCE=1 scripts/actions/perf/write/load/single
```

The raw k6 summary of the last run lands in `perf/write/load/last-summary.json` (gitignored).
