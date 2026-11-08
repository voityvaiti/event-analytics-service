## Build & checks

- **Full gate:** `./gradlew check` — Spotless (format), Error Prone + NullAway (compile-time bug & nullness checks), tests, and the JaCoCo report. Same command CI runs.
- **IDE run configs** (`.run/`, in folders) wrap the shell actions under `scripts/actions/`: _CHECK_ holds _Full_ (`check`, with a coverage summary), _Lint_ (Spotless + Error Prone + NullAway, no tests) and _Format_ (Spotless apply); _PERF_ runs `perf/run` with the cell selection filled in.

## Conventions

- **Java 21, virtual threads on** — write plain blocking code; no WebFlux or reactive types (`Mono`/`Flux`) in new code.
- **DTOs and value types are `record`s** — no Lombok.
- **Schema changes go through Flyway only** — never `ddl-auto`; add a new versioned migration, never edit one that has already been applied.
- **DB tests use Testcontainers, never H2** — tests run against the same Postgres production uses.
- **Measured commits must outlive the merge** — rebase onto `origin/main` before a perf run; until the merge only add commits on top (nothing else lands on `main`, no fixup reaches a measured commit). If CI's `journal-commits` fails, push a tag on the original commit; never change the stamp.
- **Logging via SLF4J only** — no `System.out`, no concrete logger imports.
- **SQL keywords UPPERCASE** — keywords and type names in caps (`CREATE TABLE`, `INSERT INTO`, `ON CONFLICT`, `TEXT`, `JSONB`); identifiers, columns, and named params stay lowercase. Applies to Flyway migrations and query strings; not linter-enforced.
- **No self-invented abbreviations in identifiers** — spell names out in full (`EventIngestionIntegrationTest`, not `EventIngestionIT`). Only widely-recognized abbreviations are allowed (`DB`, `URL`, `HTTP`, `JSON`, `ID`).
- **Prefer self-documenting code** — use clear names and structure. Comments should explain non-obvious rationale, constraints, or trade-offs, not repeat the implementation.
- **Keep documentation concise.** Document reasoning in READMEs, notes, and commit messages. State each point once; omit repeated explanations, hedging, and background the reader already has.

## Layering

Layered architecture; dependencies point one way only:

```
web (HTTP) ──┐
             ├→ service → persistence (Postgres) / messaging (Kafka)
consumer ────┘
```

- **Each layer is consumed through an interface** — `web → service`, `consumer → service` and `service → persistence/messaging` depend on the interface, never the concrete class; the implementation is package-private and wired by Spring.
- **Implementations are named `<Specific><Interface>`** — end the class with the interface name; the prefix states what is specific to this implementation (`JdbcEventRepository`, `PublishingEventIngestionService`). No `Impl` suffix, no vague `Default` prefix.
- `persistence` and `messaging` depend only on `domain`; `web` reaches `consumer` only for the control endpoint. `LayerDependencyTest` (ArchUnit) enforces the direction and no cycles.
- Controllers never expose JPA entities directly — map to DTOs.
