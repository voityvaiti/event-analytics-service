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
analysis, coverage, CI, and dependency automation are wired. **Stage 1 (MVP)**
is next: event ingestion, the analytics endpoints, and the database schema.

## Build & checks

```bash
./gradlew check    # Spotless + Error Prone + NullAway + tests + coverage
./gradlew test     # tests only
```

IntelliJ users get the same actions as run configs under `.run/` (_CHECK -
Full_, _LINT - …_, _TEST - Coverage Report_); the underlying shell wrappers
live in `scripts/actions/`.

Optionally install the git pre-commit hook once after cloning:

```bash
./scripts/install-hooks.sh
```

## Quality tooling

- **Spotless** (google-java-format) — formatting, auto-applied on edit
- **Error Prone + NullAway** — compile-time bug & nullness checks
- **JaCoCo** — coverage report at `build/reports/jacoco/test/html/index.html`
- **GitHub Actions** — lint + coverage on every PR
- **Renovate** — grouped dependency-update PRs
