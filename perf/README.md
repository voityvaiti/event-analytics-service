# Performance suite

Performance tests for the service, split first by the path they exercise and
then by the workload they apply to it. Each cell tracks a different question so
a regression shows up as a number (or a failed recovery), not a surprise in
production.

| Path | Cell | Question it answers |
|------|------|---------------------|
| Write | [`write/load/single/`](./write/load/single) | Steady-state ingest throughput one event at a time — how many events/s can we persist, and is it drifting over time? |
| Write | [`write/load/batch/`](./write/load/batch) | How many events/s does a batch request persist, and how much of the single-event cost was per-request overhead? |
| Write | [`write/spike/single/`](./write/spike/single) | Does the ingest path survive a sudden surge far above steady-state capacity, and recover afterwards? |
| Write | [`write/spike/batch/`](./write/spike/batch) | Does the batch path survive a surge, when each request holds a connection for a hundred inserts? |
| Read | [`read/load/event-counts/`](./read/load/event-counts) | How long does counting events in a window take, per grouping? |
| Read | [`read/load/active-users/`](./read/load/active-users) | How long does `COUNT(DISTINCT user_id)` over a window take — the read the index can help least? |
| Read | [`read/load/top-pages/`](./read/load/top-pages) | How long does ranking pages out of JSONB take? |
| Read | [`read/spike/event-counts/`](./read/spike/event-counts) | Does the cheapest read shape absorb a burst of dashboard traffic, or does queue depth beat query cost? |
| Read | [`read/spike/active-users/`](./read/spike/active-users) | Does the heaviest read shape survive a burst, and does its queue drain afterwards? |
| Read | [`read/spike/top-pages/`](./read/spike/top-pages) | Does a JSONB ranking survive a burst, with the index able to narrow the window and no more? |

Each cell owns its own `journal.jsonl` (an absolute series, self-stamped with
the rig and config so a number is only ever compared within a fixed rig). Read
each cell's README for what its numbers mean and why, and its workload's README
one level up for the parts common to every cell measured that way —
[`write/load`](./write/load), [`write/spike`](./write/spike),
[`read`](./read) and [`read/spike`](./read/spike).

## Running

Prerequisites: Docker, and the app started with **`scripts/actions/perf/app`** — not
`scripts/actions/start`. The harness refuses the latter: it is `./gradlew bootRun`,
which passes `-XX:TieredStopAtLevel=1` so C2 never compiles the request path. Same
commit here: **126.0k events/s packaged, 107k under bootRun**, with the read cells
unmoved because a Postgres query dominates them. Half the suite therefore reads as a
regression, and no journal field records how the app was launched.

k6 itself is **not** installed on the host; every test runs it from a pinned
container image (`K6_IMAGE`, default `grafana/k6:0.50.0`), and the corpus seeder
likewise runs from a pinned node image (`NODE_IMAGE`). Backing services come up
from `compose.yaml` via the shared startup script, so a new dependency is a
one-line compose edit and nothing here changes.

Every request carries a bearer token — see [Tenants and tokens](#tenants-and-tokens).

The IDE run configs (`.run/`) wrap the actions below, plus _PERF - App_ for the
launcher above — note that _APP - Start_ is the development one and the harness
will refuse it:

```bash
scripts/actions/perf/write/load/<shape>       # one request shape's throughput
scripts/actions/perf/write/load/all           # every write load cell
scripts/actions/perf/write/spike/<shape>      # one request shape's surge
scripts/actions/perf/write/spike/all          # every write spike cell
scripts/actions/perf/write/all                # every write cell, both workloads
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

Established by the [index experiment](./index-experiment.md), which ran ten
three-round passes of every cell on this rig — two arms across five corpus
densities:

| Regime | Peak-to-peak over 3 rounds |
|---|---|
| Write load, `throughput_rps`, one event per request | 1.4% – 4.8% |
| Write load, `events_per_sec`, 100 events per request | 0.8% |
| Read load, `p95_ms`, served from the index | 0% – 1.4% |
| Read load, `p95_ms`, sequential scan over gigabytes | 1.6% – 10.0% |
| Read load, `p95_ms`, sequential scan over megabytes | 0% – 1.0% |
| Read load, `p95_ms`, empty table | 0% – 4.6% |

Read the single-event write figure as **~6%**, and treat anything below it as no
measured effect. The batch row is one three-round pass rather than ten, so it is a
first reading and not yet a range — but it is an order of magnitude tighter than
the single-event regime, which is the rule below doing what it says: a request that
does a hundred inserts spends proportionally less of itself in the per-request
overhead that jitters.

Three things about this table are worth stating outright.

**Three rounds understates it.** Peak-to-peak can only grow as rounds are added —
more samples, more chance of catching an extreme. The write cell's ten passes
ranged from 1.35% to 4.76% individually; pooling their 30 rounds gave 5.84%. The
table is a lower bound, not the real range.

**The floor belongs to the amount of work, not to the machine.** The same queries
repeat to within 1.4% when an index bounds what they read, and spread to 10% when
a multi-gigabyte scan answers them — but back to 1% when the scan is only
megabytes. A floor measured in one regime transfers to no other, and the write
floor is not the read floor.

**The empty-table row is not jitter.** Those reads answer in 0.37–0.46ms, where
one hundredth of a millisecond of rounding is 2%. It is quantization of a number
too small to measure this way, which is a different thing from a noisy regime.

Where the spread is *not* reported: any spike cell. Not because nothing in them
repeats — the read spike's `spike_achieved_rps` and `spike_dropped` hold to within
1.2% — but because a spike has no single headline scalar. Its result is a compound
verdict (`recovered` = served *and* drained), and a spread over one of that
verdict's inputs would be read as a spread over the verdict. Those inputs run from
steady to wild: the write spike's `spike_achieved_rps` moves up to 9.2% between
identical rounds, and its `baseline_p95_ms` up to 92% — a figure small enough for
one scheduler hiccup to dominate, which is why the recovery gate allows a 5x
margin. The spike cells still repeat, because several rows are worth having.

The floor measured here describes *this* rig and is not the band
`compare-runs.mjs` applies in CI (`NOISE_PERCENT`, default 10). That one is
deliberately wider because a shared GitHub runner jitters more than a fixed
desktop. Two machines, two numbers; they must not be swapped for each other.

### The floor between runs

Everything above measures rounds *inside* one pass. Comparing two passes is a
different and wider thing, and the tenant-timezone run put a number on why.

That run read `top-pages` 1.1% to 2.2% slower on every window it measures — on a
branch where `top-pages` resolves nothing, opens no transaction, and executes the
same statement it always did. No single window clears its own round-to-round spread
by much, and the one-hour window does not clear it at all; what makes this a term
rather than jitter is that every window moved the same way, medians and p95 alike,
on an endpoint the branch cannot reach. The write cells said the same: ±4.4% on a
path the branch cannot touch.

The mechanism is the suite's own ordering. `perf/all` runs every write cell before
every read cell, and the write cells insert and delete hundreds of thousands of
rows. Measured across that run, the index went from 1060MB immediately after a
`REINDEX` to 1148MB by the time the read cells started — **+8.3% more index for
the same 20M rows**, and an index scan reads proportionally more pages for it.
`VACUUM ANALYZE` does not give it back; only `REINDEX` does, and the harness skips
even the `VACUUM` when it reuses an intact corpus.

So a read delta between two whole-suite passes carries a bloat term nobody
controls, and it is the same size as the effects the read cells are usually asked
about. Two consequences:

- **Prefer an internal control.** Two shapes measured in the same pass share the
  index state exactly, so how the gap between them *changes* from pass to pass
  survives what a raw delta does not. That is how the per-tenant zone lookup was
  costed at ~0.15 ms. The raw gap will not do it: two shapes differ by their
  aggregation as well as by the thing under measurement.
- **When only a cross-pass comparison will do**, `REINDEX` first and run the read
  cells alone, or accept a floor of several percent rather than the table's ~1%.


## The corpus

Every test measures against the same seeded corpus — `SEED_ROWS` (default 20M)
rows spread over `SEED_SPREAD_DAYS` from `SEED_ANCHOR` — rather than against an
empty table. Reads need it to mean anything at all: on an empty table the planner
ignores the very indexes a read test exists to exercise — measured, not assumed,
by the [index experiment](./index-experiment.md), where both arms answered every
read in under half a millisecond by sweeping a table with nothing in it. Writes
keep the same fixed starting point they always had; it simply moved from 0 to
`SEED_ROWS` and became production-shaped.

`SEED_ROWS=0` asks for that empty table deliberately, which is how the density of
the corpus becomes a variable a comparison can hold constant or sweep.

The corpus is seeded once per suite run and each test then restores it: reads
leave it untouched, and a write test deletes exactly the batch it posted, which
it can find because its rows carry a different `source` than the seeded ones. An
intact corpus is reused between runs; `SEED_FORCE=1` rebuilds it, which is
required after changing `lib/event-generator.js` — the reuse check counts rows
and cannot notice that their shape changed.

## Tenants and tokens

A row's `source` now comes from the token's tenant claim rather than the request
body, so the corpus/write-batch split is carried by **two tokens**:

| token | tenant | used by |
|-------|--------|---------|
| `SEED_TOKEN` | `perf-seed` | every read cell, and the read warm-up |
| `WRITE_TOKEN` | `perf-test` | every write cell, load and spike |

Both are minted once in `perf_bootstrap` by `lib/mint-token.mjs` (RS256, the key in
[`dev-keys/`](../dev-keys), node's built-in crypto, same pinned image as the seeder).
Each cell passes the right one as `-e TOKEN=…`; `k6_run` does not forward `TOKEN`
from the environment, so a cell states its tenant rather than inheriting one.

Mixing them fails quietly, which is the reason for the table: one shared token either
writes the batch as `perf-seed`, stranding rows `restore_seed_baseline` deletes by
`source`, or scopes the read cells to `perf-test` and reports fine latency over
nothing. The seeder is the exception — it writes the column through `COPY` and
presents no token.

No `exp` on either token: Spring checks expiry only when present, and one expiring
mid-run would surface as a nonzero `failed_rate` and read as load failure.

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
  write/
    tests.sh            the write cell list, per workload and combined
    load/
      ingest-events.js  the steady scenario, one event per request
      ingest-batches.js the steady scenario, BATCH_SIZE events per request
      measure-cell.sh   the measuring routine the load cells share
      compare-runs.mjs  the main-vs-PR comparison CI renders
      single/  batch/
    spike/
      spike-events.js   the surge scenario, one event per request
      spike-batches.js  the surge scenario, batches at a surging request rate
      measure-cell.sh   the routine the spike cells share, verdict included
      single/  batch/
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
```

Both paths split by workload first, because `load` and `spike` are measured
differently and judged differently, then again by what varies within the path:
the write side by request shape, the read side by endpoint. Either way the leaf is
a cell — a directory holding `measure.sh`, `journal.jsonl` and `README.md`, and
nothing else.

A k6 scenario and a measuring routine both live at the workload level. The read
cells share one scenario because they differ only in the query string; the write
cells need one each because they post different bodies to different endpoints. The
routine is shared in both cases, which is the point: cells of one workload differ
in what they send, never in how they are measured or judged.

- **`lib/harness.sh`** owns everything identical across tests — bringing up
  dependencies, checking the app and the k6 image, seeding the corpus and
  restoring it after a write test, and reading the pool/schema/CPU stamps. A
  test never re-implements this.
- **`lib/k6-ingest.js`** owns both `/api/v1/events` request shapes, so a contract
  change touches one file, not every scenario.
- **`lib/event-generator.js`** owns what an event *looks like*. Both write
  scenarios and the corpus seeder draw from it, so the table a read test queries
  and the traffic a write test posts are one population, not two.
- **`<workload>/measure-cell.sh`** owns a measurement: warm up, run the
  scenario, stamp and append the journal row, and record a one-line result for
  the digest. It assumes the harness is already sourced.
- **`<cell>/measure.sh`** defines a single `perf_<cell>` function, which is one
  delegating call into that routine carrying what is specific to the cell — its
  journal, its scenario, and the knobs it owns the defaults for.

## Adding a cell

A cell's directory is its identity: `<path>/<workload>/<cell>/`, where the leaf
is a request shape on the write side and an endpoint on the read side. Its
function name spells the same route out — `perf_read_load_top_pages` sits in
`read/load/top-pages/`, `perf_write_load_single` in `write/load/single/`.

1. Create the cell directory with a `measure.sh` defining that function, an empty
   `journal.jsonl`, and a `README.md` explaining what the numbers mean. Reuse an
   existing scenario if the new cell only changes the request; write a k6
   scenario next to it if the workload shape itself is new.
2. Add an action at the matching path under `scripts/actions/perf/` (copy an
   existing one — source the harness and the cell's `measure.sh`, bootstrap, run,
   report). Mind the `cd` depth: it counts back to the repository root.
3. Add a run config `.run/PERF - <Name>.run.xml` (copy an existing one). Configs
   exist per path and per workload, not per cell — a single cell runs from its
   action, or through its workload's `all`.
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

Only the **`write/load/single`** cell feeds the per-PR comparison
(`.github/workflows/perf.yml`): steady-state throughput is a single, stable
number that survives a relative main-vs-PR comparison on a noisy shared runner.
CI runs its k6 scenario directly rather than through the harness, which is why
that scenario stays at the workload level.

Everything else is deliberately **local / journalled only**. The spike cells'
overload metrics are too high-variance to reduce to a trustworthy per-PR delta,
and the read cells would each need the corpus seeded on the runner — minutes of
setup per side for a comparison that a shared runner cannot make precise anyway.
Regressions in those are caught by their journals on a fixed rig instead.

CI also stays on an empty table rather than seeding: it measures main against
the PR branch on the same runner, so the comparison is relative, and an empty
start is as valid a fixed point there as a seeded one — without the minutes.