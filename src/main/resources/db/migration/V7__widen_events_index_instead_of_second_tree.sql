-- The second arm of the active-users index experiment: instead of a separate
-- `user_id` tree beside V3's index (V6), one wide index carries every /stats
-- query — `event-counts` from its leading three columns, `active-users` from
-- the trailing `user_id`. One tree is cheaper to write and store than two; what
-- it pays back is entry width, which every wide-window scan reads. Which side
-- of that trade wins is what the journals either side of this migration
-- measure. Only one of V6/V7 ships: the branch history is rewritten to the
-- winner before merge.
--
-- Flyway migrates through the app's pool, whose every connection carries a 10s
-- statement_timeout (see application.yaml); building this index over a
-- production-sized table needs longer. SET LOCAL lifts the bound for this
-- migration's transaction only.
SET LOCAL statement_timeout = 0;

CREATE INDEX idx_events_tenant_name_occurred_at_event_type_user_id
    ON events (tenant_name, occurred_at, event_type, user_id);

DROP INDEX idx_events_tenant_name_occurred_at_user_id;

DROP INDEX idx_events_tenant_name_occurred_at;
