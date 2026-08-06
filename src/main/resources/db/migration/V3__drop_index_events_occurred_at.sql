-- Arm B of the index trade-off experiment: run the suite with the index gone, so
-- the write throughput it costs and the read latency it buys are both measured
-- numbers instead of assumptions. See notes/perf-read-and-index-experiment.md.
--
-- Provisional by intent. If the measurement says the index earns its keep, this
-- migration is removed from the branch — not compensated by a later one that adds
-- the index back, which would leave two migrations in the history of every future
-- deployment whose only effect is to cancel each other out.
DROP INDEX idx_events_occurred_at;
