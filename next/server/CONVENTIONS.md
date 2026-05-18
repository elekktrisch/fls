# Conventions — `next/server/`

Patterns established in shipped stories that future implementers should mirror.
Cite this file (and the canonical example) when answering "how do we do X?"
for a new contributor.

> **Citation discipline.** When pointing at a canonical example in another file, prefer naming the construct (e.g. `CREATE INDEX ix_arv_pilot`, `class FlightServiceTest`, `method @PreAuthorize on FlightController.locked()`) over a `file:line` range. Line numbers drift the moment the cited section is touched; construct names survive refactors. When a `file:line` is unavoidable, re-verify the citation after the section's first edit. The S-014 second-pass review found 3 of the 4 line citations in the index-shape rule below already wrong before V4 even merged — every citation that points by line is a citation that will go stale.

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

## Test infrastructure for DB-touching tests — S-009 / S-012 / S-015

- **Real DB only, no mocking.** Every `@SpringBootTest` shares a single
  Postgres 17 container; H2 was retired in S-012 once migrations started
  using `uuid` / `TEXT[]` / partial indexes / `COMMENT ON COLUMN` (H2 even
  in `MODE=PostgreSQL` can't parse all of that).
- **Shared container:** `src/test/java/ch/alpenflight/server/testsupport/SharedPostgresContainer.java`
  is a JVM-singleton that wraps `PostgresTestContainerLifecycle` — one
  container per JVM, lazily started on first reference, torn down by the
  shutdown hook. Flyway migrate is idempotent across class boots (V1+V2
  apply once; subsequent boots no-op via `flyway_schema_history`).
- **Container helper:** `src/test/java/ch/alpenflight/server/testsupport/PostgresTestContainerLifecycle.java`
  drives the container via the `docker` CLI (Testcontainers 1.21.x can't
  negotiate Docker API ≥1.44 in our sandbox). Image `postgres:17.4-alpine`
  pinned; readiness probe via JDBC `SELECT 1`.
- **Base class** — `extends PostgresIntegrationTest` (S-015) is the default
  for any full-stack DB-touching `@SpringBootTest`. It bundles
  `@SpringBootTest`, `@ActiveProfiles("test")`, the `SharedPostgresContainer`
  `@EnabledIf` guard, the `@DynamicPropertySource` for datasource + Flyway,
  and `@ExtendWith(TenantContextExtension.class)`. Subclasses re-declare
  `@SpringBootTest(webEnvironment = RANDOM_PORT)` only when they need a
  real port (HTTP integration tests via `TestRestTemplate`); MockMvc-based
  ITs inherit the default.
- **`@WithTenant` / `TenantTestContext`** — annotate a test method or class
  with `@WithTenant("019e30c3-2c00-7001-8000-000000000001")` (UUID string
  literal — annotations can't carry `UUID`) and the
  `TenantContextExtension` parses it to `UUID` and pushes it into the
  `ThreadLocal`-backed `TenantTestContext` before each test. Method-level
  beats class-level. Mid-test switching uses
  `TenantTestContext.runAs(uuid, () -> { ... })`; the legitimate unscoped
  path uses the distinctly-named `TenantTestContext.runUnscoped(...)`,
  which pushes the `NO_TENANT` (nil UUID) sentinel — "forgot to annotate"
  is `Optional.empty()` and is NOT the same thing. S-022 retrofits the
  extension to also push the value into Spring Security's context so the
  production resolver consumes the same tenant via the production path.
- **`junit-platform.properties`** pins `junit.jupiter.execution.parallel.enabled=false`.
  `TenantTestContext` uses a `ThreadLocal`; parallel test execution would
  alias sibling tests' tenant context. `JunitPlatformConfigTest` ratchets
  the value. Re-enable only after ADR 0021 is audited across the suite.
- **Isolation rule (ADR 0021):** tests own their data per-tenant
  (tenant-scoped) or by stable randomized natural key (cross-tenant) and
  **pre-clean at start, never teardown**. No `@AfterEach`, no global
  TRUNCATE, no `@Transactional` auto-rollback (it doesn't survive HTTP
  boundaries). Failed-test state stays in pgAdmin (`next/ops/dev-up-full.sh`)
  for inspection; re-running the same test pre-cleans its own data.
  Canonical example: `ClubsControllerIT` (per-test unique slug / clubKey).
  The shared `IntegrationTestSupport.createTestClub(...)` helper lands in
  S-022.
- **Test-support package boundary** — `ch.alpenflight.server.testsupport`
  is test-scope only; Maven test-scope isolation is the structural defense,
  and `TestSupportPackageBoundaryTest` is the belt-and-braces check that
  fails the build if any `src/main/java` class references the package
  (i.e. annotations like `@WithTenant` accidentally leak into production).
- **Docker guard** — every DB-touching class (or its base) carries
  `@EnabledIf("ch.alpenflight.server.testsupport.SharedPostgresContainer#available")`,
  so contributors without Docker still pass `./gradlew check` cleanly
  (tests skip rather than fail). `SharedPostgresContainer.available()`
  throws under `CI=true` instead of returning false, so a CI run on a
  hiccuping Docker daemon fails loudly rather than silently skipping every
  DB-touching test.
- **Static-asset tests** that only walk the classpath (no DB) live
  alongside the integration test in the same package, plain JUnit (no
  `@SpringBootTest`). Example: `MigrationFolderConventionsTest`,
  `TestSupportPackageBoundaryTest`.
- **Slice tests** (`@WebMvcTest`, `@DataJpaTest`, etc.) don't auto-configure
  a DataSource and don't need the shared container. Example:
  `HelloControllerIT`. The Postgres-backed companion
  `HelloEndpointPostgresIT` extends `PostgresIntegrationTest` and
  exercises the same endpoint through the full security chain via
  `JwtTestFixture` — both shapes coexist.
- **Canonical example:** `src/test/java/ch/alpenflight/server/migration/FlywayBootstrapIntegrationTest.java` (raw lifecycle pattern, predates the base class) /
  `src/test/java/ch/alpenflight/platform/hello/HelloEndpointPostgresIT.java` (S-015 base-class pattern).

## Multi-tenancy — S-022, ADR 0008

Discriminator-based per [ADR 0008](../../docs/modernization/adrs/0008-multi-tenancy-mechanism.md). Every tenant-scoped entity carries a `@TenantId` annotation on its `club_id` field; Hibernate appends `WHERE club_id = ?` to every JPA-mediated read and populates the column on every JPA-mediated write from the resolver.

- **Resolver:** `ClubTenantIdentifierResolver` (in `ch.alpenflight.platform.tenancy`). Precedence on each call: (1) the test seam `TenantTestContextAccess.current()`; (2) the JWT `clubId` claim when present + parseable; (3) `UserTenantLookup` by `keycloak_sub` (for federated users whose tokens lack a `clubId` claim); (4) the `NO_TENANT` nil-UUID sentinel. Spring Security's `JwtIssuerValidator` already authenticates the issuer before this resolver sees the token, so the claim is trusted when present.
- **No Hibernate properties:** the `@TenantId` annotation alone is enough in Hibernate 7. Do NOT set `spring.jpa.properties.hibernate.multi_tenancy` or `…tenant_identifier_resolver` defensively — Spring Boot's `BeanContainer` auto-discovers the resolver bean.
- **`Club` does NOT wear `@TenantId`** — it IS the tenant. Aggregate-internal children of `Club` (`MemberState`, future `ClubExtension`, …) carry the discriminator redundantly with their parent.
- **Fail-closed on no-tenant state:** reads filter on `club_id = NO_TENANT` and return zero rows (no real row carries the nil UUID); writes fail at the FK constraint on `club_id → club.id` (`fk_<table>_club_id`) — Postgres rejects the nil UUID with `DataIntegrityViolationException`.
- **Native SQL bypasses `@TenantId`:** `@Query(nativeQuery=true)`, `JdbcTemplate`, raw `DataSource.getConnection()` are NOT filtered by Hibernate. One production-code use exists: `UserTenantLookup` reads the cross-tenant `user` table by `keycloak_sub` (the resolver runs inside Hibernate's session-open path, so a JPA query would recurse). `user` carries no `@TenantId` per S-012, so the JDBC path doesn't bypass any tenant filter it should have honored. Any further native SQL in tenant-scoped code is a regression; S-024 will add the repo-wide CI sweep and an explicit `@UnscopedNativeQuery` marker for legitimate exceptions.
- **Indexes for hot paths:** every `@TenantId` column already has a single-column index (per S-012). Once a query joins a tenant-scoped table with another `WHERE` clause that filters significantly, prefer a composite index leading with `club_id` (e.g. `(club_id, start_date)`) so Postgres can index-only-scan the tenant slice.
- **Virtual threads:** the `SecurityContextHolder` strategy stays at default `MODE_THREADLOCAL`. JEP 491 (Java 25) removed `synchronized` pinning; Spring's `DelegatingSecurityContextRunnable` propagates the principal across `@Async` / `TaskExecutor` boundaries. Anti-pattern: raw `CompletableFuture.runAsync` / `Thread.startVirtualThread` without the delegating wrapper — the child thread sees no principal and the resolver returns `NO_TENANT`, which writes then reject at the FK layer.
- **UUID v7 generation:** every aggregate-root entity should mark its `id` field with `@UuidV7` (from `ch.alpenflight.platform.id`). The generator runs application-side via `com.github.f4b6a3:uuid-creator` and produces time-ordered keys that keep B-tree inserts monotonic. `gen_random_uuid()` defaults on PK columns are forbidden by `forbidden-migration-patterns.txt`.
- **Mock-auth profile:** `@Profile("mock-auth")` swaps in a hardcoded SYSTEM_ADMINISTRATOR principal with a `clubId` claim. `UserTenantLookup` is `@Profile("!mock-auth")` so the resolver bypasses DB fallback under mock-auth (no users seeded). Mock chain rip-out is deferred to S-026 (role enforcement).
- **Canonical example:** `MemberState` (`src/main/java/ch/alpenflight/clubs/MemberState.java`) — minimal mapping (PK + `@TenantId` discriminator + one business column) carrying the convention end-to-end. `MemberStateTenantIsolationIT` proves the filter contract.

## Typed entity IDs — S-022, ADR 0019

Aggregate-root identifiers are typed records, **not** raw `UUID`, once they leave the aggregate. The compile-time win: a `Club` id and a `Person` id are different types, so a service / controller / mapper signature cannot accidentally accept one in the other's slot.

- **Where typing applies:** service-layer parameters, controller path / body, DTOs, mapper outputs, REST URLs, JSON, logs. **Inside** the entity, the field stays raw `UUID` (keeps JPA / Hibernate / Spring Data simple); only the *getter* wraps it into the typed record. The getter is the seam — that's where the value leaves the aggregate.
- **Shape:** `record AggrId(UUID value)` — the record itself carries **no Jackson annotations**. Wire-format is centralised in `TypedIdJacksonModule` (`ch.alpenflight.platform.id`): one line per new typed-id family member registers a `ValueSerializer` (writes `toExternal()` as a JSON string) and a `ValueDeserializer` (calls `parse(String)`). Springdoc still needs `@Schema(type="string", pattern=…)` on the record so the OpenAPI spec emits a plain-string schema for the TS codegen — that's an OpenAPI hint, not Jackson. A Spring `@Component Converter<String, AggrId>` handles `@PathVariable` / `@RequestParam` binding.
- **External form (ADR 0019):** `<prefix>_<26-char Crockford Base32>`. The 26-char payload encodes the full 16-byte UUID with no loss; Crockford alphabet (`0-9a-hjkmnp-tv-z`) drops the four ambiguous letters (`i`, `l`, `o`, `u`). Examples: `clb_…` for `Club`, `psn_…` for `Person`, `usr_…` for `User`.
- **Internal entities** (per S-012 — `MemberState`, `ClubExtension`, `PersonClub`, …) keep **raw `UUID` at every layer**: no typed wrapper, no prefix. They rarely cross aggregate boundaries; if a future story externalises one (e.g. a per-club controller exposing `MemberState`), that story ships the typed wrapper (`MemberStateId`) **without a prefix** — the prefix scheme is reserved for aggregate roots so external readers can spot aggregate boundaries at a glance.
- **Canonical example:** `ClubId` (`src/main/java/ch/alpenflight/platform/id/ClubId.java`) + `ClubIdPathConverter` + the round-trip test in `ClubIdTest`. `Club.getId()` returns `ClubId`; `Club.id` (private field) stays `UUID`. `ClubsRepository extends JpaRepository<Club, UUID>` — the persistence layer keeps the raw type; `ClubsService` converts at its public methods (`id.value()` to call into the repo).
- **`@PreAuthorize` SpEL with typed IDs:** the JWT claim carries a raw UUID string. Compare via `#id.value().toString() == principal.claims['clubId']`, not `#id.toString()` (the latter is the prefixed external form).

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
    - **CASCADE join target:** an index on a child-side FK whose parent's `ON DELETE CASCADE` runs needs to find soft-deleted children too, so the cascade reaches them. Example: search for `CREATE INDEX ix_pda_planning_day` in `src/main/resources/db/migration/V4__reservations_planning_accounting.sql` (preceded by its `-- covers tombstones:` comment).
    - **Tables with no soft-delete:** snapshot / append-only / CASCADE-only tables don't carry `deleted_on`; indexes on them cover all rows by construction. Add a comment naming the absence. Example: search for `CREATE INDEX ix_dcti_test` in `V4__reservations_planning_accounting.sql`.
    - **Deferred perf tuning (case-by-case):** index shape pending production-scale plan analysis. Example: search for `CREATE INDEX ix_arv_location` in `V4__reservations_planning_accounting.sql` (annotated as deferred to S-108).
3. **Unique partial indexes use the same predicate:** if a UNIQUE constraint is partial on `deleted_on IS NULL`, the partial predicate goes in the index definition (`CREATE UNIQUE INDEX … WHERE deleted_on IS NULL`), not in the table-level `CONSTRAINT … UNIQUE`. Postgres `UNIQUE CONSTRAINT` syntax doesn't accept partial predicates.

**Canonical positive example:** search for `CREATE INDEX ix_arv_pilot` in `V4__reservations_planning_accounting.sql` — `(pilot_person_id, reservation_start DESC) WHERE pilot_person_id IS NOT NULL AND deleted_on IS NULL`. Hot-path calendar query; partial predicate keeps the index narrow as old reservations get soft-deleted.

**Caught in S-014 review:** 7 indexes were silently non-partial before the rework pass; 4 were corrected, 3 documented as deliberate-tombstone-coverage (CASCADE join, no-soft-delete parent, deferred perf tuning to S-108).

## API documentation (springdoc) — S-003

The OpenAPI spec at `/v3/api-docs` is the source of truth that the SPA's TS codegen ([ADR 0005](../../docs/modernization/adrs/0005-api-shape.md), S-004) consumes. Drift between the spec and the live API is a silent UI bug. These rules keep the spec lossless.

### Annotation discipline

- **Every `@RestController` method** carries `@Operation(summary = "<imperative verb phrase>", description = "<context for the SPA / Proffix consumer>")`.
- **Every public DTO record / class** carries `@Schema(description = ...)` on the type. Fields whose name isn't self-explanatory carry their own `@Schema(description = ...)`.
- **Every non-`200` response** declared with `@ApiResponse(responseCode = "...", description = ...)`. Typed error responses use `content = @Content(schema = @Schema(implementation = ProblemDetail.class))`.
- **Every DTO field** carries the Jakarta validation annotation that captures its real constraint (`@NotNull`, `@Size`, `@Pattern`, `@Min`, `@Max`). These flow into the spec and become client-side constraints — they ARE the input-validation contract.
- **Canonical worked example:** [`HelloController`](src/main/java/ch/alpenflight/platform/hello/HelloController.java) + [`HelloResponse`](src/main/java/ch/alpenflight/platform/hello/HelloResponse.java).

### Type placement

- **Promote DTOs to top-level classes/records.** Springdoc emits `OuterClass.NestedRecord` schema names for nested records — codegen tools choke on dotted identifiers.

### Security scheme

- **`bearerAuth` is a Components-level placeholder until S-020.** Do NOT attach `@SecurityRequirement(name = "bearerAuth")` to any operation until the corresponding controller has `@PreAuthorize`. A spec that claims auth-required while the controller accepts anonymous is a confused-deputy hazard for the codegen consumer.
- **No `clubId` / `tenantId` in tenant-scoped request bodies or paths** ([ADR 0008](../../docs/modernization/adrs/0008-multi-tenancy-mechanism.md) — `@TenantId` resolves it from the principal). The spec must reflect this for the codegen client to call endpoints with the right inputs.

### `@Schema(example = ...)` — PII discipline

Synthetic placeholders only. No realistic Swiss names, emails, phones, licence numbers, or club fixtures. The committed `next/web/openapi/openapi.json` is a public artifact in the repo's post-squash history; an example that ships there is forever-public.

Acceptable: `"example@example.test"`, `"+41 00 000 00 00"`, `"CH-XX-LICENCE-PLACEHOLDER"`. Not acceptable: real-looking Swiss surnames, real club names (Lommis, Birrfeld, …), real-format CH-licence numbers.

### Snapshot maintenance

- The committed `next/web/openapi/openapi.json` is the artifact S-004's codegen reads from.
- After any controller or DTO change: `./gradlew generateOpenApiSnapshot`. Commit the refreshed file alongside the controller/DTO change.
- `OpenApiSnapshotIT` (and `./gradlew compareOpenApiSnapshot`) fails the build when the committed snapshot drifts from the live spec. Failure message names the fix command.
