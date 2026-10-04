# Design

Architecture, design decisions, and measured limits. For setup and current status, see [README](./README.md).

## What the system is

The service stores user events as an append-only log and exposes aggregate queries over them.

An event is a single immutable fact — **who did what, when** — plus arbitrary context:

```json
{
  "event_id": "evt_abc123",
  "tenant_name": "acme",
  "user_id": "user_42",
  "event_type": "page_view",
  "timestamp": "2026-05-24T10:15:30Z",
  "properties": { "page_url": "/products/laptop-x1", "device": "mobile" }
}
```

The flow is **accept → store → aggregate → query**.

## Engineering focus

- **Mixed workload:** frequent small writes and large aggregate reads share a table and connection pool, with competing indexing and batching needs.
- **Idempotent ingestion:** retries must not inflate downstream counts.
- **Time bucketing:** reports must respect each tenant's calendar.
- **Future async consistency:** measure the delay between acceptance and visibility in stats.
- **Surge recovery:** measure overload and recovery with the [perf suite](./perf).

## Time bucketing across tenants

Daily and hourly counts depend on the reporting zone:

- An event at `2026-05-24T23:30Z` falls on May 24 in UTC and May 25 in Tokyo.
- DST makes local days 23 or 25 hours long; fixed-offset arithmetic is insufficient.
- Offsets such as India's `+05:30`, Nepal's `+05:45`, and Chatham's `+12:45` prevent UTC hourly rollups from being combined into exact local days.

Timestamps are stored in UTC. Queries apply `date_trunc(unit, ts, zone)` using one zone per tenant, so every viewer gets the same calendar boundaries.

The service reads the zone from `tenants` for time-bucketed queries. A missing row means UTC; an invalid stored zone fails the request rather than silently changing its calendar. Use `set-tenant-zone` to configure other zones.

The uncached lookup is estimated at **~0.15 ms** per bucketed request. This uses the change in the latency gap between `groupBy=type` and time groupings: the 1h gap grew from 0.81 ms to 0.94 ms, giving 0.14 ms for `hour` and 0.13 ms for `day` (0.04–0.25 ms across round pairings). Pairing shapes within each pass removes drift between runs; the raw gap also includes aggregation cost.

The estimate is useful only at 1h: it spreads ±0.5 ms at 1d and ±4 ms at 30d. The lookup represents ~7% of the 1h cell, ~0.5% at 1d, and no measurable share beyond that. A direct control using the same bucketed query with and without a stored tenant zone has not been run.

Transaction cost remains unmeasured: `groupBy=type` opens an unused transaction, and no equivalent query runs without one as a control. Comparing whole-suite passes cannot isolate it either; see [the perf notes](./perf/README.md#the-floor-between-runs).

## Architecture

Dependencies run `controller → service → repository` through interfaces. Implementations are package-private and wired by Spring.

- **Web** — REST endpoints, request validation, DTO mapping. Errors are RFC 9457 `application/problem+json`.
- **Service** — ingestion and aggregation logic; owns the bucketing-zone policy.
- **Persistence** — JDBC data access. No JPA on the write path; one parameterised `INSERT` serves both ingest endpoints, batched when a request carries more than one event.

The web layer runs on virtual threads, so request handling is plain blocking code.

### Data model

One table carries the data, append-only:

```sql
CREATE TABLE events (
    event_id     TEXT        PRIMARY KEY,
    tenant_name  TEXT        NOT NULL,
    user_id      TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    properties   JSONB       NOT NULL DEFAULT '{}'::JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_events_tenant_name_occurred_at_event_type_user_id
    ON events (tenant_name, occurred_at, event_type, user_id);
```

`event_id` is client-supplied and the primary key — the idempotency mechanism, not a surrogate. `tenant_name` is written from the authenticated token's tenant claim rather than from the request, so a client cannot attribute events to anyone else. It was called `source` until it was renamed to say what it holds; the perf cells' own notes keep the old name where they describe a measurement taken against it. Rows are never updated or deleted; every aggregate is derived data that can be rebuilt from this table.

Beside it sits one settings side-table, read only on the analytics path:

```sql
CREATE TABLE tenants (
    name      TEXT PRIMARY KEY,
    timezone  TEXT NOT NULL
);
```

This table holds reporting settings; authentication establishes tenant validity. It has no foreign key to `events`, ingestion never reads it, and a missing row means UTC.

The index is led by `tenant_name` because every analytics query filters on the token's tenant before its time range: an equality ahead of the range makes the scan one contiguous slice per tenant, and prunes by tenant as soon as more than one exists. `occurred_at` carries the range, and the trailing columns keep the aggregates on the index: `event_type` answers `groupBy=type`, `user_id` the distinct count of `active-users`. Only `top-pages` still visits the heap, for `properties`.

It replaced `(occurred_at, event_type)`, which tenancy broke: the tenant was not in it, so checking it sent every candidate row to the heap, taking a 1-hour `event-counts` from ~2 ms to ~38 ms by type, while `active-users` and `top-pages` — heap-bound already — moved 12–23%. Of the two candidate fixes, `(occurred_at, event_type) INCLUDE (tenant_name)` would have restored covering without pruning — it still walks every tenant's entries inside the window — so the tenant-led key won. The old index went with the migration rather than staying beside the new one: no query filters on a time range without a tenant any more, so it would only tax writes and disk. What the replacement is worth is in the read cells' journals, either side of the V3 migration.

`user_id` joined the entry the same way, and against the same rejected shape: a separate `(tenant_name, occurred_at, user_id)` tree beside this one serves `active-users` identically to within 0.4% on every window, but costs batch ingest 5.2% where widening costs 3.0%, and carries 894 MB more disk. Widening pays in entry width — ~3% back on `event-counts`' widest windows, nothing past the noise floor on the 1-hour and 1-day windows that dominate traffic. Both shapes were measured, three rounds of every cell each; the matrix is in [the experiment write-up](./perf/active-users-index-experiment.md).

## Key design decisions

- **Database idempotency.** The client supplies `event_id`; its primary-key constraint prevents duplicates across concurrent writers. *Rejected:* a seen-ID cache can become stale or disagree across instances. Broker deduplication covers producer sessions, not later HTTP retries.
- **Atomic batches.** Up to 1,000 events are validated together; one invalid event returns `400` with its position in `errors[]`, e.g. `events[3].eventId`. A batched statement in one transaction rolls back on database failure. The whole batch is safe to retry because each event is idempotent. *Rejected:* partial acceptance adds response and retry complexity without improving retry safety. Dynamic multi-row `INSERT ... VALUES (...),(...)` fragments the driver statement cache and `pg_stat_statements` by batch size.
- **Batch count limit.** The 1,000-event cap bounds work per request. Request bytes remain unbounded and need a separate guardrail.
- **At-least-once delivery.** The planned pipeline relies on database idempotency for harmless reprocessing. *Rejected:* Kafka transactions add throughput and operational costs for an exactly-once guarantee the idempotent sink does not need.
- **Append-only events.** Immutable raw data lets aggregates be rebuilt. *Rejected:* mutable counters alone cannot recover from corruption or answer new questions about historical events.
- **Virtual threads.** Java 21 supports concurrent blocking handlers with JDBC and ordinary stack traces. *Rejected:* WebFlux adds reactive types across layers and a narrower driver choice without enough benefit for this workload.
- **Sync before async.** Add Kafka when measurements establish a synchronous bottleneck. Starting with a broker would add operations work before a baseline exists.
- **Kafka for future consumers.** A retained log supports independent consumer groups, replay, OLAP ingestion, and stream processing. Per-user processing motivates `user_id` as the partition key; the Kafka ecosystem supports these uses. This selects the broker, not when to introduce it. Stage 3 needs only a `persistence` consumer, keeps raw events in Postgres, and has no broker replay requirement. Measured single-event rates do not distinguish the candidates. *Rejected:* RabbitMQ quorum queues fit that single consumer and provide per-message acknowledgement and dead-lettering, but acknowledged messages are unavailable to future readers. RabbitMQ Streams provide retention; Kafka is preferred for its log ecosystem. Kafka's positional offsets require a dead-letter topic to keep an unprocessable record from blocking a partition.
- **Aggregation follows measurements.** Start with SQL, then add caching, rollups, and consumer pre-computation as measured bottlenecks require. *Rejected:* early rollups fix the available questions before usage is known.
- **RSA token signatures.** The service holds a public key for verification; an external script signs tokens. Verification requires no lookup or shared state on the ingest path. *Rejected:* HS256 lets every verifier forge tokens and requires distributing a shared secret. ES256 trades faster signing for slower verification, whereas this service verifies every request.
- **Tenant from the token.** Ingestion and stats use the verified tenant claim. A tenant field in a request body is ignored to preserve client compatibility. *Rejected:* a separate tenant parameter duplicates authority and requires a consistency check at every call site. Unscoped reads would violate isolation.
- **No tenant foreign key.** Authentication establishes tenant validity before insertion; `tenants` is read only for analytics settings. *Rejected:* a foreign key adds a lookup and shared lock to every insert.
- **Pooled multitenancy.** One app, database, and `events` table use `tenant_name` as a dimension. *Rejected:* a database or instance per tenant increases operational cost and complicates queries across tenants, despite stronger isolation.
- **Tenant reporting zone.** All viewers get figures in the tenant's zone. *Rejected:* a per-request `?tz=` default could give viewers different totals. An explicit override is a future option, not currently implemented.
- **Missing settings mean UTC.** A valid token is enough to use the service; ingestion never reads `tenants`. *Rejected:* requiring a settings row adds an administrative prerequisite.
- **Invalid stored zones fail the read.** Return `500` in `problem+json`, naming the value. `set-tenant-zone` validates writes; this guards changes made elsewhere. *Rejected:* a UTC fallback would silently report against the wrong calendar.
- **Zone metadata only for time buckets.** `groupBy=type` and `top-pages` neither resolve nor report a zone. *Rejected:* reporting UTC would imply a calendar was used when none was.
- **UTC storage, bucketing on read.** Changing a reporting zone requires no data rewrite. *Rejected:* local timestamps or materialised local-day columns freeze a zone into immutable data.
- **Flyway migrations.** Versioned, reviewable schema changes run consistently in development, CI, and production. *Rejected:* `ddl-auto=update` infers changes, skips destructive ones, and offers no migration review or rollback model.
- **Testcontainers.** Integration tests use the production PostgreSQL version. *Rejected:* H2's compatibility mode differs on `jsonb`, zone-aware `date_trunc`, and `ON CONFLICT`, all essential to this service.
- **Top-N limits only.** `top-pages` returns a bounded result and truncation flag; `event-counts` returns every bucket. *Rejected:* time-series limits silently omit buckets and do not save the aggregation work performed before `LIMIT`.
- **Generated API reference.** springdoc derives OpenAPI 3.1 from mappings and validation constraints. The bearer scheme, `[from, to)` window, and response codes are supplied manually. *Rejected:* a separate handwritten reference can drift from the implementation.
- **Separate test source sets.** `src/test/java` runs without Docker; `integrationTest` alone has Testcontainers on its classpath. *Rejected:* tag filtering still lets accidental Testcontainers imports compile in unit tests; separate classpaths catch them at compilation.
- **Host app, Docker infrastructure in development.** Run the app from the IDE and Postgres through Compose. Containerise the app for smoke tests and deployment. *Rejected:* a development app container adds class mounts and remote debugging without automatic Java reload from a bind mount.

## Non-goals

- Exactly-once delivery; use at-least-once delivery and an idempotent sink.
- Multi-region replication, conflict resolution, or regional data residency.
- A dedicated analytics store such as ClickHouse or BigQuery; exports belong downstream.
- A product UI; consumers use the read API, and operational dashboards use Grafana.
- An instance per tenant; use pooled multitenancy.
- User management, roles, or OAuth flows beyond JWT tenant claims.
- Event schema registries, per-tenant validation, or evolution tooling; `properties` remains schemaless `jsonb`.

## Known limitations and what breaks at 10x

Measured on the current single-node setup against a 20M-row corpus spanning 180 days (AMD Ryzen 7 7700, 16 cores, connection pool 10). Full series in [`perf/`](./perf).

**Where it is today.** Steady-state single-event ingest holds ~3,800-4,100 req/s with p99 under 5 ms and no failures, and the batch endpoint holds ~125,000 events/s at 100 events per request — 0.078 ms of latency per event against 4.2 ms, which is what the per-request overhead was worth. Verifying a token per request cost neither figure anything measurable: 127.5k to 126.0k events/s on the batch cell, inside its 0.8% spread.

Reads are within a few percent of where they were before tenancy, because the index leads with the tenant it is filtered by (see [the data model](#data-model)). A 1-hour `event-counts` answers in 0.9 ms by type and 1.7 ms by hour, a 1-day in 11.6 ms and 27.4 ms. What residue there is belongs to the wider index entry — the tenant, and since V7 `user_id`, is stored in every one of them — and shows up where a scan reads many entries: `event-counts` by type loses 6–8% on its 7- and 30-day windows to the first widening and ~3% to the second. Both are past their spreads, and the first is the crossover the [index experiment](./perf/index-experiment.md) predicted, where a window stops being selective and a larger index only costs. What tenancy cost *without* the tenant-led index is the arm in between: 32x on the narrowest window.

`active-users` stopped being heap-bound when `user_id` entered the index: its 1-hour window nearly halved (9.6 to 5.0 ms p95), 1-day dropped 14% to ~110 ms — and its 30-day window only 10%, to ~3.4 s, because the scan was 0.9 s of it and the rest is the `COUNT(DISTINCT)` sort spilling to disk, which no index removes. That 3.4 s is the measured number Stage 4's rollups now own; the shape of the trade, and the write and disk price of the index that bought it, is in [the second experiment](./perf/active-users-index-experiment.md). Batch ingest paid 3.0% for the wider entry — the first write tax the suite has resolved — and single-event ingest measurably nothing.

**What saturates first: the read path, not the write path.** A read spike demonstrates it. Against a baseline p95 of 124 ms, a 30-second surge offering 400 req/s was served at 31.8 req/s — the load generator could not issue 9,454 of the intended requests at all — and p95 on what did get through reached 7.4 s. In the recovery window after the surge ended, p95 was still 6.3 s. Nothing that was served returned an error; it queued, and stayed queued.

Those numbers predate the statement timeout. Every pooled connection now carries a 10 s bound, and a query cancelled for exceeding it is answered 503, so a connection can no longer be held for minutes. Nine rounds across the three spike cells then established what that is worth here: it never fires under this surge, and every verdict is unchanged. The tail is time spent waiting for a connection, not time spent running a query, and a bound on the second does not touch the first.

Scoping reads to a tenant then cost the one cell that used to pass, and the tenant-led index bought it back. `event-counts` absorbed a surge and drained afterwards: offered 4,000 req/s it served ~717 and recovered to a p95 of ~15 ms. With a heap fetch per row it served ~260 and recovered to ~655 ms — no recovery at all. Over the new index it serves ~693 and recovers to 15.2 ms, in all three rounds. `active-users` and `top-pages` did not move on any field in either direction, which pins the swing to the query rather than to the rig — and leaves them draining, as they were before tenancy. A few milliseconds per query is not a latency detail when ten connections are the only place a request waits; it sets how deep the queue goes and how long it drains, which is why a query plan decided a surge verdict here while the statement timeout above could not.

**Next changes.** The statement timeout is in place; the next priority is reserving connection capacity for writes. Reads and writes share ten connections, so cheap inserts wait behind expensive queries.

[The mixed cell](perf/mixed/spike/README.md) measured a 7.3–7.7 s mean wait for writes during a read surge. With a 5 s client deadline, ~94% were unaccepted during the surge and ~20% in the following 30 s. No request reached Hikari's connection timeout. Abandoned requests appear to remain queued; a statement timeout cannot limit how many connections reads occupy.

Two options remain unbuilt:

- Separate pools reserve write connections but require manual datasource configuration and sizing; they do not bound the read queue.
- A read concurrency limit reserves capacity within one pool and bounds queueing.

The mixed cell provides the baseline. Stage 3's broker would remove ingest from this pool without addressing the read queue. Next, rollups should remove wide linear scans; caching alone only improves hits and leaves expensive misses.

**Other known gaps.**

- The bucketing zone is read on every bucketed request with no cache, at about 0.15 ms — ~7% of the narrowest `event-counts` cell, and too small to measure at the widest. That is the number a Stage 4 TTL cache would have to beat.
- `event-counts` grouped by type opens a read-only transaction it never uses, so it pays a `COMMIT` round trip for nothing. The cost is unmeasured: no shape runs the same query without the transaction, so there is nothing to control against.
- A per-request `?tz=` override is not implemented; the tenant's stored zone is the only one a query can be answered in.
- `properties` has no GIN index, so any future filter on a JSON field is a sequential scan.
- `active-users` sorts on disk past a wide enough window: a month of one tenant spills ~94 MB against the default 4 MB `work_mem`, ~2.6 s of the ~3.4 s total. `work_mem`, a hash-friendly query shape, and Stage 4's rollups attack that in ascending order of cost; none is measured yet.
- One `events` table, unpartitioned. At 10x the corpus, time-range partitioning becomes the difference between pruning and scanning.
- `/actuator` is unauthenticated. The perf harness reads the live pool size from it to stamp every journal row and gates its runs on health, and CI does the same, so requiring a token there would break every measurement the project compares against. Exposure is limited to `health,metrics,prometheus`, the last of which a metrics collector reads on the same terms; a deployment would restrict it at the network edge, which is where that belongs anyway.
- Single node, single database. There is no horizontal read scaling and no replica.
