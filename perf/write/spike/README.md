# Spike testing — surge and recovery

Answers a different question from the [load test](../load): not "how fast is the
steady state?" but "what happens when ingest traffic suddenly jumps far above
that steady state, and does the app recover once it subsides?" The k6 scenario
lives in [`spike-events.js`](./spike-events.js).

The run is three back-to-back phases against the same `POST /api/v1/events`
path, each a k6 scenario so its metrics are tagged and read separately:

1. **baseline** — a low rate well under capacity, to establish the healthy number.
2. **spike** — a sudden step to `SPIKE_RATE`, held for `SPIKE_SECONDS`, then off.
   No ramp: the point is the shock.
3. **recovery** — back to the baseline rate, to see whether the app returns to
   healthy service after the surge.

## Open model, on purpose

The scenario uses k6's `constant-arrival-rate` executor, which is an **open**
model: it holds a target *requests-per-second* whether or not the app keeps up.
That is the whole point — a real surge does not wait for the server. A closed
(constant-VUs) model can't show a surge: with the pool capped at 10, extra VUs
would just park on `getConnection()` and we'd measure the pool, not the shock.

Because it's open, `SPIKE_RATE` must **exceed the load test's measured
throughput** (~4k req/s on the reference rig) or there is no surge to observe.

## What the app does under surge — and what this test asserts

The ingest path is synchronous with no explicit backpressure: excess requests
block on the connection pool (Hikari, default `connectionTimeout` 30s), so a
short surge shows up as **latency climbing**, and only a surge sustained past the
timeout starts shedding `500`s. So the spike's honest signal here is *latency
and dropped work under load*, not necessarily errors.

The one hard assertion is **recovery**, and it takes three things. The baseline
phase must itself be healthy — p95 within `BASELINE_MAX_P95_MS` (50ms against a
~2ms norm) — because every other clause is measured against it, and a ratio to a
collapsed baseline is satisfied by a system that never recovered. Then, after the
surge, the app must serve cleanly again (`http_req_failed` under 1%) *and* answer
at roughly the speed it did before, within 5x the baseline p95. Serving every
request while taking twenty times longer is not a recovery — it is the backlog
still being worked off. The multiple is wide because run-to-run jitter already
moves this figure by about a factor of two; what it has to catch is not close to
the boundary.

The verdict is **not** journalled. It is derived from `baseline_p95_ms`,
`recovery_p95_ms` and `recovery_failed_rate`, which the row does carry, so storing
it would leave a second source of truth that keeps asserting whichever rule was
current when the row was written — which is precisely what happened before the
baseline clause existed. `PERF - Write Spike` computes it fresh and exits non-zero
when it is false.

The spike phase itself is only *observed* — a spike is allowed to shed, so
gating it would either hide the signal or red every run.

`dropped_iterations` during the spike is a client-side signal too: if k6 ran out
of VUs (`MAX_VUS`) it could not offer the full target rate, so raise `MAX_VUS` to
push the server harder rather than the client.

**A healthy result is not zero drops.** At the default `MAX_VUS` the client caps
in-flight requests, so the queue never gets deep enough to hit the connection
timeout: expect the spike phase to show **high latency (hundreds of ms) and a
large `dropped` count, but ~0% failed, then a clean recovery** to baseline
latency. That is the app absorbing the surge by slowing down and shedding at the
client, which is a pass — not a bug. To actually drive `500`s you have to raise
`MAX_VUS` enough that queued requests wait past the 30s connection timeout.

## The journal

[`journal.jsonl`](./journal.jsonl) is spike's own series, **written only by the
`PERF - Spike` task** (`scripts/actions/perf/write/spike`, never by hand). It is never
merged with the load journal — a different `scenario`, measuring a different
thing. Like the load journal, every row self-stamps the rig (CPU, cores) and the
config that makes the numbers mean something (pool, schema, rates, and the
`start_rows` the surge hit), because an absolute number is only comparable
within a fixed rig. It answers "is the app's
resilience to a surge drifting over time?" — e.g. a new index drops capacity, so
the same surge now sheds more or recovers slower.

## Running

```bash
# App must be running on the host; the task brings backing services up itself.
scripts/actions/perf/write/spike

# Tunables via env (defaults sized for the reference rig):
SPIKE_RATE=12000 SPIKE_SECONDS=45 MAX_VUS=2000 scripts/actions/perf/write/spike
```

Tunables: `BASELINE_RATE` (default 500), `SPIKE_RATE` (8000), `BASELINE_SECONDS`
(20), `SPIKE_SECONDS` (30), `RECOVERY_SECONDS` (30), `MAX_VUS` (1000). The task
also writes the raw k6 summary to `perf/write/spike/last-summary.json` (gitignored).

Not run in CI: see the [suite README](../README.md#what-runs-in-ci) for why the
spike is local / journalled only.