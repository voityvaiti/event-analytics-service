# Mixed spike — `active-users`

Surges `GET /api/v1/stats/active-users` with `groupBy=day` at 400 req/s, as
[its read spike cell](../../../read/spike/active-users) does, under steady
ingest. How the run is applied and how its row reads is described
[one level up](..); this page is why this read.

It is the surge that holds the pool longest: the heaviest read, with a ceiling
of ~90 req/s, and the one read/spike still finds draining after the surge has
ended. That makes it the case a write path has to survive, and the one Stage 3
will be judged against.
