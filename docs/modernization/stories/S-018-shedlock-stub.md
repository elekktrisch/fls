---
id: S-018
title: ShedLock stub table in Flyway baseline
epic: E-02
status: todo
depends_on: [S-009]
acceptance:
  - The `shedlock` table is in V1__baseline (DDL per `net.javacrumbs.shedlock-provider-jdbc-template`).
  - The ShedLock dependency is added but **not** enabled — `@SchedulerLock` annotations are not yet applied to jobs.
  - A README under `alpenflight/server/src/main/resources/db/migration/` notes the migration path to multi-instance: flip a property + annotate jobs.
estimate: S
adr_refs: [0009]
parity_test: none
refined: true
refined_at: 2026-05-16
refined_speculative: true
refined_speculative_at: 2026-05-16
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context
ADR 0009 chose Spring `@Scheduled` in-process. Single-instance for now; if K8s migration ever introduces multiple replicas, ShedLock is the escape hatch. Bake the table now to avoid a schema change later.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `net.javacrumbs.shedlock:shedlock-spring` and `shedlock-provider-jdbc-template` as `<scope>provided</scope>` or commented dependencies — present but not active.
- [ ] Add the standard ShedLock table to V1__baseline.
- [ ] Write a 5-line README explaining the activation path.

## Notes
This is intentionally a stub — don't activate ShedLock or annotate jobs. Activating it on a single-instance deploy is harmless but pointless.

<!-- modernize-refine: start -->

## Design notes

### V1 immutability — ship as `V2__shedlock.sql`, NOT amend V1

AC1's "in V1__baseline" predates S-009 going `done`. V1's checksum is locked per `alpenflight/server/CONVENTIONS.md` and the in-file header at `V1__baseline.sql:10-12`. Amending V1 would break `flyway:validate` on every consumer DB. **Reinterpret AC1: the table ships as `V2__shedlock.sql`.**

### Artifact layout

| File | Action | Content |
|---|---|---|
| `alpenflight/server/src/main/resources/db/migration/V2__shedlock.sql` | new | Canonical ShedLock DDL verbatim from `shedlock-provider-jdbc-template` 7.7.0 + header comment explaining the stub posture |
| `alpenflight/server/build.gradle.kts` | edit | Add `implementation("net.javacrumbs.shedlock:shedlock-spring:7.7.0")` + `implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.7.0")` (`implementation` scope, not `compileOnly`/commented — honest classpath; classes load but are inert without `@EnableSchedulerLock`) |
| `alpenflight/database/tenant-rules.yaml` | edit | Add `shedlock` SYSTEM_GLOBAL override (parallel to `flyway_schema_history` / `app_meta` entries from S-009). Without it S-011's classifier emits UNKNOWN |
| `alpenflight/server/CONVENTIONS.md` | edit | Append "Background jobs / ShedLock — S-018" section with stub-vs-activate rule + future activation runbook |
| `alpenflight/server/README.md` | edit | Database-migrations section gains a "ShedLock activation playbook" subsection (NOT a separate README under `db/migration/` — that folder stays SQL-only) |
| `alpenflight/server/src/test/java/ch/alpenflight/server/migration/MigrationFolderConventionsTest.java` | extend | 3 new tests (V2 present + correct DDL shape + no `@EnableSchedulerLock` leaked in `src/main/java`) |
| `alpenflight/server/src/test/java/ch/alpenflight/server/migration/FlywayBootstrapIntegrationTest.java` | extend | 2 new tests (table exists with the 4 expected columns + PK; table is empty post-boot) |

### V2 DDL (exact)

```sql
-- V2__shedlock.sql
-- S-018 stub: bakes ShedLock's coordination table NOW so a future HA scale-out
-- (multiple replicas firing @Scheduled jobs) becomes a config flip + per-job
-- annotation, not a schema migration.
--
-- ADR 0009 chose Spring @Scheduled in-process. Single-instance today => no
-- LockProvider bean, no @EnableSchedulerLock, no @SchedulerLock annotations.
-- The table sits empty until the HA story (TBD) activates ShedLock.
--
-- DDL is the canonical shape published by
-- net.javacrumbs.shedlock-provider-jdbc-template 7.7.0 (TIMESTAMP, not
-- TIMESTAMPTZ — matches `usingDbTime()` semantics).
--
-- SYSTEM_GLOBAL: no club_id column, no @TenantId. Classified accordingly in
-- alpenflight/database/tenant-rules.yaml.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

No `IF NOT EXISTS` — would let drift pass silently. Column types must be `TIMESTAMP` (not `TIMESTAMPTZ`) to match `.usingDbTime()` semantics that the HA activation will use.

### Stub-vs-activate decision tree

Ship: V2 DDL + 2 `implementation` deps + tenant-rules.yaml SYSTEM_GLOBAL row + README/CONVENTIONS notes.

Do **NOT** ship: any `@Configuration` class with `@EnableSchedulerLock`; any `LockProvider` `@Bean`; any `@SchedulerLock` annotation; an `application.yml` `shedlock:` block.

Rationale: the staging discipline depends on a single chokepoint. Adding "well it's wired, might as well turn it on" creates a real DB round-trip per scheduled run for zero benefit, and the eventual HA cutover loses its sequence (table — deps — activation in three separate PRs is replaced by "all three happen at the cutover moment under pressure").

### Future activation runbook (lives in CONVENTIONS.md + README)

One PR per step when HA arrives:

1. **PR-1 — config.** Create `ch.alpenflight.platform.scheduling.SchedulerLockConfiguration`:
   ```java
   @Configuration
   @EnableSchedulerLock(defaultLockAtMostFor = "PT10M", defaultLockAtLeastFor = "PT5S")
   class SchedulerLockConfiguration {
       @Bean LockProvider lockProvider(DataSource ds) {
           return new JdbcTemplateLockProvider(
               JdbcTemplateLockProvider.Configuration.builder()
                   .withJdbcTemplate(new JdbcTemplate(ds))
                   .withTableName("shedlock")
                   .usingDbTime()
                   .build());
       }
   }
   ```
   Update CONVENTIONS.md's "staged not active" line. Remove the `no_enable_scheduler_lock_anywhere_in_classpath` guard test.
2. **PR-2…N — annotate jobs.** Each job port story (S-083+ DailyFlightValidationJob, etc.) gains `@SchedulerLock(name="<stable-id>", lockAtMostFor="PT<duration>", lockAtLeastFor="PT<minHold>")` on its `@Scheduled` method. `name` is the PK in `shedlock`; once chosen, renaming is a coordination event.
3. **PR-final — scale.** Helm/K8s replica count > 1. `.usingDbTime()` makes the DB the single source of truth across replicas.

### Downstream contract

- **S-081 (Spring `@Scheduled` infrastructure):** consumes nothing from S-018. ShedLock deps on classpath are inert without `@EnableSchedulerLock`.
- **S-083+ (port DailyFlightValidationJob etc.):** ship plain `@Scheduled` methods without `@SchedulerLock`. Stable name IDs are decided when ported.
- **HA story (not yet decomposed):** consumes V2 + the deps + every stable job-ID picked by S-083+; adds the config class + per-job annotations. Schema-touch-free.
- **S-024 (cross-tenant leakage CI):** `shedlock` must be allowlisted via its SYSTEM_GLOBAL classification (no `@TenantId`, by design).
- **S-027 (audit-log infrastructure):** MUST exclude `shedlock` from any global audit interceptor. ShedLock writes are operational metadata, not domain events.

### Alternatives considered

- **Chosen — real V2 + real deps + no activation code.** Honest classpath, honest schema, single chokepoint.
- **Rejected — commented-out / `compileOnly` deps.** Dishonest dep tree; breaks `failOnVersionConflict`-style audits; hides ShedLock from `dependencyInsight`.
- **Rejected — activate on single-instance.** One DB round-trip per `@Scheduled` invocation for zero benefit; staging discipline collapses; HA story has nothing left to do.
- **Rejected — skip the table now, add at HA cutover.** Defeats S-018's whole purpose: schema changes during an HA cutover (when Flyway checksums + replica rollouts + DB locks all interact) is exactly the operation we're avoiding.
- **Rejected — separate `db/migration/README.md`.** Pollutes the migration classpath (Flyway scans it). Fold the activation notes into `alpenflight/server/README.md` + `CONVENTIONS.md`.

### Module layout

No new server subpackage at S-018. `ch.alpenflight.platform.scheduling` is **reserved** for the future HA story to create (will hold `SchedulerLockConfiguration`). Creating it empty today is gold-plating.

## Edge cases & hidden requirements

### Per-AC edge cases

**AC1 — V1 immutability.** Already covered. Ship V2.

**AC2 — dep posture.** "Added but not enabled" has three interpretations: `compileOnly` (drift trap — compiles but missing at runtime), `implementation` (real classpath; inert without `@EnableSchedulerLock`), commented-out (dishonest dep tree). Pin `implementation`.

**AC3 — README location.** `alpenflight/server/src/main/resources/db/migration/` is reserved for `.sql`; non-SQL files pollute the classpath that `spring.flyway.locations` scans. Fold into `alpenflight/server/README.md`'s existing Database-migrations section + `CONVENTIONS.md`.

### Hidden requirements

- **`shedlock` SYSTEM_GLOBAL in `tenant-rules.yaml`** (parallel to `flyway_schema_history` / `app_meta`). Without it, S-011's classifier emits UNKNOWN.
- **`V2__shedlock.sql` header comment** explains the stub posture and references the activation runbook in CONVENTIONS.md.
- **Pin ShedLock version explicitly** (`7.7.0`) — Spring Boot 4.0.6 BOM does NOT manage ShedLock. Same pattern as Flyway Gradle plugin pin in S-009.
- **CI guard against premature activation.** Either a JUnit reflection test (`ShedLockNotActivatedTest` walking `ch.alpenflight.*` for `@EnableSchedulerLock`) OR a CI grep step. Recommend the JUnit test — same test infra as `MigrationFolderConventionsTest`, no extra CI plumbing.
- **Forward-looking note for HA story:** `locked_by` defaults to `${hostname}` — decide explicit `.withLockedByValue(...)` at activation; document in HA runbook.

### Scope clarifications

**In:** V2 SQL + 2 deps + tenant-rules.yaml override + CONVENTIONS.md section + README playbook + 5-6 tests across the 3 test classes.

**Out:**
- ShedLock activation → future HA story.
- Job porting (`@Scheduled` methods) → S-081 / S-083+.
- ShedLock observability + metrics → S-035.
- Manual-trigger admin endpoint → ADR 0009 follow-up.
- `migrator` vs `app_runtime` Postgres role split → S-013/S-016 ops.

### NFR call-outs

- **Performance — zero impact today.** Future-state per-job lock acquire: ~5-15ms on a warm pool. ~8 jobs × ~16 row writes/day in HA. Trivial.
- **Boot-time delta from the 2 deps on classpath:** ~30-50ms classpath scan addition. Measurable but well within S-009's < 30s `@SpringBootTest` budget.
- **Security:** see Security plan below — primary concern is A05 (accidental activation footgun).
- **Observability:** ShedLock logs INFO when active. S-018 ships no active code → no log output.

### Things not the right shape

- AC1 "in V1__baseline" — ship V2; covered.
- AC3 "README under `db/migration/`" — wrong location; fold into existing README + CONVENTIONS.
- Task line 23 uses Maven `<scope>provided</scope>` vocabulary — project is Gradle Kotlin DSL. Rewrite to `implementation(...)`.
- Implicit AC4 missing: `tenant-rules.yaml` SYSTEM_GLOBAL entry. Promote.
- Implicit AC5 missing: smoke test that `shedlock` exists post-V2. Promote.

## Security plan

### Threat model

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| (a) | `locked_by` leaks node identity (hostname / pod name) | Low | Forward-looking — operator-controllable via `.withLockedByValue(...)` at HA activation. Document; not S-018's concern. |
| (b) | Stale locks block jobs forever if node dies mid-execution | High (post-activation) | HA activation MUST mandate `lockAtMostFor ≤ 2 × jobMaxRuntime` on every `@SchedulerLock`. CI grep at that time. N/A at S-018. |
| (c) | Clock skew between app + DB | Med (post-activation) | `.usingDbTime()` in LockProvider config. Document in HA runbook. |
| (d) | ShedLock writes pollute audit log | Med (structural) | S-027's audit infrastructure MUST exclude `shedlock` from any global Hibernate listener. Document here so S-027 inherits. |
| (e) | **Accidental activation footgun** — future contributor adds `@EnableSchedulerLock` "because the deps are there" | **Med — primary concern** | CONVENTIONS.md explicit ban + JUnit reflection guard (`ShedLockNotActivatedTest`); covers source-tree adds and binary-pulled additions equally. |

### Authorization

- **DB-level (S-018):** none. No app code references the table. The migrator role creates it.
- **DB-level (post-activation):** app role gets `SELECT, INSERT, UPDATE, DELETE` on `shedlock`. No `TRUNCATE`, no `DROP`. Captured in the HA story's role-grant migration.
- **App-level:** no endpoints, no `@PreAuthorize`, no `@TenantId`. System-global by design.

### Input validation

N/A — ShedLock's own JdbcTemplateLockProvider issues parameterized SQL; no user-controlled identifiers reach the table.

### PII handling

- `name` (lock ID, e.g. `daily-flight-validation`): non-PII, system-global. Safe to log.
- `locked_by` (host / pod name): operational metadata, NOT PII under FADP/GDPR (machine ID, not natural person). Safe in logs.
- `lock_until`, `locked_at`: timestamps; no PII.

### Audit-log events

ShedLock writes are intentionally non-audited. Capture as explicit exclusion in S-027's spec (forward-looking inheritance).

### Cross-tenant leakage

`shedlock` is SYSTEM_GLOBAL. ADR 0008's "cross-tenant entities" allowance covers this — locks coordinate across all tenants by design. S-024's leakage CI test allowlists via the SYSTEM_GLOBAL classification (not a hardcoded skip).

### OWASP applicability

- **A04 Insecure Design (positive):** pre-baking the table at S-018 avoids a schema change at HA cutover. S-018 IS the A04 mitigation.
- **A05 Security Misconfiguration:** primary concern. Mitigations:
  1. `CONVENTIONS.md` explicit ban on `@EnableSchedulerLock` until HA.
  2. JUnit reflection guard `ShedLockNotActivatedTest` — durable, runs every build.
  3. README activation playbook lists the exact 4-step sequence so the HA story doesn't reverse-engineer it.
- **A06 Vulnerable Components:** pin `7.7.0` explicitly; add to Dependabot.
- **A08, A09, A03, A02, A07, A10:** N/A or already-addressed.

### Story-specific concerns

- **Forbid `@EnableSchedulerLock` until HA.** Enforcement options:
  - **JUnit reflection test (recommended)** — `ShedLockNotActivatedTest` walks `ch.alpenflight.*` for the annotation; same test infrastructure as `MigrationFolderConventionsTest`, no extra CI plumbing.
  - CI grep alternative — cheaper but only catches source-tree adds, not third-party deps that bundle the annotation.
- **`shedlock` table must remain empty until activation.** Post-boot assertion in `FlywayBootstrapIntegrationTest` catches manual pre-population.
- **CODEOWNERS:** S-009's existing rule on `db/migration/` covers V2. No new entry.

## Test plan

### Coverage contract

**S-018 owns:**
- V2 migration applies; `shedlock` table exists with the canonical 4-column shape + PK on `name`.
- ShedLock deps on classpath (Class.forName succeeds for both).
- **No `@EnableSchedulerLock` anywhere in `ch.alpenflight.*`.**
- `shedlock` table is empty post-boot.
- `alpenflight/database/tenant-rules.yaml` carries the SYSTEM_GLOBAL entry.

**S-018 does NOT own:** ShedLock activation (HA story), job porting (S-081+), observability (S-035), per-job lock contention.

### Test pyramid

Integration-only per stack convention. 6 new test methods across 3 existing/new classes; no Spring slice changes; re-uses `PostgresTestContainerLifecycle`.

### Specific test cases

**Extend `MigrationFolderConventionsTest`** (`alpenflight/server/src/test/java/ch/alpenflight/server/migration/`):

- `v2_shedlock_migration_present` — `db/migration/V2__shedlock.sql` exists; matches `^V2__[a-z0-9_]+\.sql$`.
- `v2_shedlock_matches_canonical_provider_ddl` — file contains `CREATE TABLE shedlock`, the 4 column names, and `PRIMARY KEY (name)`. Drift between this file and ShedLock 7.7.0's contract → fail at PR time.
- `shedlock_dependency_on_classpath` — `Class.forName("net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock", false, cl)` AND `Class.forName("net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider", false, cl)` both succeed.

**Extend `FlywayBootstrapIntegrationTest`** (re-uses `PostgresTestContainerLifecycle`):

- `shedlock_table_present_post_migrate` — `INFORMATION_SCHEMA.COLUMNS` filtered to `table_name='shedlock'` returns exactly: `name VARCHAR(64) NOT NULL`, `lock_until TIMESTAMP NOT NULL`, `locked_at TIMESTAMP NOT NULL`, `locked_by VARCHAR(255) NOT NULL`. PK on `name` via `INFORMATION_SCHEMA.TABLE_CONSTRAINTS`.
- `shedlock_table_is_empty_at_boot` — `SELECT count(*) FROM shedlock` returns 0. Catches manual pre-population during debugging.

**New `ShedLockNotActivatedTest`** (no Spring context, pure classpath reflection):

- `no_enable_scheduler_lock_in_main` — uses Reflections (or `ClassPathScanningCandidateComponentProvider` with a custom `isCandidateComponent` override) to scan `ch.alpenflight.*` for `@EnableSchedulerLock`; asserts empty. Also scans methods for `@SchedulerLock`; asserts empty.

### Parity strategy

N/A — greenfield. No legacy oracle (ShedLock didn't exist in `flsserver/`).

### Test data + fixtures

Re-uses S-009's `PostgresTestContainerLifecycle`. No new fixtures. No per-test cleanup needed (nothing inserts into `shedlock`).

### Coverage gaps (deferred)

- ShedLock activation correctness (acquire, release, two-node race) → HA story.
- Per-job lock contention + name uniqueness → S-083+ as each job is ported.
- ShedLock metrics / observability → S-035.
- Postgres failover behavior under held lock → manual UAT in HA story.

### Risks

- **V2 checksum drift** — Flyway catches; integration test's column-shape assertion adds a second layer.
- **ShedLock version drift** — no BOM management; pin `7.7.0` explicit; Dependabot watchlist.
- **Reflection scan false negatives** — prefer `Reflections("ch.alpenflight").getTypesAnnotatedWith(EnableSchedulerLock.class)` over `ClassPathScanningCandidateComponentProvider` (the latter only scans `@Component`-stereotyped classes by default).
- **Postgres case sensitivity** — assertion predicates use lowercase `'shedlock'`, `'name'`, etc. Document.

## Performance plan

### Hot paths

N/A at S-018 (stub). Forward-looking for HA: per `@Scheduled` invocation = one PK lookup + UPSERT on `shedlock(name)` ≈ sub-millisecond. ~8 nightly jobs × 1 acquire + 1 release ≈ 16 row writes/day cluster-wide.

### Required indexes

`shedlock(name)` — implicit B-tree PK from `PRIMARY KEY (name)`. Sufficient.

### N+1 / cartesian / explosion risks

N/A — ShedLock SQL is single-row UPSERTs.

### Caching

**None, and must remain none.** Lock state is DB-source-of-truth. Hibernate L2 cache or `@Cacheable` would defeat mutual-exclusion.

### Latency budget

- S-018 itself: N/A.
- Forward-looking (HA): acquire p95 ≤ 10ms, release p95 ≤ 5ms on warm pool.
- Migration apply (V2): single CREATE TABLE; < 100ms cold Postgres.

### Memory

- Table footprint: ~10 rows × ~120 B ≈ 1 KB. Insignificant.
- Dep JAR weight on classpath (active or not): ~200 KB. Negligible.
- Boot-time classpath scan delta: ~30-50ms. Within S-009's < 30s `@SpringBootTest` budget.

### Performance test plan

- Migration timing: V2 apply < 100ms in `FlywayBootstrapIntegrationTest`. Already covered by the existing < 30s `@SpringBootTest` boot budget.
- No runtime perf test at S-018 — no code path to exercise. HA activation owns acquire-latency benchmark + double-fire prevention test.

### Configuration choices (forward-looking, not S-018)

- `.usingDbTime()` for the eventual LockProvider — Postgres `NOW()` round-trip ~0.3ms; correctness > marginal latency.
- `defaultLockAtMostFor` ≥ longest job worst-case × 2 (delivery mail export + monthly aircraft stats are the long tails — measure first at HA time).

<!-- modernize-refine: end -->

