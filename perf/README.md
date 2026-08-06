# Performance suite

Performance tests for the service, split first by the path they exercise and
then by the workload they apply to it. Each cell tracks a different question so
a regression shows up as a number (or a failed recovery), not a surprise in
production.

| Path | Cell | Question it answers |
|------|------|---------------------|
| Write | [`write/load/`](./write/load) | Steady-state ingest throughput — how many events/s can we persist, and is it drifting over time? |
| Write | [`write/spike/`](./write/spike) | Does the ingest path survive a sudden surge far above steady-state capacity, and recover afterwards? |
| Read | [`read/load/event-counts/`](./read/load/event-counts) | How long does counting events in a window take, per grouping? |
| Read | [`read/load/active-users/`](./read/load/active-users) | How long does `COUNT(DISTINCT user_id)` over a window take — the read the index can help least? |
| Read | [`read/load/top-pages/`](./read/load/top-pages) | How long does ranking pages out of JSONB take? |
| Read | [`read/spike/active-users/`](./read/spike/active-users) | Does the read path survive a burst of dashboard traffic, and does its queue drain afterwards? |

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
scripts/actions/perf/write/load               # steady-state throughput
scripts/actions/perf/write/spike              # surge and recover
scripts/actions/perf/write/all                # every write cell
scripts/actions/perf/read/load/<endpoint>     # one read endpoint's latency
scripts/actions/perf/read/load/all            # every read load cell
scripts/actions/perf/read/spike/<endpoint>    # one read surge
scripts/actions/perf/read/spike/all           # every read spike cell
scripts/actions/perf/read/all                 # every read cell, both workloads
scripts/actions/perf/all                      # everything, one combined digest
```

Each cell appends its rows and prints them. Eyeball them, then commit the
journals yourself — the tasks never commit for you.

## Rounds and the noise floor

Every action repeats each cell `ROUNDS` times, **default 1**, and reports the
spread across those rounds whenever there is more than one. An ordinary run is
therefore the single cheap measurement it has always been; ask for rounds when the
number is going to be compared against something.

Repetition is not thoroughness for its own sake. These are absolute numbers on a
machine that has other things to do, so two runs of *identical* code disagree.
How much they disagree is the **noise floor**, and it is the yardstick every
later comparison needs: a delta smaller than the floor is not a small effect, it
is no measured effect. The journal shows why this is not hypothetical — two runs
of the same commit `aadd201` on the same day recorded 4235.6 and 4061.8 events/s,
4.19% apart with nothing changed between them, while another same-commit pair
landed 0.40% apart. One pair cannot tell you which of those is typical.

Two figures come out of a repeated cell, answering different questions:

- **Coefficient of variation** — how tightly the rounds cluster. Describes the
  quality of the rig.
- **Peak-to-peak** — the full gap between the best and worst round. The wider
  number, and the honest yardstick for a one-run-each-side comparison, because
  either side can land at either extreme on luck alone.

Rounds run back to back within a cell, so nothing but chance separates them, and
each round journals its own row because each is a real measurement. The spread is
derived and never stored — it only ever describes the rows it was computed from.

### The measured floor

Established by the [index experiment](./index-experiment.md), which ran five
separate three-round passes of the write cell and two of every read cell on this
rig:

| Cell | Peak-to-peak over 3 rounds |
|------|----------------------------|
| Write load, throughput | 0.7% – 2.4% |
| Read load, `p95_ms`, index in use | 0.3% – 0.7% |
| Read load, `p95_ms`, sequential scans | 0.6% – 5.1% |

Read the write figure as **~2.5%**, and treat anything below it as no measured
effect. Two consequences are worth stating outright.

**Three rounds understates it.** Peak-to-peak can only grow as rounds are added —
more samples, more chance of catching an extreme. Two three-round passes of the
identical write cell showed 1.95% and 2.36%; pooling their six rounds gave 2.72%.
So the table is a lower bound, not the real range.

**The floor belongs to the workload, not just the rig.** Reads served by an index
repeat to within 0.7% because a lookup does a predictable amount of work; the
same queries answered by sequential scan spread up to 5.1%. A floor measured in
one regime does not transfer to the other.

Where the spread is *not* reported: both spike cells. Not because their numbers
are noisy — `spike_achieved_rps` and `spike_dropped` repeat to within 0.2%, the
tightest figures in the suite — but because a spike has no single headline
scalar. Its result is a compound verdict (`recovered` = served *and* drained),
and a spread over one of that verdict's inputs would be read as a spread over the
verdict. The inputs that *are* volatile are `recovery_p95_ms` and
`baseline_p95_ms`, which swing by nearly 50% between identical runs because they
are single-digit-millisecond values where one scheduler hiccup dominates — which
is exactly why the recovery gate allows a 5x margin. The spike cells still
repeat, because several rows are worth having.

The floor measured here describes *this* rig and is not the band
`compare-runs.mjs` applies in CI (`NOISE_PERCENT`, default 10). That one is
deliberately wider because a shared GitHub runner jitters more than a fixed
desktop. Two machines, two numbers; they must not be swapped for each other.

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
    tests.sh            the write cell list, sourced by every write action
    load/  spike/       one directory per cell: <scenario>.js, measure.sh, journal.jsonl, README.md
  read/
    tests.sh            the read cell list, per workload and combined
    load/
      stats-read.js     the latency scenario, endpoint and grouping via env
      measure-cell.sh   the measuring routine the load cells share
      event-counts/  active-users/  top-pages/
    spike/
      stats-spike.js    the surge scenario
      active-users/
```

Both paths split by workload first, because `load` and `spike` are measured
differently and judged differently. Only `read/` then splits again by endpoint:
the write path has one ingest endpoint, while each read endpoint is a different
query shape worth its own series.

The write cells each own their k6 scenario; the read load cells share one,
because they differ only in the URL they call and not in how they are measured.

- **`lib/harness.sh`** owns everything identical across tests — bringing up
  dependencies, checking the app and the k6 image, seeding the corpus and
  restoring it after a write test, and reading the pool/schema/CPU stamps. A
  test never re-implements this.
- **`lib/k6-ingest.js`** owns the `/api/v1/events` request shape, so a contract
  change touches one file, not every scenario.
- **`lib/event-generator.js`** owns what an event *looks like*. Both write
  scenarios and the corpus seeder draw from it, so the table a read test queries
  and the traffic a write test posts are one population, not two.
- **`<cell>/measure.sh`** defines a single `perf_<cell>` function: warm up, run
  the scenario, stamp and append the journal row, and record a one-line result
  for the digest. It assumes the harness is already sourced.

## Adding a cell

A cell's directory is its identity: `<path>/<workload>/` on the write side,
`<path>/<workload>/<endpoint>/` on the read side. Its function name spells the
same route out — `perf_read_load_top_pages` sits in `read/load/top-pages/`.

1. Create the cell directory with a `measure.sh` defining that function, an empty
   `journal.jsonl`, and a `README.md` explaining what the numbers mean. Reuse an
   existing scenario if the new cell only changes the request; write a k6
   scenario next to it if the workload shape itself is new.
2. Add an action at the matching path under `scripts/actions/perf/` (copy an
   existing one — source the harness and the cell's `measure.sh`, bootstrap, run,
   report). Mind the `cd` depth: it counts back to the repository root.
3. Add a run config `.run/PERF - <Name>.run.xml` (copy an existing one). Configs
   exist per path and per workload, not per read endpoint — those run through
   their workload's `all`.
4. Wire it into the pipelines: source its `measure.sh` in its path's `tests.sh`
   and add one entry to that workload's array. The `all` actions read those
   arrays, so nothing else needs touching. A single-cell action names its own
   entry inline, so that one string exists in two places — `tests.sh` stays the
   list every pipeline reads, but it is not the only place a cell is named.
5. Optionally define `perf_<cell>_spread`, which the runner calls with the round
   count after a repeated cell. One line delegating to `perf_spread` with the
   journal, the field whose spread matters, and a grouping field if a round
   appends more than one row. Skip it when a spread over the cell's headline
   metric would not support a comparison — that is why the spike cells have none.

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