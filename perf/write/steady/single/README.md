# Write steady — `single`

`POST /api/v1/events`, one event per request, offered at 20,000/s: the regression floor, 80% of the 25,000/s the [capacity experiment](../../../async-capacity-experiment.md) sustained on this rig. The first rows ran at 3,700/s, the sync path's closed-model throughput and Stage 3's floor; `RATE=3700` repeats them. See [shared steady methodology](..) for the verdict and fields.

## What the first rows measured

Three rounds on `a0d9a74` (2026-11-01), 20.1M rows, LiveAgent stopped and the stack scraping; all three `kept up`.

- **Persisted at the offered rate:** 3,700.0–3,700.1 events/s across the measure window; no write unaccepted or dropped. Accepted p95 5.7 ms, p99 6.0–6.1 ms.
- **Lag stays near zero:** segment medians 0–21 records against a bound of 3,700, the highest sample 43–84, about 5–20 ms of intake.

The floor holds with under 1% of the bound used. What the consumer persists above 3,700/s is the closed-model cell's question.

Three rounds on `2ab439e` (2026-11-08), linger 5 set in `application.yaml` and three consumers: all `kept up`, p99 5.9 ms, segment medians 0–21. Each consumer is busy 0.15 of the time, against 0.42 for one in the [capacity experiment](../../../async-capacity-experiment.md).

## At the regression floor

Three rounds on `ad584b3` (2026-11-08) at 20,000/s, 20.1M rows, LiveAgent stopped and the stack scraping; all three `kept up`.

- **Persisted at the offered rate:** 19,997–20,000 events/s; nothing unaccepted or dropped. Accepted p99 27–28 ms, where 3,700/s gave 6 ms.
- **Lag:** segment medians at most 321 records against a bound of 20,000, under 2% of it.
- **Rig:** host 10.5 of 16 cores, k6 4.3 of them; each consumer busy 0.68–0.75. k6 needed far fewer than its 4,000 VUs.
