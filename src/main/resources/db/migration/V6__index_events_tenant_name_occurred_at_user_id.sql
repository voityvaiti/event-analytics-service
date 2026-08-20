-- `active-users` is the one /stats query the V3 index cannot finish: `user_id`
-- is not in it, so counting distinct users sends every candidate row to the
-- heap, and past a wide enough window the planner rightly gives up on the index
-- and sweeps the table. An index carrying `user_id` lets the count run on index
-- entries alone. The tenant equality leads ahead of the range for the same
-- reason it leads in V3.
--
-- A separate tree beside V3's rather than `user_id` appended to it: widening an
-- entry taxes every scan that reads many of them — storing the tenant in each
-- entry already cost `event-counts` 6-8% on its 7- and 30-day windows — and
-- `active-users` gets a narrower tree to walk than a shared one could be. What
-- the second tree costs writes and disk is measured against exactly that
-- widened alternative in the read cells' journals, either side of this
-- migration.
--
-- Flyway migrates through the app's pool, whose every connection carries a 10s
-- statement_timeout (see application.yaml); building this index over a
-- production-sized table needs longer. SET LOCAL lifts the bound for this
-- migration's transaction only.
SET LOCAL statement_timeout = 0;

CREATE INDEX idx_events_tenant_name_occurred_at_user_id
    ON events (tenant_name, occurred_at, user_id);
