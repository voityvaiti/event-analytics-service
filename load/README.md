# Load testing — write throughput

Tracks ingest (`POST /api/v1/events`) write throughput across development
checkpoints, so a performance regression shows up as a number, not a surprise.
The k6 scenario lives in [`ingest-events.js`](./ingest-events.js).

Scope is deliberately narrow for Stage 1: the synchronous write path
(`controller → service → JDBC insert`) only. There are no read/analytics
endpoints yet, so nothing else is measured. Read scenarios get added when the
`/api/v1/stats/*` endpoints exist.

## Two ways the numbers are used

- **Absolute journal** — [`journal.jsonl`](./journal.jsonl), one row per
  measurement, **written only by the `loadTest` task** (never by hand). Answers
  "where are we, and are we drifting over time?" Only comparable within a fixed
  measurement rig, so every row self-stamps its host and CPU — a number from a
  different machine is a different series, not a regression.
- **Per-PR comparison** — the `Performance comparison` GitHub workflow
  (`.github/workflows/perf.yml`) builds `main` and the PR branch and load-tests
  both back-to-back on the same runner. Answers "did this PR change throughput?"
  It reports a *relative* delta, so it is robust to the runner's variable
  hardware — and writes nothing to the journal, because a shared CI runner is
  not a fixed rig.

## Why the numbers only mean something with their config

Two things dominate the result and silently invalidate cross-run comparison if
they drift. Both are recorded with every journal row.

- **Starting row count.** Inserts hit the `event_id` primary-key B-tree. Each
  k6 run uses run-salted, unique `event_id`s (so every request is a *real*
  insert, not an `ON CONFLICT DO NOTHING` no-op), which means the table grows
  every run and PK-insert cost rises with row count. The `loadTest` task
  `TRUNCATE`s before measuring so every row starts from the same empty state.
- **Hikari connection pool size.** Blocking JDBC on virtual threads means each
  in-flight insert holds one pooled connection; the default max pool is **10**.
  DB-side write concurrency is capped there regardless of VU count — past ~pool
  size you measure connection-wait, not insert cost. Keep VUs at or near the
  pool size for a clean measurement; the pool size is the more interesting knob
  to vary than VU count. To vary it, set it when you start the app
  (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20 ./gradlew bootRun`);
  `loadTest` reads the running app's actual pool from its actuator metrics and
  records that, so the journalled pool is always the one the run used — it is
  never passed to `loadTest`.

## Running it

Prerequisites: [k6](https://k6.io/) installed, and the app running on the host.
Backing services come up from the single source of truth, `compose.yaml`, via
the shared startup script — so this stays correct when new dependencies are
added.

```bash
# Bring up backing services (Postgres today, whatever compose.yaml lists later).
scripts/actions/dependencies

# Start the app however you normally do (IDE run config, or ./gradlew bootRun).

# Measure and append one row to journal.jsonl. TRUNCATEs first, stamps the row
# with host/CPU/commit. Eyeball the appended line, then commit it yourself.
scripts/actions/loadTest

# Tunables via env, e.g. push past the pool to see the saturation knee:
VUS=20 DURATION=60s scripts/actions/loadTest
```

The task also writes the raw k6 summary to `load/last-summary.json`
(gitignored).