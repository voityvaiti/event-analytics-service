# Event Analytics Service

A backend service that ingests user events over REST, stores them in PostgreSQL as an append-only log, and provides analytics for event counts, active users, and top pages. Ingestion is idempotent on a client-supplied `event_id`. Timestamps are stored in UTC; hourly and daily reports use each tenant's time zone.

See [DESIGN.md](./DESIGN.md) for architecture, trade-offs, and rejected alternatives.

## Where to look first

- [Key design decisions](./DESIGN.md#key-design-decisions)
- [Time bucketing across tenants](./DESIGN.md#time-bucketing-across-tenants), implemented in [`JdbcEventStatsRepository`](./src/main/java/dev/rymarovych/event_analytics/persistence/JdbcEventStatsRepository.java)
- [`perf/`](./perf) — k6 scenarios and per-test measurement journals
- Tests: `src/test/java` requires no Docker; `src/integrationTest/java` has Testcontainers on its classpath
- [CI workflows](./.github/workflows) and [build configuration](./build.gradle)

## Tech stack

- **Java 21**, **Spring Boot 4** (Spring MVC on virtual threads)
- **PostgreSQL** with **Flyway** migrations
- **Gradle** (wrapper committed)
- **JUnit 5** + **Testcontainers** (real Postgres in tests, no H2)
- **springdoc-openapi** (OpenAPI 3.1 and Swagger UI generated from the controllers)

## Status

Early development. Stages 0–2 (setup, MVP, and observability) are complete:

- Formatting, static analysis, coverage, CI, and dependency automation.
- Synchronous ingestion into a Flyway-managed schema: `POST /api/v1/events` and `POST /api/v1/events/batch`. Batches accept up to 1,000 events, with all-or-nothing validation and the same idempotency as single events.
- Analytics: `GET /api/v1/stats/event-counts` (grouped by type, hour, or day), `GET /api/v1/stats/active-users` (distinct users per hour/day), and `GET /api/v1/stats/top-pages` (top-N pages with a truncation flag).
- JWT tenant isolation and tenant-specific reporting zones.
- Generated OpenAPI documentation and Swagger UI.
- Prometheus metrics, a Grafana dashboard with perf-run annotations, and request IDs in responses and logs. See [Observability](#observability).

## API reference

Swagger UI is at `/swagger-ui.html`; the OpenAPI 3.1 document is at `/v3/api-docs`. Paths, schemas, required fields, and bounds are generated from controllers and Bean Validation constraints. The bearer scheme, `[from, to)` window, and response codes are documented manually.

Both documentation endpoints are public. API operations require a token; paste one into Swagger UI's **Authorize** to try them.

## Authentication

Every `/api/v1` endpoint requires an RS256 token in `Authorization: Bearer <token>`. The service verifies tokens with an RSA public key; tokens are issued externally. The token's tenant claim supplies `tenant_name` for writes and scopes all stats queries to that tenant.

`/actuator` is public: the perf suite reads the connection-pool size there, and Prometheus scrapes `/actuator/prometheus`.

Mint a token for local use:

```bash
TOKEN=$(scripts/actions/mint-token acme)
curl -H "Authorization: Bearer $TOKEN" \
  'localhost:8080/api/v1/stats/event-counts?from=2026-05-24T00:00:00Z&to=2026-05-25T00:00:00Z'
```

The committed key pair is for local runs and perf tests; see [`dev-keys/`](./dev-keys). **Deployments must override `spring.security.oauth2.resourceserver.jwt.public-key-location`**, or anyone with this repository can sign tokens the service trusts.

## Reporting zone

Hourly and daily reports use the zone in the tenant's `tenants` settings row, defaulting to UTC when no row exists. Set it with:

```bash
scripts/actions/set-tenant-zone acme Asia/Tokyo
```

The command is idempotent and validates against `pg_timezone_names`; an invalid zone writes nothing and exits non-zero.

`event-counts` grouped by hour/day and `active-users` include a `timezone` field. It is absent from `event-counts` grouped by type and from `top-pages`. See [Time bucketing across tenants](./DESIGN.md#time-bucketing-across-tenants) for DST and sub-hour offset handling.

## Build & checks

```bash
./gradlew check    # Spotless + Error Prone + NullAway + tests + coverage
./gradlew test     # tests only
```

IntelliJ users get the same actions as run configs under `.run/` (_CHECK - Full_, _LINT - …_, _TEST - Coverage Report_, _PERF - …_); the underlying shell wrappers live in `scripts/actions/`.

Optionally install the git pre-commit hook once after cloning:

```bash
./scripts/install-hooks.sh
```

## Performance

The [k6 suite](./perf) tracks throughput, latency, and recovery:

- **Write load:** single-event and batch ingestion. Compare events/s because request rates depend on batch size. At 20M rows, measured throughput was ~3,800 events/s for single events and ~125,000 for 100-event batches (~33×).
- **Read load:** analytics query latency by endpoint and time window.
- **Spikes:** write and read surges, recovery, and ingest during a read surge.

Each test appends a journal row with its machine and configuration. Compare runs on the same machine under similar conditions, looking for significant shifts rather than small deltas.

Start the app with `scripts/actions/perf/app`, then run actions under `scripts/actions/perf/` or the _PERF - …_ IDE configurations. k6 runs in a pinned Docker container. CI also offers a per-PR throughput comparison via the `perf` label. See [perf/README.md](./perf/README.md) for setup, interpretation, and adding tests.

## Observability

Metrics are exposed at `/actuator/prometheus`. Start the local stack with:

```bash
scripts/actions/observability        # Prometheus + Grafana, up
scripts/actions/observability down   # stop them, leaving Postgres running
```

Start the application separately; Prometheus reaches it through the host gateway. The stack uses an opt-in `observability` Compose profile, stays outside `scripts/actions/dependencies`, and publishes both services to loopback only.

Grafana at <http://localhost:3000> includes the _Event Analytics — overview_ dashboard. Summary tiles show scrape state, requests/s, security rejections, events/s, p95 latency, server error rate, and peak connection queue depth. Charts cover throughput, latency, errors, pool usage, host CPU, and scrape cost.

Select a run in _Perf runs_ to view its time range. Journal rows with measurement timestamps appear as regions; spikes have separate baseline, surge, and recovery regions. The harness adds annotations through [`perf/lib/annotate-runs.sh`](./perf/lib/annotate-runs.sh), and stack startup imports existing journals. Runs recorded without scraping are hidden from the list; show their regions with _Perf runs measured with no scraper_. Prometheus has no metrics for those windows.

The 2s scrape interval captures 30s load runs. On the reference rig with a seeded corpus, scrapes took 2.8 ms median / 3.4 ms p95 for 1,035 lines (135 KB), about 0.15% of a second per interval. Dashboard panels matched journal figures within 0.2%; measured observability overhead was below the rig's resolution. See [the experiment](./perf/observability-overhead.md) for conditions and limitations.

Every response includes `X-Request-Id`, also attached to logs for that request. An incoming ID is echoed if it contains 8–64 characters from `[A-Za-z0-9_-]`; otherwise a new ID is generated. Validation prevents log injection.

Only failures are logged: rejected tokens, malformed requests, query timeouts, and unhandled errors. Each produces one entry; 5xx entries include stack traces. Successful requests are tracked through metrics, avoiding per-request logging cost on the ingest path.

Logs are human-readable by default. For ECS JSON logs with request IDs, enable `json-logging`:

```bash
SPRING_PROFILES_ACTIVE=json-logging scripts/actions/start
```

## Known limitations / what breaks at 10x

The read path saturates first. Steady ingest measured ~4,100 req/s at p99 under 5 ms, while a 30s read surge at 400 req/s served 31.8 req/s and raised p95 from 124 ms to 7.4s (6.3s during recovery). These results predate the 10s statement timeout; subsequent measurements found it did not improve the tail because requests wait for a connection. The queue remains unbounded, and reads share the pool with ingest.

Tenant filtering initially removed the `event-counts` index-only scan: a 1h count by type rose from ~2 ms to ~38 ms. Leading the index with the tenant restored it to 0.9 ms and restored surge recovery (~693 of 4,000 req/s served, 15 ms recovery). Wider entries added 6–8% on the widest `event-counts` windows and ~3% on `top-pages`; token verification added no measurable write cost.

See [DESIGN.md → Known limitations](./DESIGN.md#known-limitations-and-what-breaks-at-10x) for the full results and ordered fixes.

## Quality tooling

- **Spotless** (google-java-format) — formatting, auto-applied on edit
- **Error Prone + NullAway** — compile-time bug & nullness checks
- **JaCoCo** — coverage report at `build/reports/jacoco/test/html/index.html`
- **GitHub Actions** — lint + coverage on every PR
- **Renovate** — grouped dependency-update PRs
