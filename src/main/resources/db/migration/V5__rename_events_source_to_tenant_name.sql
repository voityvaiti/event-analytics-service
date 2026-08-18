-- `source` named the column before the service had tenants, when it read as
-- where an event came from. It has been the tenant key since reads were scoped,
-- and every document using it had to say so beside it; the code now carries the
-- value as a TenantName, so the column may as well say what it holds.
--
-- Both statements are catalog-only: RENAME COLUMN rewrites no rows and RENAME
-- INDEX rebuilds nothing, so the size of the table does not enter into it. What
-- can cost time is the ACCESS EXCLUSIVE lock the rename takes — it queues behind
-- a read that is still running, and new queries queue behind it while it waits.
-- That is a reason to run this when the read path is quiet, not a reason to lift
-- the pool's statement_timeout: a rename that cannot get its lock in 10s should
-- fail and be retried, not hold the table.
--
-- The index is renamed with the column because its name spells the key out, and
-- perf/lib/harness.sh reads its idx_scan counter by that name to stamp every
-- journal row. The cells' own READMEs keep the old name where they describe a
-- measurement that was taken against it.
ALTER TABLE events RENAME COLUMN source TO tenant_name;

ALTER INDEX idx_events_source_occurred_at RENAME TO idx_events_tenant_name_occurred_at;
