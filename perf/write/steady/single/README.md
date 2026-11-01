# Write steady — `single`

`POST /api/v1/events`, one event per request, offered at 3,700/s: the sync path's closed-model throughput (median ~3,740 req/s in [`write/load/single`](../../load/single)), which Stage 3 sets as the floor. See [shared steady methodology](..) for the verdict and fields.

## What the first rows measured

Three rounds on `a0d9a74` (2026-11-01), 20.1M rows, LiveAgent stopped and the stack scraping; all three `kept up`.

- **Persisted at the offered rate:** 3,700.0–3,700.1 events/s across the measure window; no write unaccepted or dropped. Accepted p95 5.7 ms, p99 6.0–6.1 ms.
- **Lag stays near zero:** segment medians 0–21 records against a bound of 3,700, the highest sample 43–84, about 5–20 ms of intake.

The floor holds with under 1% of the bound used. What the consumer persists above 3,700/s is the closed-model cell's question.
