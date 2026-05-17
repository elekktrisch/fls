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

## Test infrastructure for DB-touching tests — S-009 / S-012

- **Real DB only, no mocking.** Every `@SpringBootTest` shares a single
  Postgres 17 container; H2 was retired in S-012 once migrations started
  using `uuid` / `TEXT[]` / partial indexes / `COMMENT ON COLUMN` (H2 even
  in `MODE=PostgreSQL` can't parse all of that).
- **Shared container:** `src/test/java/ch/alpenflight/server/testsupport/SharedPostgresContainer.java`
  is a JVM-singleton that wraps `PostgresTestContainerLifecycle` — one
  container per JVM, lazily started on first reference, torn down by the
  shutdown hook. Tests reuse it via `SharedPostgresContainer.INSTANCE`
  in `@DynamicPropertySource`. Flyway migrate is idempotent across class
  boots (V1+V2 apply once; subsequent boots no-op via
  `flyway_schema_history`).
- **Container helper:** `src/test/java/ch/alpenflight/server/testsupport/PostgresTestContainerLifecycle.java`
  drives the container via the `docker` CLI (Testcontainers 1.21.x can't
  negotiate Docker API ≥1.44 in our sandbox). Image `postgres:17.4-alpine`
  pinned; readiness probe via JDBC `SELECT 1`.
- **Guard:** every `@SpringBootTest` class is annotated
  `@EnabledIf("ch.alpenflight.server.testsupport.SharedPostgresContainer#available")`,
  so contributors without Docker still pass `./gradlew check` cleanly
  (tests skip rather than fail).
- **CI fail-loud guard.** `SharedPostgresContainer.available()` throws
  (instead of returning false) when Docker is unreachable AND `CI=true` is
  set (GitHub Actions / GitLab / CircleCI all set it). Otherwise a CI run
  on a hiccuping Docker daemon would silently skip every DB-touching test
  and report green — exactly the false-pass this gate exists to prevent.
- **Static-asset tests** that only walk the classpath (no DB) live alongside
  the integration test in the same package, plain JUnit (no
  `@SpringBootTest`). Example: `MigrationFolderConventionsTest`.
- **Slice tests** (`@WebMvcTest`, `@DataJpaTest`, etc.) don't auto-configure
  a DataSource and don't need the shared container. Example:
  `HelloControllerIT`.
- **Canonical example:** `src/test/java/ch/alpenflight/server/migration/FlywayBootstrapIntegrationTest.java`.

## Column shape (categorical / state / discriminator) — S-012, ADR 0020

Per [ADR 0020](../../docs/modernization/adrs/0020-categorical-column-shape.md), decision rule for picking the SQL column shape. The Java enum is the **only** value-set authority; no DB-side `CHECK IN (...)` mirrors it. Adding / removing an enum value is a Java-only change with no migration burden.

1. **Categorical / state / discriminator** → enum-as-string. `VARCHAR(32) NOT NULL` (no CHECK). Java `@Enumerated(EnumType.STRING)`. Code values UPPER_SNAKE_CASE.
2. **Independent, orthogonal flags** → `BOOLEAN`. Canonical examples: `person_club.is_active`, `user.email_confirmed`. Each flag varies without affecting others' validity.
3. **Multiple booleans where some combinations are illegal** → collapse to one enum-as-string column. Java enum enforces legal states.
4. **Multiple booleans encoding SET-MEMBERSHIP** → Postgres `TEXT[] NOT NULL` (no CHECK on subset / non-empty). Canonical example: `start_type.applicable_categories` (`src/main/resources/db/migration/V2__identity_and_reference.sql:93-102` — column on line 97). Replaced the legacy `is_for_glider / is_for_tow / is_for_motor` boolean trio. Java side enforces subset of `{GLIDER, TOW, MOTOR}` and non-emptiness.
5. **Categorical with rich metadata** (per-language display names, sort order, soft-deprecation, club-scoped variants) → FK to lookup table. Examples: `country`, `language`, `member_state`, `person_category`, `role`.

**Natural-invariant CHECKs survive:** the rule only drops CHECKs that mirror enum value sets. Permanent business invariants that don't drift with code stay useful — examples: `CHECK (birthday <= CURRENT_DATE)`, `CHECK (upper(iso2_code) = iso2_code)`, the coarse `email LIKE '%_@_%._%'` shape.

When in doubt: lead with the enum-as-string default. If you find yourself naming `is_X` and `is_Y` flags on the same row, check rule 3 — invalid combinations are the signal to collapse.

## ID strategy — S-012, ADR 0019

- Every PK is `uuid NOT NULL PRIMARY KEY`. **Never** `DEFAULT gen_random_uuid()` on a column (enforced by `forbidden-migration-patterns.txt`).
- Application generates IDs via `com.github.f4b6a3:uuid-creator` + a custom Hibernate `BeforeExecutionGenerator` (wired at S-022).
- Reference-data seeds use fixed canonical UUID v7 literals captured by `src/test/resources/scripts/GenerateCanonicalUuids.java` and embedded as literals in the migration. Re-running the script produces bit-identical output forever.
- Aggregate-root rows (per [ADR 0018](../../docs/modernization/adrs/0018-domain-model-ddd-aggregates.md)) carry a 3-letter external prefix — `psn_` / `clb_` / `usr_` for the identity triad. Internal entities keep raw UUIDs. The prefix is a presentation concern; DB column comments document the mapping (`V2__identity_and_reference.sql` aggregate-root `COMMENT ON COLUMN` blocks).

## Index shape on soft-delete-capable tables — S-014

Tables that carry a `deleted_on TIMESTAMPTZ NULL` column (soft-delete capable) follow these index rules:

1. **Default to partial:** every index on a soft-delete table must include `WHERE deleted_on IS NULL` unless the index deliberately needs to cover tombstoned rows. Reason: tombstones accumulate over multi-year retention windows (Swiss OR Art. 957a → 10 years for invoices), and indexes that cover them bloat indefinitely while serving zero hot-path queries.
2. **Deliberate-tombstone-coverage requires an inline comment:** when an index covers tombstones on purpose, add a `-- covers tombstones: <reason>` comment immediately above the `CREATE INDEX`. Two known reasons:
    - **CASCADE join target:** an index on a child-side FK whose parent's `ON DELETE CASCADE` runs needs to find soft-deleted children too, so the cascade reaches them. Example: `ix_pda_planning_day` on `planning_day_assignment(planning_day_id)` (`src/main/resources/db/migration/V4__reservations_planning_accounting.sql:312-313`).
    - **Tables with no soft-delete:** snapshot / append-only / CASCADE-only tables don't carry `deleted_on`; indexes on them cover all rows by construction. Add a comment naming the absence. Example: `ix_dcti_test` on `delivery_creation_test_item` (`V4__reservations_planning_accounting.sql:573-575`).
3. **Unique partial indexes use the same predicate:** if a UNIQUE constraint is partial on `deleted_on IS NULL`, the partial predicate goes in the index definition (`CREATE UNIQUE INDEX … WHERE deleted_on IS NULL`), not in the table-level `CONSTRAINT … UNIQUE`. Postgres `UNIQUE CONSTRAINT` syntax doesn't accept partial predicates.

**Canonical positive example:** `ix_arv_pilot` (`V4__reservations_planning_accounting.sql:237-239`) — `(pilot_person_id, reservation_start DESC) WHERE pilot_person_id IS NOT NULL AND deleted_on IS NULL`. Hot-path calendar query; partial predicate keeps the index narrow as old reservations get soft-deleted.

**Caught in S-014 review:** 7 indexes were silently non-partial before the rework pass; 4 were corrected, 3 documented as deliberate-tombstone-coverage (CASCADE join, no-soft-delete parent, deferred perf tuning to S-108).
