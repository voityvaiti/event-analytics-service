# Design

Design rationale for the Event Analytics Service: what the system is, the
decisions behind it, the alternatives that were rejected, and what breaks
first. For build instructions and current status see [README](./README.md).

## What the system is

A backend service that ingests user-generated events (page views, clicks,
purchases, signups), persists them as an append-only log, and answers
aggregate questions over that log through a second API — the same shape of
problem as Google Analytics, Mixpanel, or Amplitude, at a smaller scope.

An event is a single immutable fact — **who did what, when** — plus arbitrary
context:

```json
{
  "event_id": "evt_abc123",
  "source": "acme",
  "user_id": "user_42",
  "event_type": "page_view",
  "timestamp": "2026-05-24T10:15:30Z",
  "properties": { "page_url": "/products/laptop-x1", "device": "mobile" }
}
```

The loop is **accept → store → aggregate → query**, and it is the entire
product.

## Engineering focus

The system exists to work on five specific problems. Everything else in it is
scaffolding around these.

- **Two-shape workload.** Writes are frequent, small, and independent; reads
  are infrequent and scan large ranges. The two sides contend for the same
  table and the same connection pool, and they want opposite things from
  indexing, batching, and caching.
- **Idempotent ingest under retries.** A client that times out will retry.
  Accepting the same event twice inflates every downstream count, and the
  inflation is invisible — there is no error to notice, only wrong numbers.
- **Correctness of time bucketing across tenants.** See below; this is the
  problem the system is most opinionated about.
- **Eventual consistency, once the pipeline is async.** "Accepted" and
  "visible in stats" become separate moments with a measurable gap between
  them. That gap has to be an observable number, not a surprise.
- **Behaviour under surge.** What the service does when demand exceeds
  capacity, and whether it returns to baseline afterwards, is measured rather
  than assumed. See [`perf/`](./perf).

## Time bucketing across tenants

A daily count is not a property of the data. It is a property of the data plus
a time zone, and the same events produce different numbers under different
zones.

- **Day and hour boundaries shift.** An event at `2026-05-24T23:30Z` belongs to
  the 24th in UTC and to the 25th in Tokyo. Rebucket a tenant's stream from UTC
  to their local zone and every daily figure changes — usually by a few
  percent, which is small enough to look like a bug in the aggregation rather
  than a zone mismatch.
- **DST breaks fixed-offset arithmetic.** A zone's offset is not constant. On a
  DST transition a local day is 23 or 25 hours long, so `floor(epoch / 86400)`
  and "UTC day plus offset" are both wrong twice a year.
- **Sub-hour offsets break hourly rollups.** India is `+05:30`, Nepal `+05:45`,
  Chatham `+12:45`. An hourly rollup in UTC cannot be re-bucketed into a local
  day for any of them, because their day boundaries do not fall on a UTC hour.

The system stores every timestamp in UTC and applies a zone only at read, via
the three-argument `date_trunc(unit, ts, zone)`, so bucket boundaries follow
the requested zone's actual calendar — DST included — rather than a fixed
offset. The zone resolves **per tenant**, not per request, because a tenant's
reported numbers must not change depending on where the person opening the
dashboard happens to be; this is what GA4, Amplitude, and Mixpanel all do.

**Current state:** the repository layer is fully zone-parametric — the zone is
a query parameter and travels back with the result, so every response states
the zone its buckets were computed in. The service layer pins that zone to UTC
because there is no tenant table to read a zone from yet. When per-tenant
settings arrive, the change is one resolution step in the service; no query,
no schema, and no stored data changes.

## Architecture

Layered, with dependencies pointing one way: `controller → service →
repository`. Each layer is consumed through an interface; implementations are
package-private and wired by Spring, so a layer's internals are not reachable
from outside it even by accident.

- **Web** — REST endpoints, request validation, DTO mapping. Errors are
  RFC 9457 `application/problem+json`.
- **Service** — ingestion and aggregation logic; owns the bucketing-zone
  policy.
- **Persistence** — JDBC data access. No JPA on the write path; the ingest
  statement is a single parameterised `INSERT`.

The web layer runs on virtual threads, so request handling is plain blocking
code.

### Data model

One table, append-only:

```sql
CREATE TABLE events (
    event_id     TEXT        PRIMARY KEY,
    source       TEXT        NOT NULL,
    user_id      TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    properties   JSONB       NOT NULL DEFAULT '{}'::JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_events_occurred_at ON events (occurred_at, event_type);
```

`event_id` is client-supplied and the primary key — the idempotency mechanism,
not a surrogate. `source` is the tenant key. Rows are never updated or
deleted; every aggregate is derived data that can be rebuilt from this table.

The index is led by `occurred_at` because every analytics query filters on a
time range and only some of them constrain `event_type`; a composite led by
`event_type` cannot serve a range scan when no type is given. The trailing
`event_type` keeps `groupBy=type` covered by the same index.

## Key design decisions

Each decision states what was chosen, why, and what was rejected.

- **Idempotency at the database unique constraint.** Clients supply
  `event_id`; a duplicate is rejected by the primary key. The constraint is the
  only place in the system that can enforce this correctly, because it is the
  only place that sees concurrent writers serialised.
  *Rejected:* an application-level seen-ID cache — fast, but it is a second
  source of truth that goes stale on restart, is not shared across instances,
  and gives a false negative exactly when the system is under the load that
  causes retries. *Also rejected:* broker-level deduplication once Kafka lands
  — it deduplicates a producer session, not a client retrying an HTTP request
  hours later, which is the actual failure mode.
- **At-least-once delivery, not exactly-once.** The pipeline will accept that
  a consumer may see the same event twice, and relies on the unique constraint
  to make reprocessing harmless.
  *Rejected:* exactly-once semantics via Kafka transactions — it costs
  throughput and a large amount of operational complexity to buy a guarantee
  that an idempotent write already provides. Exactly-once is worth paying for
  when the sink cannot be made idempotent; here it can.
- **Append-only events.** Raw events are immutable. Aggregations are derived
  and can always be recomputed.
  *Rejected:* mutable per-user counters updated in place — cheaper reads, but
  a corrupted counter is unrecoverable, and any new question about historical
  data becomes unanswerable.
- **Virtual threads over reactive.** Java 21 virtual threads give a blocking
  request handler the concurrency profile of a reactive one, with ordinary
  stack traces, ordinary debugging, and ordinary blocking JDBC.
  *Rejected:* Spring WebFlux — its concurrency advantage over virtual threads
  has largely evaporated for this workload, while the costs remain: reactive
  types leak through every layer, the driver stack is narrower, and stack
  traces stop being useful at the moment they are most needed.
- **Sync before async.** The write path is synchronous today. Kafka arrives
  when the synchronous path is measured to be the bottleneck, not before.
  *Rejected:* starting with the broker — it front-loads operational complexity
  onto a system whose limits were never established, and leaves no baseline to
  compare the async version against.
- **Aggregation escalates with measured pain.** On-the-fly SQL first, then
  cache and rollups, then pre-computation via consumers. Each rung is climbed
  only when the current one hurts and the hurt is in a journal.
  *Rejected:* pre-computing rollups from the start — it fixes the set of
  answerable questions before anyone knows which questions get asked.
- **No foreign key from `events.source` to the tenant table.** Tenant validity
  is proven at the auth layer, before the write; the tenant table is read on
  the analytics path only.
  *Rejected:* enforcing referential integrity on ingest — a foreign key puts a
  lookup and a shared lock on every insert in the hottest path in the system,
  to re-verify something the request was already authenticated against.
- **Tenant as a data dimension, not an instance.** Pooled multitenancy: one
  app, one database, one `events` table, with `source` as the tenant key.
  *Rejected:* database- or instance-per-tenant — stronger isolation, but the
  operational cost per tenant becomes non-trivial and cross-tenant queries stop
  being possible.
- **Per-tenant zone resolution, not per-request.** A tenant's daily figures are
  computed in the tenant's zone regardless of who is asking.
  *Rejected:* a per-request `?tz=` parameter as the primary mechanism — the
  same dashboard would then report different totals to two people in different
  offices, which turns a reporting system into an argument. It survives as an
  explicit override, not as the default.
- **Store UTC, bucket on read.** Timestamps are stored in UTC; the zone is
  applied at query time.
  *Rejected:* storing local time, or storing a materialised local-day column —
  both freeze a zone decision into immutable data, so a tenant changing their
  reporting zone means rewriting history.
- **Flyway over `ddl-auto`.** Migrations are versioned, reviewable, and run
  identically in dev, CI, and production.
  *Rejected:* Hibernate `ddl-auto=update` — it infers a migration from a diff,
  silently declines the destructive half, and has no notion of a rollback or a
  review.
- **Testcontainers over H2.** Tests run against the same PostgreSQL version
  production uses, in Docker.
  *Rejected:* H2 in PostgreSQL-compatibility mode — faster tests, but it
  disagrees with PostgreSQL on `jsonb`, on `date_trunc` with a zone argument,
  and on `ON CONFLICT` semantics. Every one of those is load-bearing here, so
  H2 would make the test suite green on the exact behaviour most likely to
  break.
- **`limit` is for top-N, not for time series.** `top-pages` returns a bounded
  top-N with a truncation flag; `event-counts` returns every bucket in the
  window.
  *Rejected:* a uniform `limit` on all endpoints — it would silently drop
  legitimate buckets from a time series, and it saves nothing anyway, since a
  `GROUP BY` computes every group before `LIMIT` discards any.
- **Two test tiers split by source set.** Unit tests in `src/test/java` run
  with no Docker; integration tests live in a separate `integrationTest` source
  set with Testcontainers on its classpath only.
  *Rejected:* one source set with tag-based filtering — a misplaced
  Testcontainers import would then compile and quietly slow the fast suite. The
  structural split makes it a compile error.
- **Dev runs on the host, infrastructure runs in Docker.** Postgres lives in
  Compose; the application runs from the IDE.
  *Rejected:* containerising the app in dev too — Java has no free file-watch
  reload to gain from a bind mount, and the container costs a classes mount and
  a remote-debug port for nothing. The app is containerised for smoke tests and
  for deployment, where the container is the artifact.

## Non-goals

- **Exactly-once delivery.** At-least-once plus an idempotent sink is the
  cheaper correct answer. See above.
- **Multi-region.** No cross-region replication, no conflict resolution, no
  regional data residency.
- **A columnar or dedicated analytics store.** ClickHouse or BigQuery would be
  the right answer at a data volume this system explicitly does not target;
  exporting to one is a downstream concern, not part of this service.
- **A user interface.** The read API is the product surface. Dashboards are
  Grafana or the consumer's own.
- **Instance-per-tenant isolation.** Pooled multitenancy only.
- **Rich authentication or authorisation.** JWT bearer tokens carrying a tenant
  claim, and nothing beyond that — no user management, no roles, no OAuth
  flows.
- **Event schema management.** `properties` is schemaless `jsonb` by design;
  there is no registry, no per-tenant schema validation, no evolution tooling.

## Known limitations and what breaks at 10x

Measured on the current single-node setup against a 20M-row corpus spanning 180
days (AMD Ryzen 7 7700, 16 cores, connection pool 10). Full series in
[`perf/`](./perf).

**Where it is today.** Steady-state ingest holds ~4,100 req/s with p99 under
5 ms and no failures. Reads are cheap on short windows — a 1-hour
`event-counts` is ~2 ms — and degrade linearly with the scanned range: ~27 ms
at 1 day, ~206 ms at 7 days, ~980 ms at 30 days. `active-users` is the
expensive endpoint, since a distinct-user count cannot be served from the index
alone: ~3.6 s at a 30-day window.

**What saturates first: the read path, not the write path.** A read spike
demonstrates it. Against a baseline p95 of 124 ms, a 30-second surge offering
400 req/s was served at 31.8 req/s — the load generator could not issue 9,454
of the intended requests at all — and p95 on what did get through reached
7.4 s. In the recovery window after the surge ended, p95 was still 6.3 s.
Nothing that was served returned an error; it queued, and stayed queued.
Nothing bounds that queue either: there is no statement timeout on the read
path, no query admission limit, and the connection pool is shared with ingest,
so a burst of expensive reads is capable of starving the write path that is
otherwise the system's healthy half.

**What would have to change.** In order: a `statement_timeout` on the read
path, so a pathological query fails fast instead of occupying a connection; a
separate pool or a concurrency limit for reads, so the write path cannot be
starved; then rollup tables to remove the linear scan for wide windows. Only
after that does caching pay — a TTL cache in front of an unbounded query
shortens the good case and does nothing for the bad one.

**Other known gaps.**

- Time bucketing is UTC-only in practice until the tenant table exists, though
  the query layer is already zone-parametric.
- `properties` has no GIN index, so any future filter on a JSON field is a
  sequential scan.
- There is no index on `user_id`; `active-users` shows sequential scans in the
  perf journal for that reason.
- One `events` table, unpartitioned. At 10x the corpus, time-range partitioning
  becomes the difference between pruning and scanning.
- Single node, single database. There is no horizontal read scaling and no
  replica.
