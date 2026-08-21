-- `active-users` was the one /stats query the V5 index could not finish:
-- `user_id` is not in it, so the distinct count visited the heap per candidate
-- row, and on the 30-day window the planner rightly abandoned the index for a
-- full sweep — ~3.7s p95 on the reference corpus. With `user_id` in the index
-- every /stats aggregate is answered from index entries alone; only
-- `top-pages` still visits the heap, for `properties`.
--
-- Widening the one tree beat the other candidate, a separate
-- (tenant_name, occurred_at, user_id) index beside it. Both were measured:
-- they serve `active-users` identically to within 0.4%, and the second tree
-- costs batch ingest 5.2% against widening's 3.0% and carries 894MB more
-- disk. What widening pays is entry width where a scan reads many entries —
-- `event-counts` by type gives back ~3% on its widest windows, nothing beyond
-- the noise floor on the 1-hour and 1-day windows that dominate traffic. The
-- journals either side of this migration and perf/active-users-index-experiment.md
-- hold the numbers.
--
-- Numbered V7, not V6, on purpose: the rejected arm ran as a real version 6 and
-- the journal rows that measured it stamp that number. Reusing 6 for a
-- different schema would leave one number meaning two things — as already
-- happened to version 3, where the 2026-08-08 rows mean "V2 minus its index"
-- and later rows mean the tenant-led index. The gap keeps every stamp
-- unambiguous; Flyway orders by version and does not require them contiguous.
--
-- Flyway migrates through the app's pool, whose every connection carries a 10s
-- statement_timeout (see application.yaml); building this index over a
-- production-sized table needs longer. SET LOCAL lifts the bound for this
-- migration's transaction only.
SET LOCAL statement_timeout = 0;

CREATE INDEX idx_events_tenant_name_occurred_at_event_type_user_id
    ON events (tenant_name, occurred_at, event_type, user_id);

DROP INDEX idx_events_tenant_name_occurred_at;
