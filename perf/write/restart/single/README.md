# Write restart — `single`

`POST /api/v1/events`, one event per request at 1,000/s, the [mixed cell](../../../mixed/spike/active-users)'s write rate, so one record holds one event and lag counts events. See [shared restart methodology](..) for the timeline and verdict.

## What the first rows measured

Three rounds on `a0d9a74` (2026-11-01), 20.1M rows, LiveAgent stopped and the stack scraping; all three `drained`.

- **The outage was as planned:** stop and start each confirmed within 0.1 s, 60.0 s down, 59,873–60,003 events of backlog at the start request.
- **The backlog drained in 5.0–5.9 s** of the 60 s budget. Lag rose another ~3,000 while the consumer rejoined, to 62.9k–63.0k; over the whole drain, rejoin included, it inserted 13.2k–16.3k events/s, 13–16 times intake.
- **Intake never noticed:** no write unaccepted or dropped, accepted p99 5.6 ms throughout, and every accepted event present after the drain.

Stage 3's criterion was met there, with one consumer. Three rounds on `2ab439e` (2026-11-08) run three: all `drained`, under 1,000 records 6.8–7.4 s after the start request. Three members take ~6.5 s to rejoin the group against ~3.9 s for one, so this small backlog clears later; once assigned they clear it in under a second, and a 300 s outage drains ~2.85x faster ([capacity experiment](../../../async-capacity-experiment.md)).
