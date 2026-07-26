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
container image (`K6_IMAGE`, default `grafana/k6:0.50.0`), and the corpus seeder
likewise runs from a pinned node image (`NODE_IMAGE`). Backing services come up
from `compose.yaml` via the shared startup script, so a new dependency is a
one-line compose edit and nothing here changes.

The IDE run configs (`.run/`) wrap the actions below:

```bash
scripts/actions/perf/load    # PERF - Load:   one steady-state throughput row
scripts/actions/perf/spike   # PERF - Spike:  one surge-and-recover row
scripts/actions/perf/all     # PERF - All:    run every test, one combined digest
```

Each test appends to its own journal and prints the appended line. Eyeball it,
then commit the journal yourself — the tasks never commit for you.

## The corpus

Every test measures against the same seeded corpus — `SEED_ROWS` (default 20M)
rows spread over `SEED_SPREAD_DAYS` from `SEED_ANCHOR` — rather than against an
empty table. Reads need it to mean anything at all: on an empty table the
planner ignores the very indexes a read test exists to exercise. Writes keep the
same fixed starting point they always had; it simply moved from 0 to `SEED_ROWS`
and became production-shaped.

The corpus is seeded once per suite run and each test then restores it: reads
leave it untouched, and a write test deletes exactly the batch it posted, which
it can find because its rows carry a different `source` than the seeded ones. An
intact corpus is reused between runs; `SEED_FORCE=1` rebuilds it, which is
required after changing `lib/event-generator.js` — the reuse check counts rows
and cannot notice that their shape changed.

## Layout

```
perf/
  lib/
    harness.sh          shared shell harness: bootstrap + seed/k6/db/actuator helpers
    k6-ingest.js        shared k6 request shape + summary reader
    event-generator.js  the event bodies every scenario and the seeder produce
    seed-corpus.mjs     emits the fixed corpus as CSV for COPY
    seq-space.js        which sequence numbers each producer may draw from
  load/  spike/  …      one directory per test: <scenario>.js, measure.sh, journal.jsonl, README.md
```

- **`lib/harness.sh`** owns everything identical across tests — bringing up
  dependencies, checking the app and the k6 image, seeding the corpus and
  restoring it after a write test, and reading the pool/schema/CPU stamps. A
  test never re-implements this.
- **`lib/k6-ingest.js`** owns the `/api/v1/events` request shape, so a contract
  change touches one file, not every scenario.
- **`lib/event-generator.js`** owns what an event *looks like*. Both write
  scenarios and the corpus seeder draw from it, so the table a read test queries
  and the traffic a write test posts are one population, not two.
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