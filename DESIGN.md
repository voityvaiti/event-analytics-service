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
because there is nowhere to read a per-tenant zone from yet — the tenant table
does not exist. What it no longer lacks is the tenant: since reads are scoped,
the service already receives the identity it will resolve a zone for, so the
remaining change is a table and one resolution step. No query, no schema for
`events`, and no stored data changes.

## Architecture

Layered, with dependencies pointing one way: `controller → service →
repository`. Each layer is consumed through an interface; implementations are
package-private and wired by Spring, so a layer's internals are not reachable
from outside it even by accident.

- **Web** — REST endpoints, request validation, DTO mapping. Errors are
  RFC 9457 `application/problem+json`.
- **Service** — ingestion and aggregation logic; owns the bucketing-zone
  policy.
- **Persistence** — JDBC data access. No JPA on the write path; one
  parameterised `INSERT` serves both ingest endpoints, batched when a request
  carries more than one event.

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
not a surrogate. `source` is the tenant key, and it is written from the
authenticated token's tenant claim rather than from the request, so a client
cannot attribute events to anyone else. Rows are never updated or deleted; every
aggregate is derived data that can be rebuilt from this table.

The index is led by `occurred_at` because every analytics query filters on a
time range and only some of them constrain `event_type`; a composite led by
`event_type` cannot serve a range scan when no type is given. The trailing
`event_type` keeps `groupBy=type` grouped from the same index.

`source` is not in it, and that is now the read path's largest cost. A range
`COUNT(*)` needed nothing outside the index; checking `source` sends it to the heap
per row, taking a 1-hour `event-counts` from ~2 ms to ~38 ms by type and ~7 ms by
hour. `active-users` and `top-pages` already visited the heap and moved 12–23%.

The fix is a **covering** index, not a pruning one, and the distinction decides what
the suite can prove: leading with `source` would prune by tenant, which a
single-tenant corpus cannot demonstrate, whereas restoring covering shows up on one
tenant as well as on a thousand. `(source, occurred_at, event_type)` and
`(occurred_at, event_type) INCLUDE (source)` are the candidates for the next index
experiment.

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
- **A batch is all or nothing.** `POST /api/v1/events/batch` takes up to 1000
  events, and one invalid event rejects the whole request — a `400` whose
  `errors[]` names the offender by position (`events[3].eventId`). So the batch
  is the client's retry unit, which costs nothing to retry because every event
  in it is idempotent. The events go down as one batched statement inside one
  transaction, so a database failure part-way through leaves no rows behind.
  *Rejected:* accepting the valid events and reporting the rest. It reads as the
  friendlier contract, but it buys a client nothing here — a retry of the whole
  batch cannot double-write — and it costs a second response shape, a second
  write path, and a client that must diff two lists to learn what happened.
  *Also rejected:* a multi-row `INSERT ... VALUES (...),(...)` built per request.
  It is atomic without a transaction, but its SQL text varies with the batch
  size, which scatters a driver's statement cache and `pg_stat_statements` across
  one entry per size a client happens to send.
- **A cap on batch size, not on request body size.** 1000 events is what bounds
  the work one request can ask for. Nothing bounds the bytes yet — that is the
  same missing guardrail the read path has, and it belongs with that one.
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
- **Asymmetric token signatures, not a shared secret.** Bearer tokens are
  verified with an RSA public key, keeping the ability to check a token separate
  from the ability to mint one. Issuing is already outside the service: it holds
  the public half and nothing else, and tokens are signed by a separate script.
  Verification therefore costs no key exchange, no lookup and no shared state —
  which is what lets the ingest path treat a valid signature as proof of a valid
  tenant on its hottest code path.
  *Rejected:* HS256 — one secret both signs and verifies, so every party that
  can validate a token can also forge one, and the secret has to reach each of
  them over a channel that is already secure. *Also rejected:* ES256 — ECDSA
  signs faster and verifies slower, the wrong side of that trade for a service
  that verifies on every request and signs rarely.
- **The tenant is never a request parameter.** Both paths take it from the
  verified token: ingest writes `source` from the tenant claim, and every
  `/stats` query is scoped to it, with no parameter through which a caller could
  name a different one. A `source` left in a request body is ignored rather than
  rejected, since it carries no authority and failing over it would only break
  clients.
  *Rejected:* a tenant query parameter or header validated against the token —
  it is the same guarantee expressed twice, and the two can disagree, at which
  point isolation depends on a check being remembered at every call site.
  *Also rejected:* leaving reads unscoped until the tenant table lands. It would
  have made ingest attribution trustworthy while `/stats` still answered every
  caller about everyone, which is not a smaller contract but an incoherent one.
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

**Where it is today.** Steady-state single-event ingest holds ~3,800-4,100 req/s
with p99 under 5 ms and no failures, and the batch endpoint holds ~125,000 events/s
at 100 events per request — 0.078 ms of latency per event against 4.2 ms, which is
what the per-request overhead was worth. Verifying a token per request cost neither
figure anything measurable: 127.5k to 126.0k events/s on the batch cell, inside its
0.8% spread.

Reads got worse when they were scoped to a tenant, short windows most of all: a
1-hour `event-counts` is ~38 ms by type and ~7 ms by hour, against ~2 ms for both
before. Wider windows degrade from a higher floor — ~48 ms at 1 day, ~435 ms at
7 days, ~1.3 s at 30 days by hour. `active-users` stayed the expensive endpoint and
barely noticed: ~3.8 s at 30 days. Cause and fix are under
[the data model](#data-model).

**What saturates first: the read path, not the write path.** A read spike
demonstrates it. Against a baseline p95 of 124 ms, a 30-second surge offering
400 req/s was served at 31.8 req/s — the load generator could not issue 9,454
of the intended requests at all — and p95 on what did get through reached
7.4 s. In the recovery window after the surge ended, p95 was still 6.3 s.
Nothing that was served returned an error; it queued, and stayed queued.

Those numbers predate the statement timeout. Every pooled connection now
carries a 10 s bound, and a query cancelled for exceeding it is answered 503,
so a connection can no longer be held for minutes. Nine rounds across the three
spike cells then established what that is worth here: it never fires under this
surge, and every verdict is unchanged. The tail is time spent waiting for a
connection, not time spent running a query, and a bound on the second does not
touch the first.

Scoping reads to a tenant then cost the one cell that used to pass. `event-counts`
absorbed a surge and drained afterwards: offered 4,000 req/s it served ~717 and
recovered to a p95 of ~15 ms. With the heap fetch per row it serves ~256 and recovers
to ~600 ms — it no longer recovers at all. `active-users` under its own surge did not
move on any field, which pins that to the query rather than to the rig. A few
milliseconds per query is not a latency detail when ten connections are the only
place a request waits; it sets how deep the queue goes and how long it drains. That
is the strongest argument for the covering index, and it is about surge rather than
about average latency.

**What would have to change.** The first item has landed: a bound on how long
any single statement may run, so nothing occupies a connection indefinitely.

**The next one is separating reads from writes at the connection pool, and it
matters more than the timeout did.** Both paths draw from one pool of ten
connections, first-come-first-served, and virtual threads mean that pool is the
only place a request ever waits. Throughout the surge above, all ten were held
by reads continuously — so an insert worth 2 ms of work queues behind them and
its latency becomes seconds, or fails outright once the wait passes Hikari's
connection timeout. The healthy half of the system degrades because of the half
that is not, and no query plan or timeout prevents it: the timeout bounds how
long one connection is held, never how many of them reads may hold at once.

Two shapes answer it. A second pool reserves connections for writes outright,
at the cost of leaving Boot's datasource auto-configuration and of sizing two
pools where one was measured. A concurrency limit on reads reserves the same
capacity from a single pool and bounds the queue as well, which the split does
not. Neither is measured yet — no cell runs ingest and a read surge together,
so this is a mechanism the design admits rather than a number the suite
reports, and that cell comes first.

Then rollup tables to remove the linear scan for wide windows. Only after that
does caching pay — a TTL cache in front of an unbounded query shortens the good
case and does nothing for the bad one.

**Other known gaps.**

- Time bucketing is UTC-only in practice until the tenant table exists, though
  the query layer is already zone-parametric.
- `source` is not in any index, though every read now filters on it — the largest
  open item on the read path, and the one the numbers above are waiting on. A
  covering index is the fix; see [the data model](#data-model) for why covering
  rather than pruning, and which two candidates are in the running.
- `properties` has no GIN index, so any future filter on a JSON field is a
  sequential scan.
- There is no index on `user_id`; `active-users` shows sequential scans in the
  perf journal for that reason.
- One `events` table, unpartitioned. At 10x the corpus, time-range partitioning
  becomes the difference between pruning and scanning.
- `/actuator` is unauthenticated. The perf harness reads the live pool size from
  it to stamp every journal row and gates its runs on health, and CI does the
  same, so requiring a token there would break every measurement the project
  compares against. Exposure is limited to `health,metrics`; a deployment would
  restrict it at the network edge, which is where that belongs anyway.
- Single node, single database. There is no horizontal read scaling and no
  replica.
