# Write steady — `batch`

`POST /api/v1/events/batch`, 100 events per request, offered at 68,000 events/s (680 requests/s): the regression floor, 80% of the 85,000 events/s the [capacity experiment](../../../async-capacity-experiment.md) sustained in batches of 100 on this rig. Lag counts records, so its bound is 680. See [shared steady methodology](..) for the verdict and fields.

At this rate a run adds ~14M rows to the corpus before the cell deletes them, so "sustained" means 3.5 minutes on a growing table, as in the experiment.

## What the first rows measured

Three rounds on `ad584b3` (2026-11-08), 20.1M rows, LiveAgent stopped and the stack scraping; all three `kept up`.

- **Persisted at the offered rate:** 67,996–67,999 events/s; nothing unaccepted or dropped. Accepted p99 6.1–6.4 ms per request.
- **Lag:** segment medians at most 22 records against a bound of 680.
- **Rig:** host 5 of 16 cores; the consumers busy 0.96–0.97. Busy share is not headroom: the same consumers sustained 85,000 events/s in the experiment.
