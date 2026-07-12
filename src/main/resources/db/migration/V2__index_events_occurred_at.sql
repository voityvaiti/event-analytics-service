-- Every event-count query filters on an occurred_at range, so the leading
-- column is occurred_at (not event_type as sketched in DESIGN): a composite
-- led by event_type cannot serve a range scan when no event_type is given, but
-- this one does. The trailing event_type keeps the groupBy=type aggregation
-- covered by the same index.
CREATE INDEX idx_events_occurred_at ON events (occurred_at, event_type);