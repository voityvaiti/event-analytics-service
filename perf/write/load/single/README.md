# Write load — `single`

`POST /api/v1/events`, one event per request. See [shared load methodology](..) for configuration and journal fields.

## The shape everything else is calibrated against

The original reference series measured **~4,100 req/s at p99 under 5 ms** on the reference rig with 20M rows. Thirty rounds of the [index experiment](../../../index-experiment.md) established its ~6% peak-to-peak noise floor. Its comparison metric is `throughput_rps`.

CI compares this cell alongside the other load cells; see [What CI compares](../../../README.md#what-ci-compares).

## `VUS` sits at the pool on purpose

Ten VUs against a pool of ten. Blocking JDBC on virtual threads means each in-flight insert holds one pooled connection, so past ~pool size the measurement stops being insert cost and becomes connection-wait. The pool is the more interesting knob to vary than the VU count; it is set on the app, not here, and the harness reads back what the run actually used.

## Every request is a real insert

`event_id` is salted with the run id, so no request lands as an `ON CONFLICT DO NOTHING` no-op. At the default 60s window that adds ~250k rows to the corpus — about 1.2% — which the cell then deletes again by `tenant_name`, so the next cell starts where this one did.

## The async rows: a timer, not the server

Three rounds on `a0d9a74` (2026-11-01): **1,749 req/s**, p95 5.7 ms, p99 5.9–6.0 ms, 0.03% peak-to-peak, against the sync rows' ~3,740 req/s. The producer waits `linger.ms` for more records before it sends, 5 ms by default in kafka-clients 4.x and not overridden here, so each request takes ~5.7 ms and ten VUs make 10 / 5.7 ms ≈ 1,750 requests a second. The near-zero spread says the same: the cell now measures that wait.

At ten VUs this cell therefore no longer finds the maximum. Stage 3's throughput check is the open-model [steady cell](../../steady/single), which keeps ~21 requests in flight at 3,700/s and is accepted and persisted at that rate. One scratch round with the app launched with `--spring.kafka.producer.properties.linger.ms=0`, not journalled, reached 18,122 req/s at p99 1.1 ms and drained in 0 s, which confirms the wait as the bound. Every round drained in 0 s: the consumer keeps up with what ten VUs offer.

Three rounds on `2ab439e` (2026-11-08), with linger 5 set in `application.yaml` and three consumers: 1,746–1,751 req/s, p99 6.1 ms, the same wait. The [capacity experiment](../../../async-capacity-experiment.md) chose the explicit 5 ms; a series with more VUs is still open.
