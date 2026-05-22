---
id: S-024
title: Cross-tenant leakage CI test (per-repository)
epic: E-03
status: todo
depends_on: [S-022, S-011]
acceptance:
  - A property-based test under CI exercises every repository: create data in tenant A; attempt to read it while tenant context is B; assert empty result (or 404 from a controller-level test).
  - The test is parameterized by the tenant-scoped entity list from S-011.
  - The test fails the build if added.
  - A separate dimension covers the "unscoped" path — explicitly running unscoped should return both tenants' data.
estimate: M
adr_refs: [0008]
parity_test: tests/multi-tenant/leakage-property-test.kt (or .java)
refined: true
refined_at: 2026-05-22
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer]
---

## Context
The CI-time enforcement that closes R1. ADR 0008 makes leakage structurally impossible from JPA, but: native SQL queries bypass the filter, and developers may add new repository methods that should respect tenancy. This test catches both.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Iterate the tenant-scoped entity list from S-011.
- [ ] For each, write a test that creates a row in tenant A, switches to tenant B, asserts the row is invisible.
- [ ] Add an assertion that controllers (the integration-test level) return empty list / 404 / 403 (per design) for cross-tenant attempts.
- [ ] Add an unscoped variant: same setup, but the read happens inside `runUnscoped(...)`; expect to see tenant A's row.
- [ ] Wire the test into CI to fail the build.

## Notes
Spec `25-multi-tenant-isolation.spec.ts` on the legacy side does this only on a sample. New system has zero excuse to skip any endpoint — every repository participates.

<!-- modernize-refine: start -->

## Design notes

### Layout

New test package `ch.alpenflight.multitenancy.leakage` under `src/test/java`, three classes:
- `LeakageSweepIT` — `@SpringBootTest` extending `PostgresIntegrationTest`; the per-repository parameterized sweep.
- `NativeSqlRegisterTest` — pure JUnit (no Spring); parses `native-sql-register.md`, text-greps `src/main/java/**`.
- `CrossTenantNotFoundContract` — `@Nested` mixin / abstract base; the witness pattern future tenant-scoped controllers extend. Existing per-aggregate `*TenantIsolationIT` stay as spot-checks; the sweep is the catalog-driven generic guard.

Sits separate from `arch/` (bytecode-only ArchUnit) and from `server/migration/TenantCatalogConsistencyTest` (schema-shape, no live data).

### Discovery

Enumerate entities via classpath reflection on `@TenantId`-annotated fields (using ArchUnit's `ClassFileImporter` or Spring's `EntityScanner`). For each, look up the `JpaRepository<E, ?>` bean in the `ApplicationContext` (Spring `Repositories` helper). Rejected: hard-coded list (rots), YAML-driven discovery (would force YAML drift to be re-checked here when S-022 already owns that). Aggregate-internal entities (`InOutboundPoint`; future `flight_crew`, `delivery_item`) carry no own `@TenantId` and are naturally skipped — their tenancy is asserted via the parent aggregate's sweep, plus an ArchUnit rule (added here) rejecting `JpaRepository` declarations against them.

**Floor assertion.** At sweep boot, fail loud if discovered entity count < current floor (start at `2`; bump as entities land — boyscout). Catches a classpath / annotation regression that would silently produce an empty sweep.

### CRUD primitive

Per-entity row builder in `testsupport`: one method per `@TenantId`-bearing entity returning the persisted entity under the currently-active tenant. Uses `EntityManager.persist` (not service layer — bypasses validators; the sweep tests the discriminator, not business rules). Tenant column resolves through the resolver — the builder never sets it. New `@TenantId` entity in a future story = same PR adds its builder. (ArchUnit follow-up: assert builder presence.)

Rejected: pure JDBC INSERT (skips the JPA write-path, missing the second resolver branch); reflective minimal-object construction (brittle for FK-bearing aggregates).

### Two assertion dimensions

- **Negative.** Insert under `runAs(CLUB_A)`; switch to `runAs(CLUB_B)`; assert `repository.findAll().isEmpty()` AND `repository.findById(id).isEmpty()`. Both — `findById` alone misses `findAll` filter bugs and vice versa.
- **Positive baseline.** Same insert; read under `runAs(CLUB_A)` returns the row. Guards "test passes because everything is empty."
- **Cross-tenant positive sweep.** For YAML `kind: cross-tenant` entries with an exposed `JpaRepository` (Country, future Person / Aircraft): insert as A, read by PK as B, assert the row is visible. NOT a leak — guards the sacred-cow cross-club crew + the 2026-05-16 Aircraft amendment.

### Unscoped dimension — AC4 deferred

AC4 says "explicitly running unscoped should return both tenants' data." Reality: `TenantTestContext.runUnscoped()` aliases to `NO_TENANT` (nil-UUID sentinel) and is fail-closed — zero rows on read, FK rejection on insert. The "see both" mechanism (Hibernate filter-bypass with whitelist + role gating) lives in S-023, deferred to Phase G. **S-024 asserts today's fail-closed contract** (`runUnscoped → findAll().isEmpty()` AND insert → `DataIntegrityViolationException` on `fk_<table>_club_id`) and ships a `@Disabled("unblocks: S-023")` stub holding the "see both" assertion. See Open question 1.

### Native-SQL register check

Pure-JUnit, no Spring context. Parses `native-sql-register.md` allow-list (each `### <id>` section's `Caller: path:line`). Text-greps `src/main/java/**/*.java` for `nativeQuery = true`, `createNativeQuery(`, `JdbcTemplate.*query`, `NamedParameterJdbcTemplate.*query`. Each hit's SQL string-literal is scanned for any tenant-scoped table name (derived from `@Table(name=...)` or CamelCase→snake_case of `@TenantId`-bearing entity classes). Hit not in allow-list → fail. Today the register is empty, so any hit fails the build. Expired entries (past `expires:`) **FAIL** the build (not warn — a CI warning gets scrolled past; renewal is cheap). Start with text-grep; promote to JavaParser AST if false-positives appear. See Open question 2.

### Controller witness

Single test: `LocationsController` (only tenant-scoped controller today) — authenticated user in tenant A, `GET /api/v1/locations/{id-of-B}` → 404. Future tenant-scoped controllers (S-046 Aircraft admin, future Flight) extend the `CrossTenantNotFoundContract` mixin declared here — no need to revisit S-024.

### Cross-story contracts

- **Consumes from S-011:** `tenant-rules.yaml` for cross-tenant positive sweep set; `native-sql-register.md` shape.
- **Consumes from S-022:** `TenantTestContext.runAs(uuid, body)`, `NO_TENANT` sentinel, `@WithTenant`; the resolver's fail-closed write contract on FK.
- **Produces:** (a) `CrossTenantNotFoundContract` mixin for future controllers, (b) tenant-scoped row-builder convention in `testsupport`, (c) `native-sql-register.md` becomes a live CI gate, (d) ArchUnit rule against repositories on aggregate-internal entities.

### ADR 0022 directive 2

Nothing pushes business logic into the DB. Hibernate `@TenantId` (ADR 0008) stays the chosen mechanism; RLS is not introduced here.

## Edge cases & hidden requirements

### AC4 vs. shipped reality (highest-impact)
AC4 asserts "unscoped returns both tenants' data," but `runUnscoped()` is fail-closed today (`NO_TENANT` → zero rows + FK reject). Real "see both" semantics block on S-023 (Phase G). S-024 reframes AC4 to test the fail-closed contract; the "see both" assertion ships disabled here and is unblocked by S-023's tests. Surfaced as Open question 1.

### Auto-coverage for future entities
S-024 must NOT need editing when S-050/S-051/S-053/S-064 land. Discovery is reflective. Adding an entity = `@TenantId` annotation + a row-builder in `testsupport`; the sweep auto-includes. The floor-assertion gets bumped opportunistically as a one-line boyscout in the landing story's PR.

### Aggregate-internal entities
`InOutboundPoint` and future `flight_crew` / `delivery_item` have no own `@TenantId`. The reflective sweep skips them naturally; their tenancy is enforced through the parent aggregate. The new ArchUnit rule rejects any future `JpaRepository<InOutboundPoint, ?>` (or similar) declaration in production code, which would expose them outside their aggregate root.

### Cross-tenant positive set
Only `kind: cross-tenant` entries with an exposed `JpaRepository` get the sacred-cow assertion. Person / PersonClub (not yet ported) are excluded today; sweep auto-picks them up once their repository lands.

### Hibernate version pin
Tenant filter semantics are version-dependent (ADR 0008 footnote). Assert `org.hibernate.Version.getVersionString()` starts with `tenant-rules.yaml::hibernate_pin` at test boot. Bump = single-PR deliberate change.

### Fixture coupling
CLUB_A / CLUB_B UUIDs already seeded by baseline migration; the sweep reuses. Boyscout: extract the duplicated `seedClub` blocks in existing `*TenantIsolationIT` classes into a shared `TwoClubFixture` helper.

### Test isolation
`@Transactional` rollback per test class — avoids `@DirtiesContext` cost across ~50+ method invocations. ThreadLocal carrier; parallel execution stays disabled (S-022 already pinned via `JunitPlatformConfigTest`). Pin in test base: `TenantTestContext.clear()` in `@AfterEach` regardless of rollback.

### CODEOWNERS on catalog files
`tenant-rules.yaml`, `tenant-catalog.md`, `native-sql-register.md` need security + tech-lead review on every change. S-011 flagged; S-024 wires the `.github/CODEOWNERS` rule if not already present in the repo.

## Security plan

### Threat model

| # | Vector | Severity | Caught by |
|---|---|---|---|
| (a) | New `@Entity` against a tenant-scoped table without `@TenantId`. | **Crit** | Reflective sweep: create-as-A / read-as-B returns rows → fail. |
| (b) | Native SQL (`nativeQuery = true`, `createNativeQuery`, `JdbcTemplate`) against a tenant-scoped table without an allow-list entry. | **Crit** | `NativeSqlRegisterTest` text-grep + register parse. Today's empty register = any hit fails. |
| (c) | Aggregate-internal entity gets its own `@Repository` exposed in production. | High | ArchUnit rule declared here; no `JpaRepository<InternalEntity, ?>` permitted outside the aggregate root's `infra` package. |
| (d) | Resolver drift: returns `null` instead of `NO_TENANT` → Hibernate ≥ 6.x silently un-filters. | **Crit** | Two assertions per `TENANT_SCOPED` entry: no-auth read returns empty AND no-auth insert fails on FK. Without (insert→fail), a `null` resolver un-filters writes silently. |
| (e) | Controller returns entity by PK without resolver in play. | High | `CrossTenantNotFoundContract` witness on `LocationsController`; future controllers inherit. |
| (f) | Catalog regression / empty sweep → silent green. | **Crit** | Floor assertion at boot (`discovered ≥ current_floor`); fail-loud on Hibernate-version-pin mismatch. |

(Skipped: existing `TenantBypassGuardTest` covers prod `TenantContextCarrier.set` callers; `TenantCatalogConsistencyTest` covers schema-shape — neither is re-done here.)

### Authorization
This story asserts **tenant isolation only**. RBAC (`@PreAuthorize`) is S-026's concern. Controller witness uses a single authorized role per resource so a 403 cannot mask the 404-vs-200 signal.

### Input validation / PII
N/A — synthetic test fixtures only; no user input. Add a regex assertion that fixture identifiers carry a `TEST-` marker / `@example.invalid` domain so a developer copying prod data into a fixture trips the build.

### Audit-log events
S-024 doesn't emit. When S-027's `AuditLogs` lands as `kind: tenant-scoped`, the sweep covers it automatically. The fail-closed write assertion catches a future audit emitter writing with the wrong `tenant_club_id`.

### OWASP applicability
A01 (primary — structural gate), A04 (meta — converts convention to enforcement), A05 (Hibernate-version-pin assertion).

### Story-specific pins
- Native-SQL register expiry = **FAIL** the build, not warn.
- Resolver fail-closed contract tested on both read AND write paths.
- CODEOWNERS on the three catalog files; wire here if not present.

## Test plan

### Layers
| Layer | Count | Strategy |
|---|---|---|
| Harness sanity | 2 | Plain JUnit; floor assertion + Hibernate-version-pin assertion. |
| Leakage sweep | 1 parameterized | `@SpringBootTest` + shared Postgres container; iterates `@TenantId`-bearing entities × {negative, positive baseline, fail-closed unscoped}. |
| Cross-tenant positive sweep | 1 parameterized | Same context; iterates YAML `kind: cross-tenant` entities with an exposed repository. |
| Controller witness | 1 | `MockMvc` against `LocationsController`; 404 on cross-tenant id. |
| Native-SQL register | 1 | Plain JUnit; text-grep + allow-list parse. No Docker. |

### Specific test cases (named)

Per-entity (parameterized over discovered `@TenantId`-bearing entities):
- `tenant_scoped_create_in_A_invisible_to_B` — happy negative; `findAll` AND `findById` both empty under tenant B.
- `tenant_scoped_returns_own_rows_under_self` — positive baseline.
- `tenant_scoped_no_tenant_sentinel_read_returns_zero` — fail-closed read assertion.
- `tenant_scoped_no_tenant_sentinel_insert_fails_on_fk` — fail-closed write assertion.

Cross-tenant positive (parameterized over `kind: cross-tenant` with exposed repository):
- `cross_tenant_findById_returns_other_clubs_row` — sacred-cow regression guard.

Singletons:
- `floor_holds_minimum_tenant_scoped_entity_count`
- `hibernate_version_matches_pin`
- `archunit_no_repository_on_aggregate_internal_entity` — declared here (ArchUnit rule under `arch/`).
- `controller_get_with_other_tenant_id_returns_404` — `LocationsController` via MockMvc.
- `native_sql_call_site_against_tenant_scoped_table_not_in_register_fails`
- `native_sql_register_expired_entry_fails`
- `disabled_unscoped_see_both_tenants` — `@Disabled("unblocks: S-023")` placeholder holding the future contract.

### Parameterization
JUnit 5 `@ParameterizedTest` over discovered entity classes. Not jqwik — the entity set is small (≤ 50) and enumerable; property-based adds nothing.

### Fixtures + isolation
Two seeded clubs (CLUB_A, CLUB_B) already in baseline migration. Per-entity row-builders in `testsupport`. `@Transactional` rollback per class; parallel disabled (carrier is ThreadLocal). `TenantTestContext.clear()` in `@AfterEach`.

### Parity strategy
N/A — greenfield CI tripwire. Legacy `25-multi-tenant-isolation.spec.ts` covers one endpoint via Playwright; this sweep covers every persisted aggregate.

### Build-failure semantics
Standard `./gradlew :server:test` failure. No fail-fast — the sweep enumerates so a regression names the leaking entity. Harness-sanity tests run first (`@Order(1)`).

## Performance plan

(N/A — CI test infrastructure, no hot path. Sweep runs against ≤ 50 enumerable entities in a single `@SpringBootTest` context with shared Postgres container; aggregate runtime is bounded by container reuse, not by test design.)

## Open design questions

1. **AC4 reframe.** Today's reality: `runUnscoped()` → `NO_TENANT` → zero rows + FK reject. "See both tenants" requires S-023's whitelist-gated bypass (deferred to Phase G). Specialists converge on: **(a)** assert today's fail-closed contract in S-024 + ship a `@Disabled("unblocks: S-023")` stub for the literal AC4 wording. Alternative **(b)** introduce a minimal Hibernate filter-bypass helper in S-024 that pre-empts S-023's design surface. Recommend (a). **Operator decision.**
2. **Native-SQL grep: text vs. AST.** Text-grep is simpler (no JavaParser dep) and the register is empty today, so false-positives are theoretical. AST is robust against comments / log strings. Recommend text v1; promote if false-positives bite. **Operator decision.**
3. **`target_table` YAML key.** Convention (CamelCase → snake_case) covers every current entry; `@Table(name=...)` overrides would silently break the native-SQL grep. Recommend convention + a JUnit assertion at sweep boot that every `@TenantId`-bearing entity's resolved table-name matches the convention or carries an explicit `target_table` YAML key. **Operator decision.**

<!-- modernize-refine: end -->
