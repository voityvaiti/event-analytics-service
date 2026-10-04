# Write load — `single`

`POST /api/v1/events`, one event per request. See [shared load methodology](..) for configuration and journal fields.

## The shape everything else is calibrated against

The original reference series measured **~4,100 req/s at p99 under 5 ms** on the reference rig with 20M rows. Thirty rounds of the [index experiment](../../../index-experiment.md) established its ~6% peak-to-peak noise floor. Its comparison metric is `throughput_rps`.

CI compares this cell alongside the other load cells; see [What CI compares](../../../README.md#what-ci-compares).

## `VUS` sits at the pool on purpose

Ten VUs against a pool of ten. Blocking JDBC on virtual threads means each in-flight insert holds one pooled connection, so past ~pool size the measurement stops being insert cost and becomes connection-wait. The pool is the more interesting knob to vary than the VU count; it is set on the app, not here, and the harness reads back what the run actually used.

## Every request is a real insert

`event_id` is salted with the run id, so no request lands as an `ON CONFLICT DO NOTHING` no-op. At the default 60s window that adds ~250k rows to the corpus — about 1.2% — which the cell then deletes again by `tenant_name`, so the next cell starts where this one did.
