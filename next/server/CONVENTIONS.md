# Conventions — `next/server/`

Patterns established in shipped stories that future implementers should mirror.
Cite this file (and the canonical example) when answering "how do we do X?"
for a new contributor.

## Database migrations (Flyway) — S-009

- **Canonical location:** `src/main/resources/db/migration/`.
- **Naming:** `V<n>__<snake_case_desc>.sql` for versioned migrations,
  `R__<snake_case_desc>.sql` for repeatable. Undo (`U<n>__…`) is paid-tier
  Flyway and not used here.
- **Immutability:** once a migration has shipped to ANY environment, never
  amend it — its checksum is checksum-locked in `flyway_schema_history`.
  Editing the file makes every consumer DB fail `flyway:validate` until
  `flyway:repair` runs. Add a new `V<n+1>__fix.sql` instead.
- **Out-of-order forbidden:** `spring.flyway.out-of-order: false` is pinned
  in `application.yml`. A new migration must have a higher version than
  every applied migration. Out-of-order PRs surface at PR review time, not
  at deploy.
- **Clean disabled:** `spring.flyway.clean-disabled: true` is non-negotiable
  in `application.yml`. The destructive `flyway clean` operation is locked
  off from autoconfig + CI; operator-only manual override via `./gradlew
  flywayClean` is also blocked by this property.
- **`baseline-on-migrate=false`** on dev/test/prod-fresh. S-016 cutover
  introduces its own profile that flips this for the one-shot legacy
  ingest; never let `baseline-on-migrate=true` leak into the main profile.
- **No data backfills with PII.** Migrations are schema-only. Programmatic
  seed for test fixtures, S-016 for production cutover.
- **No `GRANT` / `ALTER ROLE` / `CREATE USER` / `PASSWORD '…'` literals.**
  Enforced by `MigrationFolderConventionsTest` against
  `src/test/resources/security/forbidden-migration-patterns.txt`. Adding a
  new forbidden pattern requires CODEOWNERS sign-off; removing one is a
  security incident.
- **Java migrations (`V<n>__<Name>.java`)** are supported by Spring Boot
  autoconfig as an escape hatch for data backfills too complex for SQL.
  Ship SQL by default; reach for Java only when SQL genuinely won't fit.
- **Canonical example:** `src/main/resources/db/migration/V1__baseline.sql`.

## Test infrastructure for DB-touching tests — S-009

- **Real DB only, no mocking.** Integration tests run against a real
  Postgres container driven by the docker CLI directly (Testcontainers
  1.21.x can't negotiate Docker API ≥1.44 in our sandbox).
- **Helper:** `src/test/java/ch/fls/server/testsupport/PostgresTestContainerLifecycle.java`.
  Static-once per JVM run; image `postgres:17.4-alpine` pinned; readiness
  probe via JDBC `SELECT 1`; JVM shutdown hook tears the container down.
- **Guard:** every Docker-driven integration test class is annotated
  `@EnabledIf("dockerAvailable")` so contributors without Docker still pass
  `./gradlew check` cleanly (tests skip rather than fail).
- **Static-asset tests** that only walk the classpath (no DB) live alongside
  the integration test in the same package, plain JUnit (no
  `@SpringBootTest`).
- **H2 fallback for non-Flyway `@SpringBootTest`** classes. Add
  `@ActiveProfiles("test")` so the in-memory H2 DataSource from
  `application-test.yml` is wired; otherwise the `dev` profile's
  loopback-Postgres defaults will cause a connection refusal at boot.
- **Canonical example:** `src/test/java/ch/fls/server/migration/FlywayBootstrapIntegrationTest.java`.
