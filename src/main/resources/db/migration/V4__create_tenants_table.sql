-- A tenant exists because someone holds the signing key and minted a token with
-- its claim, not because a row was inserted here. So this table is settings, not
-- a registry: ingest never reads it, no foreign key ties the event log's tenant
-- column to it, and a tenant with no row buckets in UTC. `mint-token acme &&
-- curl` keeps working with no administrative step, and a tenant reporting in UTC
-- never needs a row at all.
--
-- The zone is plain TEXT with no CHECK against pg_timezone_names: that view is
-- not immutable, so a constraint cannot reference it. Validation sits on the
-- write path instead — scripts/actions/set-tenant-zone selects the name from the
-- view, so an unknown zone writes nothing — and the read path refuses a stored
-- value it cannot parse rather than bucketing in UTC behind the caller's back.
--
-- NOT NULL because absence already means UTC: a NULL would be a second spelling
-- of the same thing, reached by a different code path.
CREATE TABLE tenants (
    name      TEXT PRIMARY KEY,
    timezone  TEXT NOT NULL
);
