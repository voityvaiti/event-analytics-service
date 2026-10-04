# What observability costs

Measures the throughput cost of recording and scraping metrics, and verifies that Grafana can reconstruct a load run. Stage 2 also added structured logging and request IDs; their coverage and limits are described below.

Measured 2026-09-13, 16:42–17:34 UTC, on the fixed rig.

## The comparison that could not answer it

Comparing against pre-Stage-2 commit `c92df02` would also include springdoc, RFC 9457 errors, and migration changes. Historical journals additionally differ in index bloat and machine state.

Instead, all arms use one jar with different metric settings:

| Arm | Meters | Local stack | Flags |
| --- | --- | --- | --- |
| A | denied | down | `--management.metrics.enable.http=false --management.metrics.enable.events=false` |
| B | on | down | none |
| C | on | up, scraping every 2s | none |

A → B isolates recording metrics; B → C adds local scraping. Under A, `/actuator/metrics/http.server.requests` returned 404 and the scrape contained no `http_server_requests_seconds` or `events_ingested` lines. `hikaricp_*` remained available for the harness's live pool-size stamp.

Each cell reads `request_metrics` from the app and `scrape` from Prometheus for its measured window. Arm labels require no manual entry.

## Conditions

Jar `3fcc994`, stamped beside the jar at build time and checked against the running process, so each row names the artifact that produced it. Corpus 20,121,149 rows — the 20M seeded under `perf-seed`, plus 121,149 rows of an unrelated tenant left on the rig, constant across every arm. Pool 10, 10 VUs for the single-event cell, 60s measured after a 30s warm-up, AMD Ryzen 7 7700, 16 cores. Postgres from `compose.yaml`, nothing else running on the machine.

Nine visits in the order **A B C / C B A / A B C**, two measured rounds each: six rows per arm per cell, with no arm holding a fixed position in the sequence.

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

Pooled single-event medians suggest a 0.6% recording cost and another 0.5% scraping cost. Pairing arms within each triplet reduces slow drift and shows that every comparison changes sign across triplets:

| Cell | Comparison | Triplet 1 | Triplet 2 | Triplet 3 |
| --- | --- | --- | --- | --- |
| single | B vs A | +0.38% | −0.23% | −1.49% |
| | C vs B | −1.59% | −0.66% | +1.74% |
| | C vs A | −1.21% | −0.88% | +0.22% |
| batch | B vs A | +0.72% | −0.24% | −0.66% |
| | C vs B | −0.08% | −0.12% | +0.99% |
| | C vs A | +0.64% | −0.36% | +0.32% |

All six comparisons change sign; the largest difference is 1.74%. There is no resolved throughput effect. The experiment puts the approximate resolution at **1.5% for single events and 1% for batches**.

Pairing nearby arms gives a tighter comparison than the suite's ~6% pooled single-event floor by reducing drift.

## What the arms do not isolate

- **Recording only:** arm A leaves the request-ID filter, MDC, and Spring observation machinery active. It disables registry recording, including histogram buckets; the result does not bound all instrumentation code.
- **Local scraping:** C combines 30 scrapes/minute with Prometheus and Grafana competing for CPU. Remote scraping would remove container contention. Fifty direct scrapes of the loaded app measured **2.8 ms median, 3.4 ms p95** for 1,035 lines and 135 KB.
- **Logging:** successful requests emit no logs, so the write workload (224,000 successful requests per single-event round) cannot compare log formats. Correlation was checked separately below.
- **Reads:** not measured. Small timer costs would be difficult to resolve against long queries and the suite's index-bloat variation between passes.

## The other half: finding the run

Journal rows record `run_id` and the UTC measurement window. `perf/lib/annotate-runs.sh` adds dashboard regions and _Perf runs_ entries; selecting a run displays its 60s window.

Visits 1–2 ran with the stack down. Starting it for visit 3 imported their annotations. All 41 measured rows appeared; 513 older rows without timestamps were skipped and counted.

Prometheus retains 15 days. To preserve the evidence, [`observability-overhead-panels.json`](./observability-overhead-panels.json) stores dashboard expressions and results over one write and one read window.

Under `json-logging`, a rejected request returned `X-Request-Id: a649e866-14ba-40f6-848f-2e211b0a3c5b`. The same value appeared exactly once in an ECS log entry's `requestId`, alongside the validation failure.

## Stage 2 exit conditions

| Condition | State |
| --- | --- |
| A k6 load run reconstructable from Grafana alone | Met — window on every row, regions and a run list on the dashboard, five read cells and thirty-six write runs annotated |
| `/actuator/prometheus` scrapes in under 100ms | Met — 2.8ms median, 3.4ms p95 |
| Every request log line carries a correlation id | Met — pinned by integration tests, and checked here against a live response header |

## What this does not settle

The experiment establishes a resolution bound, not an exact cost. Resolving ~0.5% would need more rounds or a more targeted measurement.

It also does not measure spikes with observability enabled. Spike phase annotations support that separate analysis of queue growth and recovery.
