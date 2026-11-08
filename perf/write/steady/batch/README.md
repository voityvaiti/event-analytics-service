# Write steady — `batch`

`POST /api/v1/events/batch`, 100 events per request, offered at 68,000 events/s (680 requests/s): the regression floor, 80% of the 85,000 events/s the [capacity experiment](../../../async-capacity-experiment.md) sustained in batches of 100 on this rig. Lag counts records, so its bound is 680. See [shared steady methodology](..) for the verdict and fields.

At this rate a run adds ~14M rows to the corpus before the cell deletes them, so "sustained" means 3.5 minutes on a growing table, as in the experiment.
