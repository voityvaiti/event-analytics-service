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
[Performance](#performance)). The read side has started: `GET
/api/v1/stats/event-counts` returns event counts over a time window, grouped by
type, hour, or day. Still to come: the remaining analytics endpoints
(`active-users`, `top-pages`) and JWT authentication.

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

- **Load** — steady-state ingest write throughput (`POST /api/v1/events`).
- **Spike** — behaviour under a sudden surge far above capacity, and whether the
  service recovers afterwards.

Each test appends to its own journal — a self-stamped, rig-aware series — so a
regression shows up as a number, not a surprise. The journal is meant to be kept
on **one machine under roughly the same conditions** each run: there is some
measurement noise, but it is acceptable at this stage of the project, so the
journal is read for **significant shifts, not small deltas**. Run the tests with
the actions (`scripts/actions/perf/{load,spike,all}`) or the _PERF - Load / Spike
/ All_ run configs; k6 runs from a pinned container, so nothing beyond Docker and
a running app is needed. A per-PR throughput comparison also runs in CI, opt-in
via the `perf` label. See [`perf/README.md`](./perf/README.md) for the details
and how to add a test.

## Quality tooling

- **Spotless** (google-java-format) — formatting, auto-applied on edit
- **Error Prone + NullAway** — compile-time bug & nullness checks
- **JaCoCo** — coverage report at `build/reports/jacoco/test/html/index.html`
- **GitHub Actions** — lint + coverage on every PR
- **Renovate** — grouped dependency-update PRs
