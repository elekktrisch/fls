---
id: S-015
title: Testcontainers test-DB strategy + helpers
epic: E-02
status: todo
depends_on: [S-009]
acceptance:
  - Test-DB strategy decided: **Testcontainers Postgres + transactional rollback per test** (recommended) or per-class clean migration. Document the decision.
  - A shared `@SpringBootTest`-with-test-DB base class is committed.
  - A `@WithTenant(clubId)` annotation or helper sets tenant context before each test (precondition for ADR 0008's tenant filter — see also S-022).
  - The hello-endpoint integration test from S-001 runs against the Testcontainers DB.
estimate: M
adr_refs: [0003, 0008]
parity_test: none
refined: true
refined_at: 2026-05-16
refined_speculative: true
refined_speculative_at: 2026-05-16
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context
ADR 0003 deferred this to a phase-4 story. Choice has real implications for test runtime — transactional rollback is fast (~10ms per test); per-class clean migration is slower (~1s) but cleaner.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `org.testcontainers:postgresql` + `spring-boot-testcontainers`.
- [ ] Decide: transactional rollback (recommended for most tests) + per-class clean migration for tests that test transaction boundaries themselves.
- [ ] Build the base class(es).
- [ ] Add `@WithTenant` annotation backed by a JUnit extension.
- [ ] Add a "switch tenant" helper for tests that need to exercise cross-tenant behavior.
- [ ] Add a "no tenant" helper for tests that need to exercise the unscoped path (S-023).

## Notes
Testcontainers reuses a single Postgres container across the test JVM (`reuse=true`) — 30s startup amortized. Without reuse, every test class pays the cost.

<!-- modernize-refine: start -->

## Design notes

### AC1 reinterpretation — `PostgresTestContainerLifecycle`, NOT the Testcontainers library

The story's "Testcontainers Postgres" wording predates the sandbox finding from S-010/S-011/S-009. Testcontainers 1.21.x's bundled docker-java 3.4.x negotiates Docker REST API 1.32; the sandbox daemon requires ≥1.44. The working alternative is **`PostgresTestContainerLifecycle`** (`next/server/src/test/java/ch/fls/server/testsupport/`) — `docker run -d ... postgres:17.4-alpine`, port read via `docker port`, JDBC readiness poll, JVM shutdown hook.

S-015 **does NOT add** `org.testcontainers:postgresql` to the dep graph. AC1 is reinterpreted as: "Test-DB strategy = Docker-CLI-managed Postgres via `PostgresTestContainerLifecycle` + per-test transactional rollback (default) or per-class clean-migrate (opt-out per documented escape hatch)."

If the docker-java API negotiation constraint lifts later (newer Testcontainers or sandbox tooling), revisit. Not in S-015's scope.

### Strategy decision — transactional rollback (chosen)

Spring TestContext's default `@Rollback(true)` + class-level `@Transactional` gives per-test isolation in ~10ms. Container starts once per JVM; Flyway migrates once. Test classes share the application context via Spring's context cache (~500ms cache hit vs ~15s rebuild).

Escape hatches for the rare cases that need real commits:
- `@Transactional(propagation = NOT_SUPPORTED)` per method (runs outside the test's tx).
- `@Rollback(false)` + explicit `@AfterEach` cleanup.
- A future `PostgresCleanMigrationTest` sibling base class — defer until a real test demands it (YAGNI).

Known-broken with rollback (document):
- Tests that span `MockMvc` requests with a separate request transaction.
- Tests that themselves call Flyway (e.g. `FlywayBootstrapIntegrationTest`'s adversarial cases). Those keep their own static-lifecycle pattern and don't extend the base class.
- DDL inside `DO $$` blocks (Postgres-specific).

### Artifact layout

New test-support sources under `next/server/src/test/java/ch/fls/server/testsupport/`:

| File | Action | Content |
|---|---|---|
| `PostgresIntegrationTest.java` | new | Abstract base class. Hosts the static `PostgresTestContainerLifecycle` + `dockerAvailable()` gate + `@DynamicPropertySource` datasource/Flyway wiring + class-level `@SpringBootTest` + `@ActiveProfiles("test")` + `@Transactional` + `@EnabledIf("dockerAvailable")` + `@ExtendWith(TenantContextExtension.class)`. |
| `WithTenant.java` | new | Method-and-class-level annotation: `@WithTenant(long clubId)`. Meta-annotated `@ExtendWith(TenantContextExtension.class)`. |
| `TenantContextExtension.java` | new | JUnit 5 `BeforeEachCallback`/`AfterEachCallback`. Reads `@WithTenant` (method-then-class), stores `clubId` in `TenantTestContext`. At S-015: store-only. S-022 swaps the body to push into `SecurityContextHolder` / `CurrentTenantIdentifierResolver`. |
| `TenantTestContext.java` | new | `ThreadLocal<Long>` holder with `set(Long)`, `current() -> Optional<Long>`, `clear()`. Plus `runAs(Long clubId, Runnable)` for mid-test switching and `runUnscoped(Runnable)` for the explicit S-023 unscoped path. |
| `TenantTestSupportArchTest.java` | new | ArchUnit (or hand-rolled package-scan) rule: no class outside `ch.fls.server.testsupport..` may reference any class inside it. Belt-and-braces beyond Maven test-scope isolation. |
| `PostgresIntegrationTestSmokeIT.java` | new | Meta-tests: base class boots + Flyway migrated + transactional rollback isolates methods + `@WithTenant(42)` captures + no-annotation → `Optional.empty()` + Postgres-backed hello smoke. |

Existing classes (refactor in same PR):
- `FlywayBootstrapIntegrationTest` keeps its existing static-lifecycle + adversarial-case tests (checksum drift, OOO). It does NOT extend the new base class because those tests use FRESH schemas not the shared container.
- `HelloControllerIT` (currently `@WebMvcTest`-sliced) stays as-is. AC4's "hello smoke runs against test DB" is satisfied by a NEW test (`HelloEndpointPostgresIT extends PostgresIntegrationTest`) rather than rewriting the slice test.

### Base class signature

```java
@SpringBootTest
@ActiveProfiles("test")
@Transactional                                // default rollback per test
@EnabledIf(value = "ch.fls.server.testsupport.PostgresIntegrationTest#dockerAvailable",
           disabledReason = "Docker unreachable; start Docker Desktop / Engine")
@ExtendWith(TenantContextExtension.class)
public abstract class PostgresIntegrationTest {

    protected static final PostgresTestContainerLifecycle POSTGRES = new PostgresTestContainerLifecycle();
    private static final boolean DOCKER_AVAILABLE = tryStart(POSTGRES);

    public static boolean dockerAvailable() { return DOCKER_AVAILABLE; }

    @AfterAll static void stopContainer() { POSTGRES.stop(); }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",                POSTGRES::jdbcUrl);
        r.add("spring.datasource.username",           POSTGRES::username);
        r.add("spring.datasource.password",           POSTGRES::password);
        r.add("spring.datasource.driver-class-name",  () -> "org.postgresql.Driver");
        r.add("spring.flyway.url",                    POSTGRES::jdbcUrl);
        r.add("spring.flyway.user",                   POSTGRES::username);
        r.add("spring.flyway.password",               POSTGRES::password);
    }

    private static boolean tryStart(PostgresTestContainerLifecycle lc) {
        try { lc.start(); return true; }
        catch (Throwable t) {
            System.err.println("[fls-server] Docker unreachable: " + t.getMessage());
            return false;
        }
    }
}
```

### `@WithTenant` shape — annotation now, real wiring at S-022

S-015 ships the **surface**; S-022 ships the **behavior**.

- At S-015: `@WithTenant(clubId=...)` → `TenantContextExtension` writes `clubId` to `TenantTestContext` (thread-local). Tests can inject a `TenantContextProbe` bean (test-only `@TestConfiguration`) to read it back.
- At S-022: the extension body is swapped to also set the value on whatever `CurrentTenantIdentifierResolver` reads (likely `SecurityContextHolder` with a stub authentication carrying the `clubId` claim). Tests don't change.
- **Default state (no `@WithTenant`):** `TenantTestContext.current()` returns `Optional.empty()`. S-022's resolver, seeing empty from both test and security context, returns the fail-closed sentinel `__no_tenant__` (S-009 invariant). Forward-compatible.
- **`TenantTestContext.runUnscoped(Runnable)`** is the EXPLICIT API for tests that exercise the legitimate unscoped path (S-023). Named distinctly so reviewers see it; "forgot to annotate" is NOT the same thing.
- **Mid-test switching:** `TenantTestContext.runAs(clubId, () -> { ... })` saves prior tenant, sets new, restores in `finally`. Critical for S-024's leakage-pattern tests ("create as A → switch to B → assert empty read").

### AC4 — Postgres-backed hello smoke (new test, not retarget)

`HelloControllerIT` is currently `@WebMvcTest(HelloController.class)` — slice test with no DataSource. Don't convert it (loses the slice-test pattern + inflates runtime). Instead, ship a new `HelloEndpointPostgresIT extends PostgresIntegrationTest` that calls `GET /api/v1/hello` via `TestRestTemplate` (or `MockMvc`) and proves the base class works for a controller-level integration test. Same assertions as the existing slice test; different boot path.

### Alternatives considered

- **Chosen — Docker-CLI lifecycle helper + `@Transactional` rollback + shared static container.** Honest about the sandbox constraint, fast per-test, single base class.
- **Rejected — `org.testcontainers:postgresql`.** API negotiation against the sandbox daemon doesn't work; precedent set by S-010/S-011/S-009.
- **Rejected — per-class clean migration.** ~1s × N classes overhead for no isolation benefit over `@Transactional` at S-015's scale.
- **Rejected — H2 with MODE=PostgreSQL.** Adequate for `@WebMvcTest` / `@DataJpaTest` slices but ADR 0008's `@TenantId` + ADR 0002's Postgres-only types (jsonb, etc.) make H2 a fidelity hazard once S-022+ entities land. H2 stays as the application-test.yml default for slice tests; integration tests extend `PostgresIntegrationTest`.
- **Rejected — convert `HelloControllerIT` to `@SpringBootTest`.** Loses the `@WebMvcTest` slice pattern; doubles CI cost. New test instead.

### Integration with other stories

**Inputs:**
- S-009: `PostgresTestContainerLifecycle`, `application-test.yml` baseline (H2 default), Flyway autoconfig + V1.
- S-001: `@SpringBootTest` conventions.

**Outputs (consumed downstream):**

| Story | Consumes | Use |
|---|---|---|
| S-022 (`@TenantId` resolver) | `@WithTenant` + `TenantContextExtension` swap-in point | Implements the actual resolver behavior |
| S-023 (UnscopedTenantContext) | `TenantTestContext.runUnscoped` | Test-side surface for unscoped sessions |
| S-024 (cross-tenant leakage CI) | `TenantTestContext.runAs(clubId, ...)` | Canonical leakage pattern (A → B switch) |
| Every Phase-B+ DB integration test | `extends PostgresIntegrationTest` | One-line setup; no boilerplate |

### Module layout

`ch.fls.server.testsupport` (test-only): hosts everything S-015 ships. Production code MUST NOT reference this package — ArchUnit rule enforces. The package already exists from S-009 (`PostgresTestContainerLifecycle`); S-015 adds the base class + tenant primitives + smoke meta-tests.

## Edge cases & hidden requirements

### Per-AC edge cases

**AC1 — strategy choice:**
- `@Transactional` rollback breaks for `MockMvc` requests (request runs in own tx; outer rollback can't undo committed work). Controller ITs needing real commits opt out.
- Rollback hides bugs where code relies on auto-flush ordering or DB-side constraints firing at commit. Document the per-class clean-migrate alternative.
- Tests calling Flyway themselves (adversarial cases) must opt out — `FlywayBootstrapIntegrationTest` is the precedent.
- Postgres sequences do NOT reset on rollback. Tests asserting on hardcoded IDs flake on re-run order. Document.

**AC2 — base class:**
- Static container field must be on the base class (not per-subclass). Otherwise every subclass restarts the container (~30s × N).
- `@DynamicPropertySource` referencing JVM-wide port → all subclasses share one context (Spring context-cache hit). If port differs per subclass → cache thrash.
- Docker unavailable → `@EnabledIf` skip pattern from S-009. CI runners must have Docker so skips can't hide regressions.

**AC3 — `@WithTenant`:**
- ThreadLocal + JUnit parallel execution (`junit.jupiter.execution.parallel.enabled=true`) → cross-test bleed. Pin `parallel.enabled=false` in `junit-platform.properties`; meta-test asserts it.
- Null/blank `clubId` on the annotation: forbid at compile (`long`, not `Long`). Sentinel "unscoped" goes through `runUnscoped` only.
- Concurrent execution race when a test uses `runAs(...)` inside a `@Test` method — try/finally restores prior state.

**AC4 — hello smoke:**
- `HelloControllerIT` is `@WebMvcTest` slice. Don't rewrite. Add NEW `HelloEndpointPostgresIT extends PostgresIntegrationTest`.

### Hidden requirements

- **`junit-platform.properties`** under `next/server/src/test/resources/`: pin `junit.jupiter.execution.parallel.enabled=false`. Meta-test asserts.
- **ArchUnit rule** (or equivalent package-scan test): `ch.fls.server.testsupport..` unreachable from `src/main/java`. Belt-and-braces beyond Maven test-scope.
- **`generate_statistics=true`** in `application-test.yml` (forward-looking — useful for N+1 detection helpers in S-022+).
- **Container CI flag:** `withReuse(System.getenv("CI") == null)` if Testcontainers' reuse is ever wired (not at S-015; defer).
- **Documentation note in `next/server/CONVENTIONS.md`:** "Test infrastructure for DB-touching tests" section. The S-009-set pattern (Docker-CLI direct, single static container, `@EnabledIf` gate) gets a paragraph on `extends PostgresIntegrationTest` + the rollback-by-default rule + the `@WithTenant` annotation surface.

### Scope clarifications

**In scope:**
- Base class `PostgresIntegrationTest`.
- `@WithTenant` annotation + `TenantContextExtension` + `TenantTestContext` (test-only).
- `TenantTestSupportArchTest` (or equivalent) ArchUnit rule.
- 5-6 meta-tests in `PostgresIntegrationTestSmokeIT`.
- `HelloEndpointPostgresIT` (new Postgres-backed hello smoke).
- `junit-platform.properties` pinning parallel=false.
- CONVENTIONS.md update.

**Out of scope:**
- Actual tenant resolver wiring (S-022).
- `UnscopedTenantContext` mechanism (S-023).
- Cross-tenant leakage CI test (S-024).
- Converting `HelloControllerIT` from slice to integration (deliberately).
- `PostgresCleanMigrationTest` sibling base class (YAGNI).

### NFR call-outs

- **Performance:** transactional rollback ~10ms/test; per-class clean-migrate ~1s. Container shared across JVM. Spring context cached across subclasses sharing config.
- **Maintenance:** one base class lifts the static-container boilerplate currently duplicated in `FlywayBootstrapIntegrationTest`. Without it, each new DB test re-implements.
- **Observability:** base class logs `container=<name> port=<n>` once at start (mirrors S-010's pattern) for CI log diagnosis.
- **Security:** see Security plan — primary concern is `@WithTenant` leaking into production code.

### Things not the right shape

- AC1 "Testcontainers Postgres" — reinterpret as docker-CLI helper.
- AC3 `@WithTenant` wiring — defer real wiring to S-022; ship the annotation surface here.
- AC4 hello smoke — new test, don't rewrite the slice test.
- Tasks line 24 (`org.testcontainers:postgresql + spring-boot-testcontainers`) — DELETE; not added.

## Security plan

### Threat model

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| (a) | `@WithTenant` leaks into `src/main/java` and becomes a production auth bypass | High | ArchUnit rule (or package-scan test) asserts `testsupport..` unreachable from `src/main`; Maven test-scope isolation is the structural defense |
| (b) | Test without `@WithTenant` silently "passes" tenant assertions | High | Default state is `Optional.empty()` → S-022 resolver fails closed with sentinel `__no_tenant__` → queries return zero rows. Tests pass only if they ACTIVELY assert the absence; can't accidentally prove "no leakage" |
| (c) | Test pollution across tests via `@Rollback(false)` opt-out | Med | Explicit transactional default; `@Rollback(false)` requires review (annotate with `@PersistsAcrossTests` for CI lint) |
| (d) | Resolver dual-source confusion (test context vs SecurityContext) | Med | Document precedence: test-context wins iff active; otherwise SecurityContext; otherwise sentinel. Assert in S-022 |
| (e) | Container reuse across CI shards | Low | `reuse=true` is intra-JVM only; not a security boundary. Document |

### Authorization

- **Test-level:** `@WithTenant` sets test-only tenant context. No role gate at S-015 — that's S-026's territory.
- **Production-level:** the eventual S-022 resolver reads BOTH the test-extension store AND `SecurityContextHolder`, test-context-wins-only-when-active. The precedence rule is documented in `TenantTestContext`'s JavaDoc.

### Input validation

`@WithTenant(long clubId)` — primitive `long`, no user input. Sentinel `__no_tenant__` is a code constant.

### PII handling

- Tests insert PII inside transactions; rollback cleans. Tests with `@Rollback(false)` need explicit `@AfterEach` cleanup.
- Container is ephemeral (no volume mount); JVM exit destroys data.
- No PII in baseline Flyway migrations (S-009 invariant carries forward).

### Audit-log events

N/A — test infrastructure.

### Cross-tenant leakage

`@WithTenant` exists precisely to support S-024's leakage CI pattern. Canonical use:

```java
@WithTenant(clubId=1L) @Test void cannot_see_other_clubs_flights() {
    flightRepository.save(new Flight(...));    // saved as club 1
    TenantTestContext.runAs(2L, () -> {
        assertThat(flightRepository.findAll()).isEmpty();  // tenant-scoped → empty
    });
}
```

The `runUnscoped` helper is the legitimate cross-tenant access path (system-admin reports, OGN ingest) — named distinctly so it's NEVER conflated with "forgot to annotate."

### OWASP applicability

- **A01 Broken Access Control:** `@WithTenant` IS the test-side AC infrastructure. If it bleeds to prod or precedence is wrong, AC breaks. Mitigations: ArchUnit + precedence rule.
- **A04 Insecure Design:** test-only annotation MUST be unreachable from `src/main/java`. Structural enforcement (package + ArchUnit), not code review.
- **A05 Security Misconfiguration:** Testcontainers binds to loopback by default — assert in helper config. CI runs ephemeral (`reuse=false`); dev opt-in via `~/.testcontainers.properties`.
- **A06 Vulnerable Components:** N/A — no new external deps.
- **A08 Software & Data Integrity:** `reuse=true` survives between runs on dev; never in CI.

### Story-specific concerns

- **Package gate (primary):** ArchUnit rule `noClasses().resideOutsideOfPackage("ch.fls.server.testsupport..").should().dependOnClassesThat().resideInAPackage("ch.fls.server.testsupport..")` — fails build if any `src/main` class references the test-support package.
- **Maven scope isolation (secondary):** test-scope classes invisible to `src/main` compilation. ArchUnit is belt-and-braces.
- **Default tenant state:** `Optional.empty()` for absent `@WithTenant`. Forward-compatible with S-022's fail-closed resolver.
- **Helper API surface to lock down:** `@WithTenant`, `TenantContextExtension`, `TenantTestContext.{set, current, clear, runAs, runUnscoped}`. Nothing else.
- **CI runs ephemeral:** `withReuse(System.getenv("CI") == null)` if/when reuse is wired.

## Test plan

### Coverage contract

**S-015 owns:**
- Base class boots; Flyway runs once per JVM; container shared.
- Transactional rollback isolates `@Test` methods.
- `@WithTenant` annotation captures `clubId` readable via `TenantContextProbe`.
- No-annotation default → `Optional.empty()`.
- ArchUnit / package-scan rule fails build if testsupport is referenced from src/main.
- Postgres-backed hello smoke runs cleanly.
- `junit-platform.properties` pins parallel=false.

**S-015 does NOT own:** tenant resolver wiring (S-022), unscoped mechanism (S-023), leakage CI (S-024).

### Test pyramid

Integration-only per stack convention. Meta-tests for the base class itself:

- `base_class_boots_with_flyway_migrated` — extends base class; queries `flyway_schema_history`; asserts ≥ 1 row with `version='1', success=true`.
- `transactional_rollback_isolates_methods` — two `@Order`-ed `@Test` methods; A inserts into `_rollback_probe`; B asserts empty. Setup table created outside the managed transaction.
- `with_tenant_annotation_captures_club_id` — `@WithTenant(clubId=42L)` on a `@Test`; `TenantContextProbe` reads back; assert 42.
- `no_with_tenant_yields_no_tenant_sentinel` — `@Test` WITHOUT `@WithTenant`; `TenantContextProbe.current()` returns `Optional.empty()`.
- `tenant_switch_mid_test_restores_prior` — `@WithTenant(1L)`; inside test call `runAs(2L, () -> assertCurrent(2L))`; after the block `assertCurrent(1L)`.
- `unscoped_helper_yields_empty_context` — `runUnscoped(() -> assertEmpty())`.
- `archunit_testsupport_unreachable_from_main` — ArchUnit rule fires; passes (no main class references testsupport).
- `parallel_execution_disabled` — reads system property `junit.jupiter.execution.parallel.enabled`; asserts false (or default).
- `hello_endpoint_runs_against_real_db` — new `HelloEndpointPostgresIT`; `GET /api/v1/hello`; same assertions as the slice test.

### Parity strategy

N/A — greenfield infrastructure.

### Test data + fixtures

- `PostgresTestContainerLifecycle` (S-009) — re-used.
- `_rollback_probe` table — created/dropped in `@BeforeAll`/`@AfterAll` outside the managed transaction (separate raw `DataSource.getConnection()`).
- `TenantContextProbe` — test-only `@TestConfiguration` bean exposing `TenantTestContext.current()`.

### Coverage gaps (deferred)

- Tenant filter actually filters rows by `clubId` → S-022.
- Unscoped escape hatch behavior → S-023.
- Cross-tenant leakage CI check → S-024.
- Per-class clean-migration base class variant → YAGNI; build when a real test needs it.

### Risks

- **Non-transactional writes bypass rollback** (raw JDBC, `@Async`, `Propagation.REQUIRES_NEW`). Document; tests using these opt into manual cleanup.
- **Parallel execution race** — pin parallel=false + meta-test asserts.
- **Docker-unavailable silent skips** — `@EnabledIf` from S-009; CI must have Docker so skips can't hide regressions.
- **Sentinel-shape drift between S-015 and S-022** — pin contract in `TenantTestContext` JavaDoc; S-022 implements against the named shape.

## Performance plan

### Hot paths

- JVM cold start (container + Flyway + first context): ~30s (p95). One-shot.
- First `@SpringBootTest` class boot: ~15s (p95) — Spring context init dominates.
- Subsequent same-config class boots: ~500ms (cache hit).
- Per `@Test` method: ~10-100ms (transactional rollback).
- `@WithTenant` setup: sub-ms.

### Required indexes

N/A — infra story.

### N+1 risks

N/A at S-015. Forward-looking: enable `spring.jpa.properties.hibernate.generate_statistics=true` in `application-test.yml` so S-022+ stories can write `assertQueryCount(n)` helpers.

### Cartesian / explosion risks

N/A.

### Caching strategy

- Spring `TestContext` cache: default size (32). Avoid `@DirtiesContext`.
- Container reuse: static field, JVM-scoped. No Testcontainers `withReuse(true)` until that lib is on-classpath.

### Latency budget

- JVM cold start p95 < 30s.
- First class boot p95 < 15s.
- Cached class boot p95 < 1s.
- Per `@Test` p95 < 100ms, p99 < 250ms.
- Suite formula: `30 + N×5 + M×0.1` seconds for N classes × M methods.

### Memory

- Spring context: 200-500 MB per cached context. Cap unique configs at 4.
- Postgres alpine container: ~70 MB RSS.
- Test JVM: `-Xmx2g` ceiling for CI.

### Performance test plan

- Meta-test `base_class_cold_boot_under_30s` — `@Timeout(30, SECONDS)`.
- Meta-test `context_cache_reuse_under_1s` — second `@SpringBootTest` with identical config.
- Meta-test `transactional_rollback_under_100ms` — 100 iterations insert-then-rollback; p95 < 100ms. Tagged `@Tag("perf-smoke")` for selective skip.
- Suite-level gate — wall-clock `./gradlew test` regression > 20% vs `main` baseline fails PR.

### Configuration choices

- `@Transactional` rollback by default (~10ms) vs per-class clean (~1s) — chosen for speed.
- `generate_statistics=true` — enables forward N+1 detection.

<!-- modernize-refine: end -->

