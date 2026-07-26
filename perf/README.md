# Performance suite

Performance tests for the service, split first by the path they exercise and
then by the workload they apply to it. Each cell tracks a different question so
a regression shows up as a number (or a failed recovery), not a surprise in
production.

| Path | Cell | Question it answers |
|------|------|---------------------|
| Write | [`write/load/`](./write/load) | Steady-state ingest throughput — how many events/s can we persist, and is it drifting over time? |
| Write | [`write/spike/`](./write/spike) | Does the ingest path survive a sudden surge far above steady-state capacity, and recover afterwards? |
| Read | [`read/event-counts/`](./read/event-counts) | How long does counting events in a window take, per grouping? |
| Read | [`read/active-users/`](./read/active-users) | How long does `COUNT(DISTINCT user_id)` over a window take — the read the index can help least? |
| Read | [`read/top-pages/`](./read/top-pages) | How long does ranking pages out of JSONB take? |
| Read | [`read/spike/`](./read/spike) | Does the read path survive a burst of dashboard traffic, and does its queue drain afterwards? |

Each cell owns its own `journal.jsonl` (an absolute series, self-stamped with
the rig and config so a number is only ever compared within a fixed rig). Read
each cell's README for what its numbers mean and why; the read cells share
[one README](./read) for the parts common to all of them.

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
scripts/actions/perf/write/load        # one steady-state throughput row
scripts/actions/perf/write/spike       # one surge-and-recover row
scripts/actions/perf/write/all         # every write cell
scripts/actions/perf/read/<endpoint>   # one read endpoint on its own
scripts/actions/perf/read/spike        # the read surge
scripts/actions/perf/read/all          # every read endpoint
scripts/actions/perf/all               # everything, one combined digest
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
    k6-ingest.js        shared /api/v1/events request shape
    event-generator.js  the event bodies every scenario and the seeder produce
    query-generator.js  the questions the read scenarios ask
    seed-corpus.mjs     emits the fixed corpus as CSV for COPY
    seq-space.js        which sequence numbers each producer may draw from
    k6-stats.js         shared /api/v1/stats request shape
    k6-summary.js       shared k6 summary reader
  write/
    load/  spike/       one directory per cell: <scenario>.js, measure.sh, journal.jsonl, README.md
  read/
    stats-read.js       the latency scenario, endpoint and grouping via env
    stats-spike.js      the surge scenario
    event-counts/  active-users/  top-pages/  spike/
```

The write cells each own their k6 scenario; the read cells share one, because
they differ only in the URL they call and not in how they are measured.

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

## Adding a cell

1. Create `perf/<path>/<name>/` with a `measure.sh` defining `perf_<name>`, an
   empty `journal.jsonl`, and a `README.md` explaining what the numbers mean.
   Reuse an existing scenario if the new cell only changes the request; write a
   k6 scenario next to it if the workload shape itself is new.
2. Add an action `scripts/actions/perf/<path>/<name>` (copy an existing one —
   source the harness and the cell's `measure.sh`, bootstrap, run, report).
3. Add a run config `.run/PERF - <Name>.run.xml` (copy an existing one).
4. Wire it into the pipelines: source its `measure.sh` and add one `TESTS` entry
   in its path's `all` action and in `scripts/actions/perf/all`.

## What runs in CI

Only the **write load** cell feeds the per-PR comparison
(`.github/workflows/perf.yml`): steady-state throughput is a single, stable
number that survives a relative main-vs-PR comparison on a noisy shared runner.

Everything else is deliberately **local / journalled only**. The spike cells'
overload metrics are too high-variance to reduce to a trustworthy per-PR delta,
and the read cells would each need the corpus seeded on the runner — minutes of
setup per side for a comparison that a shared runner cannot make precise anyway.
Regressions in those are caught by their journals on a fixed rig instead.

CI also stays on an empty table rather than seeding: it measures main against
the PR branch on the same runner, so the comparison is relative, and an empty
start is as valid a fixed point there as a seeded one — without the minutes.