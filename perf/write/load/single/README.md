# Write load — `single`

`POST /api/v1/events`, one event per request. What a row means, and why the
config stamped into it is what makes it mean anything, is [one level up](..);
this page is the request shape.

## The shape everything else is calibrated against

This is the oldest series in the suite, and the reference figure the rest of the
suite quotes: **~4,100 req/s at p99 under 5ms** on the reference rig against the
20M-row corpus. The write noise floor — read as ~6% peak-to-peak — was measured
here, over thirty rounds of the [index experiment](../../../index-experiment.md).
Every claim of the form "this change cost the write path X%" is a claim about
this cell's `throughput_rps`.

It is also the only cell in the suite that runs in CI, as the per-PR main-vs-PR
comparison. That is not because it matters most but because it is the one number
stable enough to survive a shared runner.

## `VUS` sits at the pool on purpose

Ten VUs against a pool of ten. Blocking JDBC on virtual threads means each
in-flight insert holds one pooled connection, so past ~pool size the measurement
stops being insert cost and becomes connection-wait. The pool is the more
interesting knob to vary than the VU count; it is set on the app, not here, and
the harness reads back what the run actually used.

## Every request is a real insert

`event_id` is salted with the run id, so no request lands as an
`ON CONFLICT DO NOTHING` no-op. At the default 60s window that adds ~250k rows to
the corpus — about 1.2% — which the cell then deletes again by `source`, so the
next cell starts where this one did.
