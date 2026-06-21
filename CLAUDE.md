## Build & checks

- **Full gate:** `./gradlew check` — Spotless (format), Error Prone + NullAway
  (compile-time bug & nullness checks), tests, and the JaCoCo report. Same
  command CI runs.
- **IDE run configs** (`.run/`) wrap the gradle tasks as shell actions under
  `scripts/actions/`: _CHECK - Full_, _LINT - Spotless Check/Apply_,
  _LINT - Error Prone + NullAway_, _TEST - Coverage Report_.

## Conventions

- **Java 21, virtual threads on** — write plain blocking code; no WebFlux or
  reactive types (`Mono`/`Flux`) in new code.
- **DTOs and value types are `record`s** — no Lombok.
- **Schema changes go through Flyway only** — never `ddl-auto`; add a new
  versioned migration, never edit one that has already been applied.
- **DB tests use Testcontainers, never H2** — tests run against the same
  Postgres production uses.
- **Logging via SLF4J only** — no `System.out`, no concrete logger imports.
- **SQL keywords UPPERCASE** — keywords and type names in caps (`CREATE TABLE`,
  `INSERT INTO`, `ON CONFLICT`, `TEXT`, `JSONB`); identifiers, columns, and named
  params stay lowercase. Applies to Flyway migrations and query strings; not
  linter-enforced.
- **No self-invented abbreviations in identifiers** — spell names out in full
  (`EventIngestionIntegrationTest`, not `EventIngestionIT`). Only
  widely-recognized abbreviations are allowed (`DB`, `URL`, `HTTP`, `JSON`,
  `ID`).

## Layering

Layered architecture; dependencies point one way only:

```
controller → service → repository
```

- **Each layer is consumed through an interface** — `web → service` and
  `service → repository` depend on the interface, never the concrete class; the
  implementation is package-private and wired by Spring.
- **Implementations are named `<Specific><Interface>`** — end the class with the
  interface name; the prefix states what is specific to this implementation
  (`JdbcEventRepository`, `SynchronousEventIngestionService`). No `Impl` suffix,
  no vague `Default` prefix.
- Repositories never import from `web`.
- Controllers never expose JPA entities directly — map to DTOs.
