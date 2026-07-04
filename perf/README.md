# Performance suite

Performance tests for the service, one directory per workload. Each test tracks
a different question against the ingest path so a regression shows up as a
number (or a failed recovery), not a surprise in production.

| Test | Directory | Question it answers |
|------|-----------|---------------------|
| Load | [`load/`](./load) | Steady-state ingest write throughput — how many events/s can we persist, and is it drifting over time? |
| Spike | [`spike/`](./spike) | Does the ingest path survive a sudden surge far above steady-state capacity, and recover afterwards? |

Each test owns its own `journal.jsonl` (an absolute series, self-stamped with
the rig and config so a number is only ever compared within a fixed rig) and its
own k6 scenario. Read each test's README for what its numbers mean and why.

## Running

Prerequisites: Docker, and the app running on the host — start it however you
normally do (`scripts/actions/start`, an IDE run config, or `./gradlew bootRun`).
k6 itself is **not** installed on the host; every test runs it from a pinned
container image (`K6_IMAGE`, default `grafana/k6:0.50.0`). Backing services come
up from `compose.yaml` via the shared startup script, so a new dependency is a
one-line compose edit and nothing here changes.

The IDE run configs (`.run/`) wrap the actions below:

```bash
scripts/actions/perf/load    # PERF - Load:  one steady-state throughput row
scripts/actions/perf/spike   # PERF - Spike: one surge-and-recover row
scripts/actions/perf/all     # PERF:         run every test, one combined digest
```

Each test appends to its own journal and prints the appended line. Eyeball it,
then commit the journal yourself — the tasks never commit for you.

## Layout

```
perf/
  lib/
    harness.sh       shared shell harness: bootstrap + k6/db/actuator helpers
    k6-ingest.js     shared k6 request shape + summary reader
  load/  spike/  …   one directory per test: <scenario>.js, measure.sh, journal.jsonl, README.md
```

- **`lib/harness.sh`** owns everything identical across tests — bringing up
  dependencies, checking the app and the k6 image, resetting the table, and
  reading the pool/schema/CPU stamps. A test never re-implements this.
- **`lib/k6-ingest.js`** owns the `/api/v1/events` request shape, so a contract
  change touches one file, not every scenario.
- **`<test>/measure.sh`** defines a single `perf_<test>` function: warm up, run
  the scenario, stamp and append the journal row, and record a one-line result
  for the digest. It assumes the harness is already sourced.

## Adding a test

1. Create `perf/<name>/` with a k6 scenario (import the shared request shape from
   `../lib/k6-ingest.js`), a `measure.sh` defining `perf_<name>`, an empty
   `journal.jsonl`, and a `README.md` explaining what the numbers mean.
2. Add an action `scripts/actions/perf/<name>` (copy an existing one — source the
   harness and the test's `measure.sh`, bootstrap, run the function, report).
3. Add a run config `.run/PERF - <Name>.run.xml` (copy an existing one).
4. Wire it into the pipeline: source its `measure.sh` and add one `TESTS` entry
   in `scripts/actions/perf/all`.

## What runs in CI

Only the **load** test feeds the per-PR comparison
(`.github/workflows/perf.yml`): steady-state throughput is a single, stable
number that survives a relative main-vs-PR comparison on a noisy shared runner.
The spike test is deliberately **local / journalled only** — its surge rate is
sized to real hardware capacity and its overload metrics are too high-variance
to reduce to a trustworthy per-PR delta. Resilience regressions are caught by
its journal on a fixed rig instead.