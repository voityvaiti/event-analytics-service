# Event Analytics Service

A backend service built to ingest **high-frequency** user events (page views,
clicks, purchases, signups) — accepting them fast, without loss or duplication
— then aggregate and store them efficiently to power analytics. The output is
dashboard-style insight over the raw event stream: a small, self-built take on
Google Analytics / Mixpanel / Amplitude.

The engineering focus is the **two-shape workload** — writes are frequent and
small, reads are aggregate-heavy — and each side gets its own optimizations.
The flow is **accept → store → aggregate → query**: events arrive over REST,
land in PostgreSQL as an append-only log (idempotent on a client-supplied
`event_id`), and a read API answers questions like top pages, active users, and
event counts over time.

Time bucketing is treated as a correctness problem, not formatting. A daily
count is a property of the data *and* a time zone: the same events produce
different numbers depending on where the day boundary falls, DST makes a local
day 23 or 25 hours long, and sub-hour offsets like `+05:30` break any rollup
that assumes whole-hour buckets. Timestamps are therefore stored in UTC and the
zone is applied only at read, via `date_trunc(unit, ts, zone)`, resolved per
tenant rather than per request.

📐 **[DESIGN.md](./DESIGN.md)** — design rationale, trade-offs, and the
alternatives that were rejected.

## Where to look first

Ten-minute tour, in order:

1. **[DESIGN.md → Key design decisions](./DESIGN.md#key-design-decisions)** —
   every decision with the alternative it beat and why.
2. **[DESIGN.md → Time bucketing across tenants](./DESIGN.md#time-bucketing-across-tenants)**
   — the correctness problem the system is most opinionated about;
   [`JdbcEventStatsRepository`](./src/main/java/dev/rymarovych/event_analytics/persistence/JdbcEventStatsRepository.java)
   is the implementation.
3. **[`perf/`](./perf)** — k6 suite with a per-test journal; each run appends a
   rig-stamped line, so a regression is a number rather than a surprise.
4. **Test tiering** — `src/test/java` (no Docker) versus
   `src/integrationTest/java` (Testcontainers on its classpath only); the split
   is structural, so a misplaced import is a compile error.
5. **[`.github/workflows`](./.github/workflows)** and
   [`build.gradle`](./build.gradle) — the same commands locally and in CI, plus
   an opt-in per-PR throughput comparison behind the `perf` label.

## Tech stack

- **Java 21**, **Spring Boot 4** (Spring MVC on virtual threads)
- **PostgreSQL** with **Flyway** migrations
- **Gradle** (wrapper committed)
- **JUnit 5** + **Testcontainers** (real Postgres in tests, no H2)

## Status

Early development. **Stage 0 (project setup) is complete** — formatting, static
analysis, coverage, CI, and dependency automation are wired. **Stage 1 (MVP) is
in progress:** event ingestion is implemented — the synchronous write path
(`POST /api/v1/events` → PostgreSQL, idempotent on a client-supplied `event_id`)
on a Flyway-managed schema, with its write throughput tracked over time (see
[Performance](#performance)). Batch ingestion (`POST /api/v1/events/batch`, up to
1000 events per request, all-or-nothing on validation) shares that path and its
idempotency. The MVP read side is in place: `GET
/api/v1/stats/event-counts` (event counts over a time window, grouped by type,
hour, or day), `GET /api/v1/stats/active-users` (distinct users per hour/day
bucket), and `GET /api/v1/stats/top-pages` (top-N pages by event count, with a
truncation flag). **JWT authentication is in place**, which makes the service
multi-tenant in practice rather than only in the schema: every `/api/v1`
endpoint requires an RS256 bearer token, a row's `source` comes from the
token's tenant claim instead of the request body, and every `/stats` query is
scoped to the caller's own tenant.

## Authentication

Every `/api/v1` endpoint needs `Authorization: Bearer <token>`. Tokens are
verified with an RSA **public** key, so the service can check a token but never
mint one — issuing lives outside it. `/actuator` is left open, because the perf
suite reads the live connection-pool size from it to stamp every journal row.

Mint a token for local use:

```bash
TOKEN=$(scripts/actions/mint-token acme)
curl -H "Authorization: Bearer $TOKEN" \
  'localhost:8080/api/v1/stats/event-counts?from=2026-05-24T00:00:00Z&to=2026-05-25T00:00:00Z'
```

The tenant passed there is the `source` the caller's events are written under
and the only rows its queries can see. The committed key pair is a throwaway for
local runs and the perf suite — see [`dev-keys/`](./dev-keys). **A deployment
must override
`spring.security.oauth2.resourceserver.jwt.public-key-location`**, or it will
trust tokens anyone with this repository can sign.

## Build & checks

```bash
./gradlew check    # Spotless + Error Prone + NullAway + tests + coverage
./gradlew test     # tests only
```

IntelliJ users get the same actions as run configs under `.run/` (_CHECK -
Full_, _LINT - …_, _TEST - Coverage Report_, _PERF - …_); the underlying shell
wrappers live in `scripts/actions/`.

Optionally install the git pre-commit hook once after cloning:

```bash
./scripts/install-hooks.sh
```

## Performance

The write path is load-tested and its throughput tracked over time, matching the
high-frequency-ingest focus above. The suite lives in [`perf/`](./perf):

- **Load** — steady-state ingest write throughput, one cell per request shape
  (`POST /api/v1/events` and `POST /api/v1/events/batch`). Events per second is
  the field the two are compared over; their request rates differ by the batch
  size and mean nothing side by side. At 20M rows the single-event path holds
  ~3,800 events/s and a 100-event batch holds ~125,000 — a factor of 33, which is
  what the per-request overhead was worth.
- **Spike** — behaviour under a sudden surge far above capacity, and whether the
  service recovers afterwards, again per request shape.

Each test appends to its own journal — a self-stamped, rig-aware series — so a
regression shows up as a number, not a surprise. The journal is meant to be kept
on **one machine under roughly the same conditions** each run: there is some
measurement noise, but it is acceptable at this stage of the project, so the
journal is read for **significant shifts, not small deltas**. Run the tests with
the actions under `scripts/actions/perf/` or the _PERF - …_ run configs; k6 runs
from a pinned container, so nothing beyond Docker and a running app is needed. A per-PR throughput comparison also runs in CI, opt-in
via the `perf` label. See [`perf/README.md`](./perf/README.md) for the details
and how to add a test.

## Known limitations / what breaks at 10x

The read path saturates before the write path does: steady-state ingest holds
~4,100 req/s at p99 under 5 ms, while a 30-second read surge offering 400 req/s
was served at 31.8 req/s, pushing p95 from 124 ms to 7.4 s — still 6.3 s in the
recovery window afterwards. That surge predates the 10 s statement timeout every
pooled connection now carries, which was then measured against it and does not
move the tail: the wait is for a connection, not for a query. The queue is still
unbounded, and the pool is shared with ingest, so a read burst can hold every
connection the write path needs.

Scoping reads to a tenant made this worse before it makes it better. `source` is not
in the `(occurred_at, event_type)` index, so `event-counts` lost its index-only scan:
a 1-hour count by type went from ~2 ms to ~38 ms, and under surge that cell stopped
recovering. A covering index is the fix and is the next thing measured; token
verification itself cost the write path nothing.
Full numbers and the ordered fix list are in
[DESIGN.md → Known limitations](./DESIGN.md#known-limitations-and-what-breaks-at-10x).

## Quality tooling

- **Spotless** (google-java-format) — formatting, auto-applied on edit
- **Error Prone + NullAway** — compile-time bug & nullness checks
- **JaCoCo** — coverage report at `build/reports/jacoco/test/html/index.html`
- **GitHub Actions** — lint + coverage on every PR
- **Renovate** — grouped dependency-update PRs
