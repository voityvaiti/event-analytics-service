# What observability costs

Stage 2 added a Prometheus scrape, a provisioned dashboard, structured logging
and a request id. This measures what carrying them costs under load, and closes
the stage's remaining exit condition: that a k6 run is reconstructable from
Grafana alone.

Measured 2026-09-13, 16:42–17:34 UTC, on the fixed rig.

## The comparison that could not answer it

The obvious experiment — this branch against the commit before Stage 2 — is
worthless here. The diff from `c92df02` carries springdoc, the whole RFC 9457
error contract and a migration rewrite alongside the meters, so its delta would
measure three weeks of work and attribute it to instrumentation. Historical
journal rows are no substitute either: every one of them predates the
observability stack, and a row measured months ago on a differently-bloated
index answers a different question than the one being asked.

What isolates the meters is not a different commit but a different
configuration of the same one. Micrometer's meters can be denied by prefix, so
one jar serves every arm:

| Arm | Meters | Local stack | Flags |
| --- | --- | --- | --- |
| A | denied | down | `--management.metrics.enable.http=false --management.metrics.enable.events=false` |
| B | on | down | none |
| C | on | up, scraping every 2s | none |

A → B is what recording metrics costs. B → C is what a scraper on the same
machine costs on top. Confirmed on the running app rather than assumed: under
arm A `/actuator/metrics/http.server.requests` answers 404 and the scrape
carries zero `http_server_requests_seconds` and zero `events_ingested` lines,
while `hikaricp_*` survives — which matters, because the harness reads the live
pool size from the actuator to stamp every row, and an arm that broke that
would fail before it measured anything.

Nothing labels an arm by hand. `request_metrics` and `scrape` are read per cell
from the app's own scrape and from Prometheus over the run's window, the same
way `pool` and `schema_version` have always been read, so a row states what it
was measured under rather than what an operator remembered to type.

## Conditions

Jar `3fcc994`, stamped beside the jar at build time and checked against the
running process, so each row names the artifact that produced it. Corpus
20,121,149 rows — the 20M seeded under `perf-seed`, plus 121,149 rows of an
unrelated tenant left on the rig, constant across every arm. Pool 10, 10 VUs
for the single-event cell, 60s measured after a 30s warm-up, AMD Ryzen 7 7700,
16 cores. Postgres from `compose.yaml`, nothing else running on the machine.

Nine visits in the order **A B C / C B A / A B C**, two measured rounds each:
six rows per arm per cell, with no arm holding a fixed position in the
sequence.

```bash
ROUNDS=2 scripts/actions/perf/write/load/all   # once per visit, arm set by how the app was started
```

## Result: no measured effect, either shape

| Cell | Arm | n | Median | Peak-to-peak | CV |
| --- | --- | --- | --- | --- | --- |
| `write/load/single`, req/s | A | 6 | 3745.4 | 0.95% | 0.36% |
| | B | 6 | 3722.7 | 4.27% | 1.62% |
| | C | 6 | 3704.3 | 4.21% | 1.58% |
| `write/load/batch`, events/s | A | 6 | 123977 | 1.62% | 0.71% |
| | B | 6 | 124116 | 2.67% | 0.98% |
| | C | 6 | 124133 | 1.36% | 0.60% |

Read down the median column of the single-event cell and a story assembles
itself: 3745 → 3723 → 3704, meters costing 0.6% and the scraper another 0.5%.
It is not there. Each triplet of visits holds all three arms measured minutes
apart, so comparing within a triplet removes the rig's slow drift, and the
moment that is done every comparison changes sign:

| Cell | Comparison | Triplet 1 | Triplet 2 | Triplet 3 |
| --- | --- | --- | --- | --- |
| single | B vs A | +0.38% | −0.23% | −1.49% |
| | C vs B | −1.59% | −0.66% | +1.74% |
| | C vs A | −1.21% | −0.88% | +0.22% |
| batch | B vs A | +0.72% | −0.24% | −0.66% |
| | C vs B | −0.08% | −0.12% | +0.99% |
| | C vs A | +0.64% | −0.36% | +0.32% |

All six comparisons change sign across their three triplets, and the largest
difference anywhere is 1.74%. The ordering in the pooled medians was drift
shared by adjacent visits, not cost.

So: **if the meters cost anything on either path, it is under about 1.5% of
throughput on the single-event path and under about 1% on the batch one** —
below what this rig can resolve. The batch arm with meters on measured
*faster* than the arm without them, which is the same statement made from the
inside.

That bound is tighter than the ~6% floor `README.md` documents for this cell,
and for a reason worth keeping: that figure pools rounds across a whole pass,
where this design pairs arms minutes apart and lets the drift cancel.

## What the arms do not isolate

Arm A denies the meters; it does not remove observability from the request
path. The request-id filter, the MDC it writes, and Spring's observation
machinery run in both arms — what disappears is the recording into a registry,
including the percentile-histogram buckets, which is the part that scales with
request rate. So the number above bounds the cost of *keeping* metrics, not the
cost of the code that produces them.

Arm C runs Prometheus and Grafana on the machine being measured, so its delta
mixes serving 30 scrapes a minute with two containers competing for the same
CPU. A deployment scrapes from elsewhere and pays only the first half. Serving
that half is measured directly: 50 requests to `/actuator/prometheus` on the
loaded app answer in **2.8ms median, 3.4ms p95**, over 1035 lines and 135 KB.

JSON logging is not an arm. A successful request logs nothing at all by design,
so the write cells — 224,000 successful requests in a single-event round alone
— exercise none of it,
and an arm switching the log format would have measured the same thing twice.
What was verified instead is that the format carries the correlation, below.

The read cells are not in the matrix either. A per-request timer is invisible
inside a query that takes 1 to 3.4 seconds, and `README.md` documents a
cross-pass index-bloat term of the same size as any effect there would be.

## The other half: finding the run

The stage's exit condition is that a load run is reconstructable from Grafana
alone. Every row now stamps `run_id` and the exact UTC window k6 measured in,
and `perf/lib/annotate-runs.sh` turns each into a region annotation and an
entry in the dashboard's _Perf runs_ list — pick one, and the panels move to
those 60 seconds.

The catch-up path proved itself during this experiment without being aimed at:
visits 1 and 2 measured with the stack down, so nothing was annotated at the
time; visit 3 brought it up, the action synced every journal, and those runs
appeared. All 41 rows measured here are on the dashboard, and the 513 rows that
predate the window stamps are skipped and counted rather than given invented
windows.

Because the local Prometheus keeps 15 days,
[`observability-overhead-panels.json`](./observability-overhead-panels.json)
holds the series the panels draw — the dashboard's own expressions, evaluated
over one write window and one read window — so this report stays checkable
after the data behind it expires.

The correlation the log carries was checked end to end under the
`json-logging` profile: a rejected request answered `X-Request-Id:
a649e866-14ba-40f6-848f-2e211b0a3c5b`, and that value appears exactly once in
the log, in an ECS line whose `requestId` field holds it and whose message
names the validation failure.

## Stage 2 exit conditions

| Condition | State |
| --- | --- |
| A k6 load run reconstructable from Grafana alone | Met — window on every row, regions and a run list on the dashboard, five read cells and thirty-six write runs annotated |
| `/actuator/prometheus` scrapes in under 100ms | Met — 2.8ms median, 3.4ms p95 |
| Every request log line carries a correlation id | Met — pinned by integration tests, and checked here against a live response header |

## What this does not settle

The floor bounds the effect; it does not measure it. A rig quiet enough to
resolve 0.5% would need either far more rounds or a different instrument than
end-to-end throughput — and the answer it would give could not change a
decision, since the observed bound is already an order of magnitude below the
per-request cost that would make the meters worth removing.

Nothing here measures a spike under the stack. The phase bounds a spike row now
stamps are on the dashboard, and reading a surge's queue against them is the
first thing to do the next time one is run — but that is a separate question
from what instrumentation costs at steady state.
