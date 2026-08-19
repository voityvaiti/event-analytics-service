# Write load — steady-state ingest throughput

Tracks how much the ingest path can persist per second across development
checkpoints, so a regression shows up as a number, not a surprise. One cell per
request shape, measured identically:

| Cell | Request shape |
|---|---|
| [`single/`](./single) | `POST /api/v1/events` — one event per request |
| [`batch/`](./batch) | `POST /api/v1/events/batch` — 100 events per request |

Each cell owns its `journal.jsonl`; the k6 scenarios sit here beside
[`measure-cell.sh`](./measure-cell.sh), the measuring routine they share, because
the cells differ in what they post and not in how they are measured.

The main-vs-PR renderer CI uses is [`perf/lib/compare-runs.mjs`](../../lib/compare-runs.mjs),
not a file at this level: it covers every load cell, read ones included, so it
belongs to no single workload.

## Two ways the numbers are used

- **Absolute journal** — one row per measurement, written only by the
  `PERF - Write Load` task (`scripts/actions/perf/write/load/…`, never by hand).
  Answers "where are we, and are we drifting over time?" Only comparable within a
  fixed measurement rig, so every row self-stamps its CPU and core count — a
  number from a different machine is a different series, not a regression.
- **Per-PR comparison** — the `Performance comparison` GitHub workflow
  (`.github/workflows/perf.yml`) builds `main` and the PR branch and load-tests
  both back-to-back on the same runner. Answers "did this PR change throughput?"
  It reports a *relative* delta, so it is robust to the runner's variable
  hardware — and writes nothing to the journal, because a shared CI runner is
  not a fixed rig.

## Why the numbers only mean something with their config

Two things dominate the result and silently invalidate cross-run comparison if
they drift. Both are recorded with every journal row.

- **Starting row count.** Inserts hit the `event_id` primary-key B-tree, and
  PK-insert cost rises with row count. Every cell starts from the same seeded
  corpus rather than an empty table — the fixed point moved from 0 to `SEED_ROWS`,
  which is both comparable and closer to production. A run tags its own rows and
  deletes exactly them afterwards, restoring the corpus; `start_rows` records the
  size it actually measured against. Rows journalled before seeding landed
  honestly say `start_rows: 0` and are the earlier series.
- **Hikari connection pool size.** Blocking JDBC on virtual threads means each
  in-flight insert holds one pooled connection; the default max pool is **10**.
  DB-side write concurrency is capped there regardless of VU count. To vary it,
  set it when you start the app
  (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20 ./gradlew bootRun`); the harness
  reads the running app's actual pool from its actuator metrics and records that,
  so the journalled pool is always the one the run used — it is never passed in.

Five more columns keep distinct series from being read as one trend:

- **`scenario`** — the request shape (`ingest-single`, `ingest-batch`). Each cell's
  k6 scenario stamps its own, so one shape's number is never read against
  another's as a series.
- **`batch_size`** — events per request, reported by the scenario rather than
  passed in. It is the factor between the row's `throughput_rps` and its
  `events_per_sec`, and it appears only in the rows of a cell that posts batches,
  together with those two derived fields. A single-event row carries none of the
  three: `requests` is already its event count and `throughput_rps` already its
  event rate, and a second copy of each under another name is a field that can go
  stale against itself.
- **`duration`** — the measured window. The measured run holds a constant VU count
  for the whole window — no ramp stages, the throwaway warm-up pass does the
  warming — so every metric is steady-state. Longer windows give rare events (GC
  pauses) more chance to land in the tail, so a `60s` number and a `30s` number
  are different series, not a drift.
- **`ingest_path`** — write-path semantics: `sync` while the `202` follows the
  DB write, so throughput is genuine write throughput. When a buffer fronts the
  write (Kafka, or in-process coalescing) the `202` returns before the row lands
  and the same number measures intake, not persistence — set `INGEST_PATH` so
  the two stay separate series. The one field not read from the app, because
  nothing exposes it yet.
- **`schema_version`** — the latest applied Flyway migration, read from the
  migrated DB. Secondary indexes (a GIN on `properties` especially) make every
  INSERT costlier, so throughput steps down when they land; this column marks
  that step as a migration, not a regression.

## Throughput, and which throughput

A write comparison turns on throughput, not latency: the write cost of a secondary
index is a few percent either way, close enough to run-to-run jitter that the two
are easy to confuse, so throughput is the figure whose floor has to be known before
a delta is called real. The latency percentiles ride along in every row for
context, but nothing is decided on them.

Which throughput depends on what is being compared. **Events per second is the only
field that reads across the cells**, because a request means a different amount of
work in each; requests per second is what reads across rounds *within* a cell.
`single/` takes its spread over `throughput_rps`, where the two are the same
number and its rows have carried that field since the beginning; `batch/` takes its
over `events_per_sec`.

A floor measured in one cell does not transfer to the other. The suite's ~6% write
figure belongs to a request that does one insert, and
[the floor belongs to the amount of work](../../README.md#the-measured-floor).

## Running

Prerequisites: Docker, and the app running on the host. [k6](https://k6.io/) is
**not** installed on the host — the harness runs it from a pinned container image
(`K6_IMAGE`, default `grafana/k6:0.50.0`). Backing services come up from
`compose.yaml` via the shared startup script, so this stays correct when new
dependencies are added.

```bash
# Start the app however you normally do (IDE run config, or ./gradlew bootRun).
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

The raw k6 summary of the last run lands in
`perf/write/load/last-summary.json` (gitignored).
