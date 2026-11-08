# Performance suite

Tests are grouped by path, workload, and request shape or endpoint. Each leaf is a measurement cell.

| Path | Cell | Question it answers |
|------|------|---------------------|
| Write | [`write/load/single/`](./write/load/single) | Single-event ingest throughput |
| Write | [`write/load/batch/`](./write/load/batch) | Batch ingest throughput and per-request overhead |
| Write | [`write/spike/single/`](./write/spike/single) | Single-event surge and recovery |
| Write | [`write/spike/batch/`](./write/spike/batch) | Batch surge and recovery |
| Write | [`write/steady/single/`](./write/steady/single) | Whether the consumer persists a fixed offered rate as it arrives |
| Write | [`write/restart/single/`](./write/restart/single) | Whether a 60 s consumer outage drains within its downtime |
| Read | [`read/load/event-counts/`](./read/load/event-counts) | Event-count latency by grouping |
| Read | [`read/load/active-users/`](./read/load/active-users) | Distinct-user count latency |
| Read | [`read/load/top-pages/`](./read/load/top-pages) | JSONB page-ranking latency |
| Read | [`read/spike/event-counts/`](./read/spike/event-counts) | Event-count surge and queue recovery |
| Read | [`read/spike/active-users/`](./read/spike/active-users) | Active-user surge and queue recovery |
| Read | [`read/spike/top-pages/`](./read/spike/top-pages) | Page-ranking surge and queue recovery |
| Mixed | [`mixed/spike/active-users/`](./mixed/spike/active-users) | Write acceptance and consumer lag during a read surge |

Each cell owns a `journal.jsonl` stamped with its machine and configuration; compare only compatible runs. Cell READMEs explain individual results. Shared methodology lives in [`write/load`](./write/load), [`write/spike`](./write/spike), [`write/steady`](./write/steady), [`write/restart`](./write/restart), [`read`](./read), and [`read/spike`](./read/spike).

## Running

Prerequisites: Docker and the app started with **`scripts/actions/perf/app`**. The harness rejects `scripts/actions/start` and `./gradlew bootRun` because `-XX:TieredStopAtLevel=1` disables C2 compilation. On the same commit, packaged throughput was **126.0k events/s versus 107k under bootRun**; reads were unchanged because query cost dominates them.

The launcher stamps the build commit beside the jar. The harness reads that stamp through the running process, so switching the checkout cannot mislabel a measurement:

- Changes to inputs read by `bootJar` produce `<sha>-dirty`.
- A non-local `BASE_URL`, whose process cannot be inspected, produces `unknown`.
- A local jar without a stamp fails the run, as does a stamp newer than the process. The latter can occur if a second launcher rebuilds before finding the port occupied.

Measure after the final rebase onto `main`, then only add commits until merge. Changes landing on `main` can force another rebase and rewrite measured commits. GitHub retains original commits through PR heads only if the final head still contains them. CI's `scripts/actions/perf/check-journal-commits` rejects dirty stamps and commits unreachable from a GitHub branch, tag, or PR head. Push new commits; if rewritten, tag and push the original. **Never change a journal stamp.**

Two fields record instrumentation automatically after each run:

- `request_metrics`: whether the app publishes `http.server.requests`.
- `scrape`: whether Prometheus scraped during the measured window. Starting the stack later does not change that window's status; a stopped stack means `off`.

The [local stack](../README.md#observability) scrapes every 2s. Rows predating these fields were measured without it.

A `202` means the broker holds the events, not the table, so write and mixed cells wait for the consumer before they count or delete anything: the harness polls `persistence.consumer.lag` until it reads 0 and fails after `DRAIN_TIMEOUT_SECONDS` (default 300). It then reconciles the measured run. k6 counts the events answered 202 and logs the ID of every other event to `last-unaccepted.log`; the run's rows, less those IDs, must equal the accepted count, because a request k6 gave up on may still have been stored. Rows record `accepted_events`, `missing_events` (anything but 0 fails the cell) and `drain_seconds`, the wait after k6 exited.

Steady, restart and mixed cells also sample the lag once a second while k6 runs (`start_lag_sampler`, read by `lib/lag_samples.py`) and judge it against one second of intake. Each run's curve goes to `lag.jsonl` beside the journal.

k6 and the seeder run in pinned containers (`K6_IMAGE`, default `grafana/k6:0.50.0`, and `NODE_IMAGE`). The shared startup script brings up Compose dependencies. Requests use bearer tokens; see [Tenants and tokens](#tenants-and-tokens).

IDE configurations in `.run/` wrap the actions below. Use _PERF - App_ to start the app; _APP - Start_ is for development.

```bash
scripts/actions/perf/write/load/<shape>       # one request shape's throughput
scripts/actions/perf/write/load/all           # every write load cell
scripts/actions/perf/write/spike/<shape>      # one request shape's surge
scripts/actions/perf/write/spike/all          # every write spike cell
scripts/actions/perf/write/steady/<shape>     # one fixed offered rate, lag judged
scripts/actions/perf/write/steady/ladder      # climb steady rates until the consumer falls behind
scripts/actions/perf/write/restart/<shape>    # the consumer stopped for 60 s under intake
scripts/actions/perf/write/all                # every write cell, every workload
scripts/actions/perf/read/load/<endpoint>     # one read endpoint's latency
scripts/actions/perf/read/load/all            # every read load cell
scripts/actions/perf/read/spike/<endpoint>    # one read surge
scripts/actions/perf/read/spike/all           # every read spike cell
scripts/actions/perf/read/all                 # every read cell, both workloads
scripts/actions/perf/mixed/spike/<endpoint>   # steady ingest under one read surge
scripts/actions/perf/mixed/all                # every mixed cell
scripts/actions/perf/all                      # everything, one combined digest
scripts/actions/perf/journals                 # browse every journal on a local page
```

Each cell appends its rows and prints them. Eyeball them, then commit the journals yourself — the tasks never commit for you. `scripts/actions/perf/journals` shows them as a table per cell on `127.0.0.1:8765`, filtered by commit, ingest path and the cell's dimensions; a row opens every field and, for lag-sampling cells, its curve with phase bands and the lag bound.

### Kafka settings and saturation

Every write and mixed row records the Kafka settings the running app used:

- `linger_ms` comes from the app's command line, `--spring.kafka.producer.properties.linger.ms=<ms>`, else from the `application.yaml` packaged in the jar it runs. It is null only when neither sets it and the client's default decides. Setting linger any other way fails the run, because the row could not say what the producer used.
- `consumer_concurrency` and `partitions` come from the app's consumer metrics: how many persistence consumers run, and how many partitions they hold between them.

Steady and restart cells also sample what each part of the rig is doing once a second (`start_saturation_sampler`, `lib/saturation_samples.py`). Steady rows summarise the measure phase in `measure_saturation`, and restart rows the drain after the restart in `drain_saturation`. Load cells sample only with `SATURATION=on`, so their throughput series carries no sampling cost. `SATURATION=off` turns it off anywhere, to measure what the sampling costs. Each run's per-second curve goes to `saturation.jsonl` beside the journal.

- **CPU** is in cores, so 1.0 is one whole core and the host's ceiling is `cores`: `host_cores`, `app_cores`, and `postgres_cores`, `kafka_cores` and `k6_cores` from each container's cgroup. Each has its mean and highest second. `sampler_cores` is the sampler's own cost.
- **`consumer_busy`** is the share of the time each consumer spent processing, from the `spring.kafka.listener` timer, for the busiest consumer and on average. The listener runs on a virtual thread, so no thread's CPU shows it: near 1.0 means the consumer is the limit, whether it computed or waited for Postgres.
- **`disk_busy`** is the share of the time the Docker disk was busy. `postgres_written_mb_per_sec` and `kafka_written_mb_per_sec` show what each container wrote.
- **Pool:** `pool_active`, `pool_pending` and `pool_acquire_wait_ms`.
- **`postgres_sessions`**: the average number of sessions on CPU, waiting by wait class (`IO`, `Lock`, `LWLock` and others), or idle inside a transaction.

Read a limit off these figures in order:

1. A part near its ceiling is the limit.
2. If the whole host is near `cores`, the machine is: k6 competes for the same cores, so the figure describes the rig, not the service.
3. If nothing is near a ceiling but lag grows, the limit is waiting. Look at a busy consumer, pending connections, and sessions waiting on `Lock` or `IO`.

Steady and restart rows also record `start_index_mb`, the events indexes' size before the run. Heavy write runs grow it (see [The floor between runs](#the-floor-between-runs)), and later steps of a series inherit the growth.

### Experiments

`JOURNAL` points the steady, restart and single-event load cells at another journal, so an experiment's rows never mix with a cell's series. `scripts/actions/perf/write/steady/ladder` climbs the steady stream through `LADDER_RATES` and stops at the first rate whose verdict is not `kept up`, writing to `perf/experiments/async-capacity/ladder/<single|batch>/` by default. `BATCH_SIZE` above 1 sends batches. The ladder caps `MAX_VUS` at 4,000, because the steady cell's 1.1 VUs per request/s would need tens of thousands at the top rates. A rate k6 cannot fully offer then ends the ladder as `RATE NOT OFFERED`, not as the server's limit. Results are in [the async write capacity experiment](./async-capacity-experiment.md).

## Rounds and the noise floor

Set `ROUNDS` to repeat each cell (default **1**). Repeated load cells report:

- **Coefficient of variation:** how tightly the rounds cluster.
- **Peak-to-peak:** the gap between best and worst rounds; use this wider bound when comparing one run on each side.

Rounds run consecutively, each with its own journal row. Spread is calculated from those rows and is not stored. Use multiple rounds for comparisons: two runs of unchanged commit `aadd201` measured 4235.6 and 4061.8 events/s (4.19% apart), while another pair differed by 0.40%. A delta below this noise floor is not a measured effect.

The [observability experiment](./observability-overhead.md) shows why pairing nearby measurements matters: apparent effects in pooled medians changed sign when paired.

### The measured floor

Established by the [index experiment](./index-experiment.md), which ran ten three-round passes of every cell on this rig — two arms across five corpus densities:

| Regime | Peak-to-peak over 3 rounds |
|---|---|
| Write load, `throughput_rps`, one event per request | 1.4% – 4.8% |
| Write load, `events_per_sec`, 100 events per request | 0.8% |
| Read load, `p95_ms`, served from the index | 0% – 1.4% |
| Read load, `p95_ms`, sequential scan over gigabytes | 1.6% – 10.0% |
| Read load, `p95_ms`, sequential scan over megabytes | 0% – 1.0% |
| Read load, `p95_ms`, empty table | 0% – 4.6% |

Use **~6%** for single-event writes. Pooling 30 rounds gave 5.84%, while the ten individual three-round passes ranged from 1.35% to 4.76%. More samples can reveal wider extremes, so the table is a lower bound. The batch figure comes from one three-round pass; batching reduces the share of request overhead that jitters.

The floor depends on workload: indexed reads varied by at most 1.4%, gigabyte scans by 10%, and megabyte scans by 1%. Empty-table reads took 0.37–0.46 ms, where 0.01 ms rounding alone is ~2%. Do not transfer floors between regimes.

Spike cells repeat but report no spread: recovery combines service and queue drainage, so no single scalar represents it. Individual inputs vary differently: read `spike_achieved_rps` and `spike_dropped` stayed within 1.2%, while write `spike_achieved_rps` varied by 9.2% and `baseline_p95_ms` by 92%. The recovery rule allows a 5x margin for baseline jitter.

CI uses a separate, wider `NOISE_PERCENT` band (default 10) in `compare-runs.mjs`, reflecting shared-runner noise.

### What CI compares

The `perf` label compares both write load shapes and all five read load shapes against `main` on one runner. By default it reports medians of three rounds, alternating which branch runs first. Each round rebuilds both sides with a fresh schema, corpus, and throwaway warm-up. Reads run before writes to avoid index bloat from inserts and cleanup.

CI seeds 2M rows over 180 days, one tenth of the reference corpus density. Compare only the two sides of that CI run, never a CI number against a local journal.

Only throughput and overall p95 receive verdicts. p99 and per-window deltas are informational: identical jars differed by 45% in batch p99 and 10% in the narrowest read window. CI runs k6 scenarios directly, bypassing the harness.

### The floor between runs

Within-pass spread understates differences between whole-suite runs. In the tenant-timezone experiment, unchanged `top-pages` queries slowed 1.1–2.2% across all windows, while the unchanged write path moved ±4.4%.

`perf/all` runs writes before reads. Inserts and cleanup grew the index from 1060 MB after `REINDEX` to 1148 MB before reads: **8.3% more index for the same 20M rows**. `VACUUM ANALYZE` does not remove this bloat; `REINDEX` does. Reusing an intact corpus skips even `VACUUM`.

For comparisons:

- Prefer controls measured in the same pass. Compare how the gap between two shapes changes across passes; a raw gap also includes their aggregation differences. This estimated the tenant-zone lookup at ~0.15 ms.
- For separate-pass read comparisons, `REINDEX` first and run reads alone, or allow several percent of noise instead of the table's ~1%.

## The corpus

All cells start with `SEED_ROWS` rows (default 20M), spread over `SEED_SPREAD_DAYS` from `SEED_ANCHOR`. Empty tables hide index effects: the [index experiment](./index-experiment.md) measured sub-0.5 ms sequential scans with and without an index. Set `SEED_ROWS=0` only to test that regime explicitly.

The suite seeds once. Reads leave the corpus intact; writes use a separate tenant and delete their posted rows afterwards. An intact corpus is reused between runs. Set `SEED_FORCE=1` after changing `lib/event-generator.js`: reuse checks count rows but cannot detect changed event contents.

## Tenants and tokens

Rows use the token's tenant claim (`tenant_name`, formerly `source`). Two tokens separate the corpus from test writes:

| token | tenant | used by |
|-------|--------|---------|
| `SEED_TOKEN` | `perf-seed` | every read cell, and the read warm-up |
| `WRITE_TOKEN` | `perf-test` | every write cell, load and spike |

`perf_bootstrap` mints both tokens once with `lib/mint-token.mjs`, using RS256, the [dev key](../dev-keys), and Node's built-in crypto in the seeder's pinned image. Each cell passes `-e TOKEN=…` explicitly; `k6_run` does not inherit it.

Swapping tokens silently invalidates results: writes under `perf-seed` escape write-tenant cleanup, while reads under `perf-test` query an empty dataset. The seeder uses `COPY` directly and needs no token.

Tokens omit `exp` to avoid expiry appearing as a load failure; Spring validates expiry when the claim is present.

## Layout

```
perf/
  lib/
    harness.sh          shared shell harness: bootstrap + seed/k6/db/actuator helpers
    mint-token.mjs      signs the RS256 token a cell authenticates with
    k6-ingest.js        shared /api/v1/events request shape
    event-generator.js  the event bodies every scenario and the seeder produce
    query-generator.js  the questions the read scenarios ask
    seed-corpus.mjs     emits the fixed corpus as CSV for COPY
    seq-space.js        which sequence numbers each producer may draw from
    k6-stats.js         shared /api/v1/stats request shape
    k6-summary.js       shared k6 summary reader
    compare-runs.mjs    the main-vs-PR comparison CI renders, over every load cell
    annotate-runs.sh    puts the window each row was measured in on the dashboard
    lag_samples.py      reads the once-a-second lag samples the harness takes
    saturation_samples.py  samples and reads what each part of the rig is doing
  viewer/
    server.py           serves the journals read-only to the page, 127.0.0.1 only
    index.html          the page: tables, filters, row details, lag curves
  write/
    tests.sh            the write cell list, per workload and combined
    load/
      ingest-events.js  the steady scenario, one event per request
      ingest-batches.js the steady scenario, BATCH_SIZE events per request
      measure-cell.sh   the measuring routine the load cells share
      single/  batch/
    spike/
      spike-events.js   the surge scenario, one event per request
      spike-batches.js  the surge scenario, batches at a surging request rate
      measure-cell.sh   the routine the spike cells share, verdict included
      single/  batch/
    steady/
      ingest-at-rate.js the open-model scenario, single events or batches in named phases
      measure-cell.sh   the routine the steady cells share, verdict included
      single/
    restart/
      measure-cell.sh   stops and restarts the consumer under the steady scenario
      single/
  read/
    tests.sh            the read cell list, per workload and combined
    load/
      stats-read.js     the latency scenario, endpoint and grouping via env
      measure-cell.sh   the measuring routine the load cells share
      event-counts/  active-users/  top-pages/
    spike/
      stats-spike.js    the surge scenario, endpoint and rate via env
      measure-cell.sh   the measuring routine the spike cells share
      event-counts/  active-users/  top-pages/
  mixed/
    tests.sh            the mixed cell list, per workload and combined
    spike/
      ingest-under-read-spike.js  steady writes beside the read surge
      measure-cell.sh   the measuring routine the mixed spike cells share
      active-users/
```

Write and read paths split by workload, then request shape or endpoint. Mixed cells split by the endpoint being surged. Each leaf contains `measure.sh`, `journal.jsonl`, and `README.md`.

Scenarios and measurement routines live at workload level. Read cells share a scenario; write shapes need separate scenarios for their request bodies. All cells within a workload use the same measurement routine.

- `lib/harness.sh`: dependencies, app/image checks, seeding, drain, reconciliation, cleanup, saturation sampling, and stamps for pool, Kafka settings, schema, CPU, running build, metrics, and scraping.
- `lib/k6-ingest.js`: shared ingest request contracts.
- `lib/event-generator.js`: event contents for scenarios and the corpus.
- `<workload>/measure-cell.sh`: warm-up, measurement, journal, and digest entry; expects the harness to be sourced.
- `<cell>/measure.sh`: a `perf_<cell>` function delegating with the cell's journal, scenario, and defaults.

## Adding a cell

Use `<path>/<workload>/<cell>/` and a matching function name, e.g. `read/load/top-pages/` → `perf_read_load_top_pages`.

1. Add `measure.sh`, an empty `journal.jsonl`, and a README explaining results. Reuse a scenario when only the request changes; place new scenarios at the workload level.
2. Copy a matching action under `scripts/actions/perf/`: source the harness and cell, bootstrap, run, report. Adjust its `cd` depth to the repository root.
3. Add `.run/PERF - <Name>.run.xml` only for a new path or workload. Individual cells run through their action or the workload's `all` action.
4. Source the cell in its path's `tests.sh` and add it to the workload array. The single-cell action also names its entry inline.
5. Optionally add `perf_<cell>_spread`, delegating to `perf_spread` with the journal, metric, and grouping field if a round writes multiple rows. Skip this for compound results such as spike recovery.

## What runs in CI

The per-PR comparison (`.github/workflows/perf.yml`) runs every load cell; see [What CI compares](#what-ci-compares). CI writes no journal rows.

Spike, steady, restart and mixed cells run locally: shared runners cannot reliably sustain their offered rates, and their verdicts are compound. Their journals track regressions on a fixed rig.
