-- Every /stats query now filters on the token's tenant before its time range,
-- and `source` is not in the V2 index — checking it sends every candidate row
-- to the heap. Leading with `source` puts the equality ahead of the range, so
-- the scan is one contiguous slice per tenant and prunes by tenant as soon as
-- more than one exists; the other candidate, V2's key plus INCLUDE (source),
-- would restore covering without pruning — it still walks every tenant's
-- entries inside the window. The trailing event_type keeps groupBy=type
-- answerable from the index alone, as in V2.
--
-- The V2 index is dropped: no query filters on a time range without a tenant
-- any more, so it would only tax writes and disk.
--
-- Flyway migrates through the app's pool, whose every connection carries a 10s
-- statement_timeout (see application.yaml); building this index over a
-- production-sized table needs longer. SET LOCAL lifts the bound for this
-- migration's transaction only.
SET LOCAL statement_timeout = 0;

CREATE INDEX idx_events_source_occurred_at ON events (source, occurred_at, event_type);

DROP INDEX idx_events_occurred_at;
