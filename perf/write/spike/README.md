# Write spike — surge and recovery

Answers a different question from the [load workload](../load): not "how fast is
the steady state?" but "what happens when ingest traffic suddenly jumps far above
that steady state, and does the app recover once it subsides?" One cell per
request shape, judged identically:

| Cell | Request shape |
|---|---|
| [`single/`](./single) | `POST /api/v1/events` — one event per request |

The k6 scenarios sit here beside [`measure-cell.sh`](./measure-cell.sh), the
routine that applies a surge and judges it. A run is three back-to-back phases,
each a k6 scenario so its metrics are tagged and read separately:

1. **baseline** — a low rate well under capacity, to establish the healthy number.
2. **spike** — a sudden step to `SPIKE_RATE`, held for `SPIKE_SECONDS`, then off.
   No ramp: the point is the shock.
3. **recovery** — back to the baseline rate, to see whether the app returns to
   healthy service after the surge.

## Open model, on purpose

The scenarios use k6's `constant-arrival-rate` executor, which is an **open**
model: it holds a target *requests-per-second* whether or not the app keeps up.
That is the whole point — a real surge does not wait for the server. A closed
(constant-VUs) model can't show a surge: with the pool capped at 10, extra VUs
would just park on `getConnection()` and we'd measure the pool, not the shock.

Because it's open, a cell's `SPIKE_RATE` must **exceed what its own shape sustains
in the load journal**, or there is no surge to observe. Each cell derives its rate
from its own ceiling and says so; a rate shared across shapes would mean different
things at each of them.

## What the app does under surge — and what this asserts

The ingest path is synchronous with no explicit backpressure: excess requests
block on the connection pool (Hikari, default `connectionTimeout` 30s), so a
short surge shows up as **latency climbing**, and only a surge sustained past the
timeout starts shedding `500`s. So the spike's honest signal here is *latency
and dropped work under load*, not necessarily errors.

The one hard assertion is **recovery**, and it takes three things. The baseline
phase must itself be healthy — p95 within the cell's `baseline_max_p95_ms` —
because every other clause is measured against it, and a ratio to a collapsed
baseline is satisfied by a system that never recovered. That bound is per cell,
not shared: it asks whether the baseline is a state worth measuring against, and
the answer scales with the unit of work being posted. Then, after the surge, the
app must serve cleanly again (`http_req_failed` under 1%) *and* answer at roughly
the speed it did before, within 5x the baseline p95. Serving every request while
taking twenty times longer is not a recovery — it is the backlog still being
worked off. The multiple is wide because run-to-run jitter already moves this
figure by about a factor of two; what it has to catch is not close to the
boundary.

The spike phase itself is only *observed* — a spike is allowed to shed, so gating
it would either hide the signal or red every run.

`dropped_iterations` during the spike is a client-side signal too: if k6 ran out
of VUs (`MAX_VUS`) it could not offer the full target rate, so raise `MAX_VUS` to
push the server harder rather than the client.

## The journal

Each cell owns its own series, never merged with a load journal or with another
cell's — a different `scenario`, measuring a different thing. Every row
self-stamps the rig (CPU, cores) and the config that makes the numbers mean
something (pool, schema, rates, `baseline_max_p95_ms`, and the `start_rows` the
surge hit), because an absolute number is only comparable within a fixed rig. It
answers "is the app's resilience to a surge drifting over time?" — e.g. a new
index drops capacity, so the same surge now sheds more or recovers slower.

The row carries the verdict in `recovered`, so it answers its own question rather
than leaving a reader to apply the rule by hand. It is derived from
`baseline_p95_ms`, `recovery_p95_ms`, `recovery_failed_rate` and
`baseline_max_p95_ms`, all of which are in the row too, and the `commit` stamp
says which version of the rule produced it — rows from before a clause existed are
attributable, not merely stale.

No spread is reported over any of it: a spike has no single headline scalar, and a
spread over one input of a compound verdict reads as a spread over the verdict.
See the [suite README](../../README.md#rounds-and-the-noise-floor).

## Running

```bash
# App must be running on the host; the action brings backing services up itself.
scripts/actions/perf/write/spike/single      # one cell
scripts/actions/perf/write/spike/all         # every write spike cell

# Tunables via env (each cell's defaults are sized for its own ceiling):
SPIKE_RATE=12000 SPIKE_SECONDS=45 MAX_VUS=2000 scripts/actions/perf/write/spike/single
```

Tunables: `SPIKE_RATE` (per cell), `BASELINE_RATE`, `BASELINE_SECONDS` (20),
`SPIKE_SECONDS` (30), `RECOVERY_SECONDS` (30), `MAX_VUS` (1000). The raw k6
summary of the last run lands in `perf/write/spike/last-summary.json`
(gitignored).

Not run in CI: see the [suite README](../../README.md#what-runs-in-ci) for why the
spike is local / journalled only.
