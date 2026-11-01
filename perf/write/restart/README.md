# Write restart — the consumer stopped under intake

Stage 3's kill-and-restart exercise. Single events arrive at 1,000/s throughout while the harness stops the `persistence` consumer for 60 s and restarts it. The question is whether the backlog drains within its downtime and every accepted event arrives. The cell reuses the [steady scenario](../steady/ingest-at-rate.js) with one `intake` phase; [`measure-cell.sh`](./measure-cell.sh) drives the outage.

## Timeline

1. Wait for the first accepted write, then `BASELINE_SECONDS` (30).
2. `POST /internal/persistence-consumer/stop`, then poll until `STOPPED`.
3. `DOWNTIME_SECONDS` (60) from that confirmation; ~60,000 events accumulate.
4. `POST …/start`, then poll until `RUNNING`.
5. k6 keeps offering until `AFTER_SECONDS` (90) past the planned restart, so the drain is seen to hold.

The endpoint needs `app.persistence-consumer.control.enabled`, which `scripts/actions/perf/app` sets, and a `persistence:control` token the harness mints. The outage restarts the consumer whenever it exits, failed or interrupted.

## Verdict

`drained` when lag falls under one second of intake (`lag_bound`, 1,000 records) for good within `DRAIN_BUDGET_SECONDS` (60) of the start request, and under 0.1% of writes went unaccepted. Clearing 60 s of backlog in 60 s while intake continues means the consumer writes at least twice the intake rate. Otherwise `DID NOT DRAIN`, `WRITES NOT ACCEPTED`, or `TOO FEW SAMPLES` (valid samples for under 80% of the seconds after the restart). An accepted event missing after the final drain fails the cell.

## Reading a row

- `stop_seconds`, `downtime_seconds`, `start_seconds`: the timeline as observed.
- `lag_at_restart`: the backlog when the start was requested.
- `drain_after_restart_seconds`: from the start request until lag stayed under the bound.
- `catch_up_events_per_sec`: events inserted per second over that drain, the consumer's rejoin included.
- `lag.jsonl`: each run's curve, as in [steady](../steady).
