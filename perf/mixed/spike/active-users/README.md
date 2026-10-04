# Mixed spike — `active-users`

Surges `GET /api/v1/stats/active-users` with `groupBy=day` at 400 req/s alongside steady ingestion. See [mixed methodology](..) and the [read spike cell](../../../read/spike/active-users).

With a ceiling of ~90 req/s and a long recovery tail, this query holds the pool long enough to expose write starvation. It is the baseline for Stage 3's acceptance target.
