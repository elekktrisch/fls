---
id: S-009
title: Wire Flyway into Spring Boot + V1__baseline placeholder
epic: E-02
status: done
started_at: 2026-05-16
done_at: 2026-05-16
github_issue: 19
github_pr: 20
depends_on: [S-001]
acceptance:
  - `org.flywaydb:flyway-core` + `flyway-database-postgresql` are in the dependency graph.
  - On Spring Boot startup against a fresh Postgres, Flyway runs the V1__baseline migration (placeholder one-table schema).
  - `flyway:info` and `flyway:validate` are wired into CI and fail the build on drift.
  - A `db/migration/` folder under `next/server/src/main/resources/` is the canonical location.
estimate: S
adr_refs: [0003]
parity_test: none
refined: true
refined_at: 2026-05-16
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context
First DB story. Establishes the Flyway-driven migration model. V1__baseline is a placeholder; S-012..S-014 fill in the real schema.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add Flyway dependencies and Spring Boot autoconfig properties.
- [ ] Create `db/migration/V1__baseline.sql` with one placeholder table (will be replaced by S-012).
- [ ] Configure `flyway.outOfOrder=false` (strict).
- [ ] Wire `flyway:info`/`validate` into CI.
- [ ] Add a Testcontainers-Postgres smoke test that boots the app and asserts the baseline migrated.

## Notes
Don't generalize too early: the V1__baseline grows incrementally in S-012..S-014. Once it's stable, **never amend** — every subsequent change is a new V*__ migration.

<!-- modernize-refine: start -->

## Design notes

### Module layout

**Server (`next/server/`):**

- `build.gradle.kts` — additions:
  - `dependencies { implementation("org.flywaydb:flyway-core"); implementation("org.flywaydb:flyway-database-postgresql"); runtimeOnly("org.postgresql:postgresql") }`. Versions BOM-managed by Spring Boot 4.0.6 — **do not pin** `flyway-core` or `flyway-database-postgresql` explicitly; the two artifacts MUST move together and the BOM keeps them aligned (the Flyway-10+ split is the standard footgun).
  - `plugins { id("org.flywaydb.flyway") version "11.13.2" }` — Gradle plugin, version pinned explicitly (the BOM does not manage plugin versions). Configuration block reads `FLYWAY_URL` / `FLYWAY_USER` / `FLYWAY_PASSWORD` env vars; `tasks.named("check") { if (System.getenv("FLYWAY_URL") != null) dependsOn("flywayValidate") }` keeps `./gradlew check` offline-friendly for contributors without a Postgres handy, while CI sets the env and gets the gate.
- `src/main/resources/application.yml` — pin every Flyway property explicitly (do not rely on Boot defaults):
  ```yaml
  spring:
    datasource:
      url: ${DATASOURCE_URL:jdbc:postgresql://localhost:5432/fls}
      username: ${DATASOURCE_USER:fls}
      password: ${DATASOURCE_PASSWORD:fls}
    flyway:
      enabled: true
      locations: classpath:db/migration
      out-of-order: false              # strict ordering
      validate-on-migrate: true
      baseline-on-migrate: false       # S-016 cutover overrides via its own profile
      clean-disabled: true             # non-negotiable; protects against `flyway clean` on prod
      schemas: public
      default-schema: public
      table: flyway_schema_history
  ```
- `src/main/resources/db/migration/V1__baseline.sql` — sentinel marker table (see "Placeholder strategy"):
  ```sql
  -- S-009: placeholder baseline. S-012 ships V2__identity_and_reference.sql on top.
  -- This table is intentionally SYSTEM_GLOBAL (no club_id): it tracks schema
  -- generation only. Classified accordingly in next/database/tenant-rules.yaml.
  CREATE TABLE app_meta (
      key   VARCHAR(64) PRIMARY KEY,
      value VARCHAR(255) NOT NULL
  );
  INSERT INTO app_meta (key, value) VALUES ('schema_baseline_version', 'S-009');
  ```
- `src/test/java/ch/fls/server/testsupport/PostgresTestContainerLifecycle.java` — clone of `next/database/extract/src/test/java/ch/fls/legacyextract/MssqlTestContainerLifecycle.java` with `postgres:17.4-alpine` image, port `5432`, env (`POSTGRES_USER=fls_test`, `POSTGRES_PASSWORD=fls_test`, `POSTGRES_DB=fls_test`). Static-once lifecycle per test class (shared container amortizes the ~10-15s pull-cached startup). `@EnabledIf("dockerAvailable")` guard so contributors without Docker can still run `./gradlew check`.
- `src/test/java/ch/fls/server/migration/FlywayBootstrapIntegrationTest.java` — `@SpringBootTest` + `@AutoConfigureTestDatabase(replace = Replace.NONE)` + `@DynamicPropertySource` wiring container creds onto `spring.datasource.*` and `spring.flyway.*`. Tests enumerated in the Test plan below.
- `src/test/java/ch/fls/server/migration/MigrationFolderConventionsTest.java` — plain JUnit 5 (no Spring). Walks the `db/migration/` resource folder; asserts naming convention + scans for forbidden patterns. Cheap, runs in every build, no DB needed.
- `src/test/resources/security/forbidden-migration-patterns.txt` — regex list owned by security-engineer; consumed by `MigrationFolderConventionsTest`.
- `next/server/README.md` — append a "Database migrations" section: how to add `V<N+1>__<desc>.sql`, how to run `flywayValidate` / `flywayInfo` locally (env-var contract), the "never amend a shipped migration" rule, recovery via `flywayRepair` (manual only — never CI).
- `next/server/CONVENTIONS.md` — create if missing. Migration-naming + immutability rule + Java-migration escape-hatch policy.

**Cross-module sync (must land in this story to avoid drift):**

- `next/database/tenant-rules.yaml` — add an override classifying `flyway_schema_history` and `app_meta` as `SYSTEM_GLOBAL`. Without this, S-024's reflection check (once it lands) and S-011's classifier integration test will eventually see `UNKNOWN` for these tables.

**CI (`.github/workflows/ci.yml`, `next-build` lane):**

Extend after the `Build next/server` step:

```yaml
- name: Start Postgres 17 for Flyway validate
  if: steps.detect.outputs.server == 'true'
  run: |
    docker run -d --name fls-flyway-pg \
      -e POSTGRES_PASSWORD=fls -e POSTGRES_USER=fls -e POSTGRES_DB=fls \
      -p 5432:5432 postgres:17.4-alpine
    for i in $(seq 1 30); do
      docker exec fls-flyway-pg pg_isready -U fls && break
      sleep 2
    done
- name: flywayMigrate + flywayValidate + flywayInfo
  if: steps.detect.outputs.server == 'true'
  working-directory: next/server
  env:
    FLYWAY_URL: jdbc:postgresql://localhost:5432/fls
    FLYWAY_USER: fls
    FLYWAY_PASSWORD: fls
  run: ./gradlew flywayMigrate flywayValidate flywayInfo --no-daemon
- name: Tear down Postgres
  if: always() && steps.detect.outputs.server == 'true'
  run: docker rm -f fls-flyway-pg || true
```

The Postgres image pull + redact of any connection URI from CI logs (Spring + Flyway sometimes echo on error) is included. Existing `next-build` already gates on `next/**` paths, so `db/migration/**` changes trigger it.

### Domain model

No JPA entities. `app_meta` is a pure SQL marker; no `@Entity` counterpart in S-009. S-012 may promote or replace it. `app_meta` has no `club_id` — S-011 catalog classifies it `SYSTEM_GLOBAL`. Call this out in V1's header comment so a parity reviewer doesn't flag a missing tenant discriminator.

### API surface

None. No new endpoints; no `@PreAuthorize`. The `db` Actuator health indicator auto-registers once `spring-boot-starter-jdbc` (transitive via flyway-core's `DataSource` requirement) is on the classpath; `/actuator/health` composite now includes `db { status: UP }`. No new exposure config required.

### Placeholder V1 strategy

**Decision: sentinel marker table.** Pristine V1 with a real-but-trivial table (`app_meta`) avoids two failure modes:

- *"Delete V1 + replace"*: if any contributor applies V1 to a local dev DB and then S-012 author replaces the file, every dev DB needs a wipe-and-reapply or a `flyway repair`. Operator-error vector.
- *"Empty stub forever"*: an empty `V1__placeholder.sql` survives forever in the history table as a no-op — confusing for future readers.

S-012 ships `V2__identity_and_reference.sql` on top; V1 stays as a permanent historical record of "this is where the schema generation begins." The `app_meta.schema_baseline_version` row gives ops a quick "what schema generation is this DB on?" check via plain SQL.

### `baseline-on-migrate=false` (default)

S-009 lands on a fresh Postgres. **Set `baseline-on-migrate=false`** for dev/test/prod-fresh. S-016 (cutover) introduces a dedicated profile/flag that flips it `true` for its one-shot legacy-ingest invocation. Boot autoconfig never auto-baselines a real DB — the operation is always explicit.

### Test infrastructure

Re-uses the docker-CLI-managed container pattern proven by S-010 + S-011 (`MssqlTestContainerLifecycle`). Testcontainers 1.21.x's bundled docker-java 3.4.x cannot negotiate Docker API ≥1.44 in this sandbox; the symmetric Postgres helper drives the lifecycle through `docker` CLI directly. **S-009 ships the first Postgres helper**; S-015 generalizes if a second consumer appears.

### Alternatives considered

- **Sentinel V1 (chosen)** vs. empty placeholder vs. shipping the real S-012 schema directly. See Placeholder V1 strategy above for rationale.
- **`baseline-on-migrate: false` (chosen)** vs. dual-profile shipping `true` for cutover from day one. Defer cutover profile to S-016; keeps S-009 minimal.
- **Single `classpath:db/migration` location (chosen)** vs. per-profile folders. Per-profile invites silent dev/prod schema drift — exactly what Flyway exists to prevent. S-015 may add a `src/test/resources/db/testdata/` for INSERT-only seed scripts run outside Flyway.
- **Boot-managed Flyway versions (chosen)** vs. explicit pins in `build.gradle.kts`. Boot 4.0.6 BOM moves the two Flyway artifacts together; explicit pinning the runtime jar without pinning the database jar is the standard Flyway-10+ misconfiguration.
- **Gradle plugin version explicit pin (chosen)** — Boot BOM doesn't manage plugins; pin to `11.13.2`.
- **Java-migration support: documented in CONVENTIONS.md (chosen)** vs. forbidden vs. eagerly enabled. Spring Boot autoconfig picks up `JavaMigration` beans without additional config; ship S-009 SQL-only but document the escape hatch.
- **Test-DB helper location: `next/server/src/test/java/ch/fls/server/testsupport/` (chosen)** vs. a shared fixtures module. Server module for S-009; S-015 extracts if a second consumer materializes. Premature module split is worse than a later move.
- **CI in a separate `db.yml` workflow — rejected.** The existing `next-build` lane in `ci.yml` already covers `next/**` paths; duplicating JDK/Gradle setup buys nothing.
- **`@FlywayTest` JUnit extension — rejected.** Legacy library tied to JUnit 4 / older Flyway majors; Spring Boot's `@SpringBootTest` + `@DynamicPropertySource` covers the same use case.
- **Maven Flyway plugin — rejected** (S-001 picked Gradle Kotlin DSL).

### Integration with other stories

**Inputs (from `depends_on: [S-001]`):** Spring Boot 4.0.6 + Java 25 + Gradle Kotlin DSL + `failOnVersionConflict` runtime/compile classpath check + `${ENV_VAR:default}` convention.

**Outputs (consumed downstream):**

| Story | Consumed | Use |
|---|---|---|
| **S-012/S-013/S-014** | `db/migration/` folder + naming convention + immutability rule | Ship V2/V3/V4 with real schema |
| **S-018** | Same as above | `V<N>__shedlock.sql` |
| **S-015** | `PostgresTestContainerLifecycle` helper | Generalize the test-DB strategy |
| **S-016** | Same migration folder + a profile-overridden `baseline-on-migrate: true` | One-shot cutover migration |
| **S-022** | `app_meta` (or its eventual replacement) co-exists with JPA-mapped entities | Ensure `ddl-auto=validate` or `none` (never `update`) once JPA lands |
| **S-011** | `tenant-rules.yaml` SYSTEM_GLOBAL override for `flyway_schema_history` + `app_meta` | Keeps S-011's classifier from emitting UNKNOWN on these tables |

## Edge cases & hidden requirements

### Per-AC edge cases

**AC1 — deps in graph.** `flyway-database-postgresql` is a SEPARATE artifact since Flyway 10; omission causes a runtime `ClassNotFoundException` for the Postgres database type — not a compile error. Smoke test must boot against real Postgres to catch it. Hash-pin via Gradle dependency-verification is out of scope (no S-001 precedent); flag as future hardening.

**AC2 — fresh Postgres + V1 applies.** "Fresh" means empty `public` schema + no `flyway_schema_history` table. With `baseline-on-migrate=false`, Flyway runs V1, creates `flyway_schema_history`, inserts the V1 row. **Concurrent startup** (two pods racing): Flyway acquires `pg_advisory_lock` by default; safe. Test must not parallelize against the same DB. Empty migration folder → Flyway warns but boots; the smoke test asserts a row IS present, catching the regression.

**AC3 — drift in CI.** Drift definitions: (a) committed migration's checksum differs from the applied row (history corruption — operator-only `repair`); (b) new migration version conflicts with an applied higher version (`out-of-order=false` catches at PR time); (c) a deleted migration file when its history row exists (`validate` fails). Wire validate per-PR (every PR, including drafts). Spin Postgres via docker CLI in CI; mirror the pattern from S-010's pre-pull step.

**AC4 — canonical folder.** `classpath:db/migration` is the autoconfig default. Pin in `application.yml` so the convention survives a future BOM bump that flips defaults. Multiple folders forbidden until S-016 + S-015 prove the need.

### Hidden requirements (promote or surface)

- **`spring.flyway.clean-disabled=true`** must be explicit. Older Flyway versions defaulted permissive — `clean` drops the entire schema.
- **`spring.flyway.locations`, `out-of-order`, `validate-on-migrate`, `table`, `enabled`** all pinned explicitly so future BOM bumps surface as visible config changes.
- **`spring.datasource.*`** — S-001 deferred. S-009 ships env-driven (`DATASOURCE_URL`/`DATASOURCE_USER`/`DATASOURCE_PASSWORD`) with `application-dev.yml` loopback defaults; `.env.example` updated.
- **Postgres image:** pin `postgres:17.4-alpine` (minor + variant). Document that production image MUST match `lc_collate` settings — alpine's musl differs from debian's glibc on locale-sensitive sorts.
- **Flyway plugin version:** Boot BOM doesn't manage plugins; pin `org.flywaydb.flyway` to `11.13.2` (or current latest).
- **Tenant catalog cross-module update:** `flyway_schema_history` + `app_meta` get `SYSTEM_GLOBAL` overrides in `next/database/tenant-rules.yaml`. S-009 commits this edit; without it, S-011's classifier emits `UNKNOWN` on future re-runs.
- **`spring.jpa.hibernate.ddl-auto`:** S-009 doesn't add JPA, but document the forward constraint — once S-022 lands JPA, this MUST be `validate` or `none`, never `update`/`create`. Hibernate racing Flyway at boot would invalidate every startup-cost assumption.
- **`PostgresTestContainerLifecycle` self-smoke:** test that the helper itself starts a container, opens JDBC, runs `SELECT 1`, tears down. A broken helper otherwise manifests as a confusing `@SpringBootTest` failure.
- **Runbook entries in `next/server/README.md`:** add migration, run local validate, recover from botched migration (operator-only `flywayRepair`).
- **CODEOWNERS rule** on `next/server/src/main/resources/db/migration/` — security + tech-lead review on every migration. Migration drift = compliance + correctness risk.
- **Pre-commit hook mirrors CI grep patterns** (`PASSWORD '...'`, `GRANT`, `ALTER ROLE`, `CREATE USER`, PII-INSERT) for fast local feedback. Optional but recommended.

### Scope clarifications

**In scope:**
- Flyway deps + Spring Boot autoconfig wiring + every property pinned in `application.yml`.
- Env-driven `spring.datasource.*` + `application-dev.yml` defaults + `.env.example` keys.
- Canonical `db/migration/V1__baseline.sql` with sentinel `app_meta` table.
- `PostgresTestContainerLifecycle` helper.
- Two test classes: `FlywayBootstrapIntegrationTest` + `MigrationFolderConventionsTest`.
- Security-owned `forbidden-migration-patterns.txt`.
- CI step in `ci.yml`'s `next-build` lane spinning Postgres + running `flywayMigrate flywayValidate flywayInfo`.
- Updates to `next/server/README.md` + `CONVENTIONS.md` (create if missing).
- Cross-module update to `next/database/tenant-rules.yaml` (SYSTEM_GLOBAL overrides).

**Out of scope:**
- Real schema content → S-012/S-013/S-014.
- ShedLock V<N> → S-018.
- Test-DB strategy beyond happy-path smoke → S-015 (transactional rollback vs. truncation vs. per-class re-migrate).
- Data migration / legacy cutover → S-016.
- Hibernate / JPA wiring → S-022.
- Split `migrator` / `app_runtime` Postgres roles → flag for S-013/S-016 ops decisions; single-user `postgres` acceptable for S-009 dev/test.
- Production migration runbook → ops story.

### NFR call-outs

- **Performance — boot:** Flyway's `migrate()` is synchronous; p95 < 1s warm, < 5s cold-with-V1. Future S-012 baseline projected < 10s on cold container. C6 ≤ 6h cutover budget owned by S-016 / S-017 rehearsal.
- **Performance — test:** every `@SpringBootTest` re-applies all migrations against fresh schema. Acceptable at V1; cost grows linearly. S-015 owns the optimization.
- **Security:** migration files are an attack surface for plain-text secrets + privilege escalation via `GRANT`/`CREATE ROLE`/`ALTER ROLE`. CI grep + CODEOWNERS gate.
- **Observability:** Flyway INFO logs at apply-time are sufficient. Audit-log of migrations (per-DDL row) is a forward constraint for S-027.
- **Operability:** operator must be able to apply migrations outside Spring Boot (`./gradlew flywayMigrate` + env vars). `flywayRepair` is operator-only — never autoconfig-driven.

### Things not the right shape

- AC1 "in the dependency graph" is vague. Promote to: explicit declarations in `build.gradle.kts` + integration-test assertion that `org.flywaydb.database.postgresql.PostgreSQLDatabaseType` is on the classpath.
- AC3 "wired into CI" — singular. Pin to: a Gradle task (`flywayMigrate flywayValidate flywayInfo`) invoked from `ci.yml`'s `next-build` lane against an ephemeral Postgres container.
- AC4 says `db/migration/` is canonical — document in `README.md` + `CONVENTIONS.md` + asserted in `MigrationFolderConventionsTest`.
- Implied AC5 (missing): operator runbook for "add migration / run validate / recover from botched migration."

## Security plan

### Threat model

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| (a) | Secrets in migration files (committed to git) | **High** | CI grep `PASSWORD\s*'[^']*'` on `db/migration/**`; CONVENTIONS.md ban; secrets via env vars consumed by application code only |
| (b) | Migration user has full DDL rights — `DROP TABLE person` is one typo away | **High** | Recommend split `migrator` (DDL) + `app_runtime` (DML) Postgres roles — `spring.flyway.user` distinct from `spring.datasource.username`. S-009 ships single-user for dev/test; flag for S-013/S-016 ops split |
| (c) | PII in data backfill migrations (FADP/GDPR) | **High** | Schema-only migrations; CI grep blocks `INSERT INTO (person\|person_club\|audit_logs\|audit_log_details)`; data backfills via programmatic seed (S-018) or one-shot cutover job (S-016) |
| (d) | Repeatable migration privilege escalation — attacker pushes `R__seed_admin.sql` with `GRANT superuser`, auto-applies on boot | **High** | CI grep `GRANT\s|ALTER\s+ROLE|CREATE\s+(USER\|ROLE)|REVOKE\s` rejects across all migrations; CODEOWNERS forces security review on any `R__*` |
| (e) | Supply-chain on Flyway artifact | Med | BOM-managed versions (Spring Boot 4); Dependabot/Renovate; future hardening: Gradle dependency-verification |
| (f) | Partially-applied migration leaves DB inconsistent | Med | Prefer transactional DDL (Postgres supports it); document `flywayRepair` as ops-only manual recovery; never enable auto-repair |
| (g) | Credentials echoed in CI logs on validate failure | Med | Pass via `FLYWAY_URL` env var only; CI step redacts via `::add-mask::` for password values |

### Authorization (DB-level)

- **`spring.flyway.user`** = `migrator` role (DDL on app schema). **`spring.datasource.username`** = `app_runtime` (DML only). Passwords via env. S-009 acceptable single-user `postgres` for dev/test; production split is a MUST in S-013/S-016 ops runbook.
- **`spring.flyway.clean-disabled=true`** explicit in `application.yml` for all profiles. Never rely on default.

### Input validation (migration content)

- **Filename regex (CI):** `^(V\d+(\.\d+)*__|R__|U\d+__)[a-z0-9_]+\.sql$`. Reject anything else under `db/migration/`.
- **Forbidden-pattern grep (`forbidden-migration-patterns.txt`, consumed by `MigrationFolderConventionsTest` AND CI):**
  - `PASSWORD\s*'` — plain-text creds
  - `GRANT\s|ALTER\s+ROLE|CREATE\s+(USER|ROLE)|REVOKE\s` — privilege ops
  - `INSERT\s+INTO\s+(person|person_club|audit_logs|audit_log_details)` — PII data (case-insensitive)
  - `DROP\s+SCHEMA|TRUNCATE` — destructive (allowlist per PR)
  - `\$\{[^}]+\}` — Flyway placeholders (avoid until S-011/tenant-aware tooling lands)

### PII handling

- Committed migrations: schema-only. No literal person / license / medical / email data — ever.
- `flyway_schema_history` table — classify `SYSTEM_GLOBAL` in `next/database/tenant-rules.yaml`. S-024 leakage CI (when it lands) allowlists via that classification, not a hardcoded skip.
- Migration apply log (stdout): redact connection URIs; never log row-level data from backfills.

### Audit-log events

- Flyway is JDBC-direct → bypasses Hibernate envers; no per-row audit. Acceptable; not a regression vs. legacy.
- **Apply-time structured log** (INFO): `{event: "flyway.migration.applied", version, description, type, executionTimeMs, success, deploymentSha}`. Feeds S-035 ops dashboard.
- `flyway_schema_history` rows ARE the forensic trail (script, checksum, `installed_by`, `installed_on`). Read-only from app code.

### Cross-tenant leakage

- N/A — no tenant-scoped entities introduced.
- **Invariant for V1:** must not introduce `club_id` on a non-tenant entity, nor a tenant-scoped entity without `club_id NOT NULL`. S-011 tenant catalog classifies `flyway_schema_history` + `app_meta` as `SYSTEM_GLOBAL`.

### OWASP applicability

- **A01 Broken Access Control:** applies — recommend `migrator`/`app_runtime` role split (S-013/S-016 ops).
- **A02 Cryptographic Failures:** applies — secrets never in `.sql`.
- **A03 Injection:** N/A at S-009 — migrations are static SQL.
- **A04 Insecure Design:** applies — S-009 sets the discipline that every tenant-scoped table from S-013 onwards carries `club_id NOT NULL`. CONVENTIONS.md encodes.
- **A05 Security Misconfiguration:** applies — `clean-disabled=true`, `validate-on-migrate=true`, `out-of-order=false`, `baseline-on-migrate=false` all explicit.
- **A06 Vulnerable Components:** applies — BOM-pin Flyway; Dependabot.
- **A08 Data Integrity Failures:** applies — Flyway checksum lock; CI runs `flywayValidate` on every PR.
- **A09 Security Logging & Monitoring:** applies — structured apply-log + `flyway_schema_history` are the forensic chain.

### Story-specific concerns

- **`spring.flyway.clean-disabled=true`** non-negotiable.
- **CODEOWNERS** entry `next/server/src/main/resources/db/migration/ @sec-team @tech-lead` — every migration PR requires both.
- **Pre-commit hook** mirrors CI grep patterns.
- **Operator runbook** (in README): failed-migration playbook — `flywayInfo`, manual `flywayRepair`, never auto-invoke.
- **Baseline strategy:** `baseline-on-migrate=false` for S-009; S-016 is the only story permitted to flip it.

## Test plan

### Coverage contract

**S-009 owns:** dep wiring + autoconfig executes `migrate()` at startup + V1 applies cleanly + `flyway_schema_history` is created + `flywayValidate` returns OK + canonical folder exists + `outOfOrder=false` + `cleanDisabled=true` + naming convention enforced + forbidden-pattern grep over migrations.

**S-009 does NOT own:** real schema (S-012/S-013/S-014), ShedLock V<N> (S-018), test-DB strategy formalization (S-015), parity vs. legacy (S-016), Hibernate config (S-022), production runbook (ops).

### Test pyramid

- **Unit:** none (per stack convention — no mocking tier).
- **Static-asset (no-DB):** `MigrationFolderConventionsTest` — plain JUnit 5, fast, runs in every build.
- **Integration (only DB-touching tier):** `FlywayBootstrapIntegrationTest` — `@SpringBootTest` against a Docker-CLI-managed Postgres 17 container via `PostgresTestContainerLifecycle`. Guarded by `@EnabledIf("dockerAvailable")`.
- **CI step assertion:** `./gradlew flywayValidate` against the same Postgres container in `ci.yml`'s `next-build` lane. Belt-and-suspenders alongside the JUnit `validate_passes_after_migrate`.

### Specific test cases — `FlywayBootstrapIntegrationTest`

- `app_boots_against_fresh_postgres` — ApplicationContext loads; `flyway_schema_history` exists in `public`; exactly one row, `version='1'`, `success=true`, `type='SQL'`.
- `flyway_schema_history_metadata_is_well_formed` — `description` matches V1 filename's `<desc>`, `script='V1__baseline.sql'`, `installed_by` = migrator user, `installed_on` within last 60s, `checksum` non-null.
- `placeholder_baseline_objects_exist` — `app_meta` table exists; row `('schema_baseline_version', 'S-009')` is present.
- `reboot_against_already_migrated_db_is_noop` — start container, boot, shut, boot again on same container; still one row, no errors on `org.flywaydb` logger at WARN/ERROR.
- `checksum_drift_fails_loudly` — adversarial. Use `filesystem:<tmp>` location with a V1 copy; apply; mutate file (append comment); reboot; expect `FlywayValidateException`.
- `out_of_order_disabled_blocks_late_v2` — adversarial. V1 + V3 applied; then drop V2 with lower version; reboot fails because `outOfOrder=false`.
- `clean_is_disabled` — invoke `Flyway` bean's `clean()` directly; expect `FlywayException` mentioning `cleanDisabled`.
- `validate_passes_after_migrate` — post-boot, call `flyway.validate()` programmatically; no exception.

### Specific test cases — `MigrationFolderConventionsTest`

- `db_migration_resource_folder_exists`.
- `at_least_one_versioned_baseline_present` — matches `^V\d+(_\d+)*__[A-Za-z0-9_]+\.sql$`.
- `every_file_matches_naming_convention` — every `*.sql` matches `^(V\d+(_\d+)*|R)__[A-Za-z0-9_]+\.sql$`.
- `v1_baseline_is_non_empty` — `V1__baseline.sql` length > 0 after stripping comments.
- `no_forbidden_patterns_in_migrations` — regex over the forbidden-patterns fixture file.
- `dependency_graph_contains_flyway` — `Class.forName("org.flywaydb.core.Flyway")` and `Class.forName("org.flywaydb.database.postgresql.PostgreSQLDatabaseType")` succeed.

### Parity strategy

N/A — S-009 is greenfield. Legacy `flsserver/database/FLS/Updates/DBUpdate_v*.sql` is reference-only and targets SQL Server; not parity-relevant.

### Test data + fixtures

- **`PostgresTestContainerLifecycle`** static-once per JVM run (one container shared across tests in `FlywayBootstrapIntegrationTest`). Image `postgres:17.4-alpine`, port `0:5432`, env (`POSTGRES_USER=fls_test`, `POSTGRES_PASSWORD=fls_test`, `POSTGRES_DB=fls_test`). Readiness probe via JDBC `SELECT 1`. JVM shutdown hook.
- **`dockerAvailable()`** predicate: `ProcessBuilder("docker", "version").start().waitFor() == 0`. Matches S-010 pattern. Class guarded by `@EnabledIf` so Windows-without-Docker still passes `./gradlew check`.
- **`forbidden-migration-patterns.txt`** test resource: regex per line, security-engineer-owned.
- **Per-adversarial-test isolation:** drop `public` schema between phases (`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`) so each phase starts known.

### Doc-as-oracle for downstream

| Story | Consumes | Use |
|---|---|---|
| S-012/S-013/S-014 | `db/migration/` folder + naming + immutability rule | Ship V2/V3/V4 on top |
| S-015 | `PostgresTestContainerLifecycle` | Generalize the test-DB strategy |
| S-016 | Migration folder + dedicated profile flipping `baseline-on-migrate=true` | One-shot cutover load |
| S-018 | Folder + numbering | `V<N>__shedlock.sql` |
| S-011 | `tenant-rules.yaml` updates (committed by S-009) | Catalog drift-control invariant holds |

### Coverage gaps (deferred)

- Real-schema correctness → S-012/S-013/S-014.
- Per-test isolation strategy (rollback vs. truncation vs. per-class) → S-015.
- ShedLock V<N> → S-018.
- Legacy → new data parity → S-016.
- Production migration rehearsal (≤ 6h budget) → S-017 / ops.

### Risks

- **`PostgresTestContainerLifecycle` is new code.** Copy `MssqlTestContainerLifecycle` byte-for-byte changing only image/port/env; add a self-smoke test (`lifecycle_starts_and_serves_select_1`).
- **Postgres image pull latency in CI.** Mitigate via the same pre-pull step pattern as S-010's `docker pull` step.
- **`flywayValidate` CI step needs a live DB.** JUnit `validate_passes_after_migrate` enforces the contract even if the Gradle CI step is misconfigured.
- **V1 checksum becomes load-bearing when S-012 reshapes it.** S-009 ships V1 as deliberately trivial; comment in the file explains it's intentionally permanent.
- **Port collisions.** Lifecycle uses `-p 0:5432` and reads the port back via `docker port`; matches S-010.
- **Alpine vs. debian collation surprise.** Pin `postgres:17.4-alpine`; document that production image must match `lc_collate`. If S-015 finds issues, switch to `postgres:17.4` (debian) — one-line change.
- **Test slowness.** ~10-15s per `@SpringBootTest` class. Bounded at S-009; revisit in S-015.

## Performance plan

### Hot paths

- **App startup, history scan:** O(n) on row count. < 50ms at N=1; < 200ms at N=200. PK on `installed_rank` — no tuning.
- **App startup, pending apply:** S-009 placeholder V1 < 100ms. S-012 real V1 projected 500ms-2s on cold container.
- **`@SpringBootTest` per-class boot:** container start ~5-10s (cached image), Flyway re-applies all migrations against fresh schema. Cost grows linearly in N — flag now, mitigate in S-015.

### Required indexes

N/A — `flyway_schema_history` ships with PK on `installed_rank`. No app tables in S-009.

### N+1 risks

N/A — no ORM in this story.

### Cartesian / explosion risks

N/A.

### Caching strategy

N/A at the migration layer. Spring autoconfig caches the `Flyway` bean.

### Latency budget

- **Flyway overhead at warm boot (no pending):** p95 < 1s.
- **Cold boot applying placeholder V1:** p95 < 5s end-to-end (pool init + V1 apply).
- **`@SpringBootTest` class boot:** p95 < 30s (container + V1).
- **Forward-looking for S-012 real V1:** < 10s, so C6 (6h cutover) retains headroom.

### Memory considerations

- Flyway runtime heap: < 10MB.
- `flyway_schema_history` lifetime peak: ~40KB at 200 migrations.
- No streaming / batch path in scope.

### Performance test plan

- **Boot smoke (this story):** integration test asserts `ApplicationContext` loads in < 30s. `@Timeout(30)` on the bootstrap test.
- **Migration-apply benchmark (defer to S-015 / S-108):** measure `Flyway.migrate()` against a realistic dump. Drives C6.
- **Test-class boot benchmark (defer to S-015):** mean < 30s, p95 < 45s across representative classes.

### Configuration choices affecting performance

- `spring.flyway.out-of-order=false` — strict; faster + safer.
- `spring.flyway.validate-on-migrate=true` — microsecond cost; keep for correctness.
- `spring.flyway.connect-retries=5`, `connect-retries-interval=2s` — set in `application-test.yml` only; eliminates container-readiness flake.
- `spring.flyway.batch=true` — Teams-edition feature; leave default.
- **Hikari pool:** Flyway uses 1 connection during `migrate()`; non-blocking once done.
- **`spring.jpa.hibernate.ddl-auto`** — must be `validate` or `none` once S-022 lands JPA. Hibernate racing Flyway at boot invalidates every cost assumption above.

## Open design questions

These specialists' analyses surfaced operator-decision points; surfaced rather than silently resolved.

1. **CI flywayValidate trigger frequency.** Recommendation: every PR (including drafts). Alternatives: only on merges to main (cheaper), or scheduled nightly (lowest signal). Every-PR adds a docker-pull-and-spin step (~30s) to every Gradle CI run. Operator decision.
2. **`spring.datasource.*` env-var names.** Recommendation: `DATASOURCE_URL` / `DATASOURCE_USER` / `DATASOURCE_PASSWORD` with `application-dev.yml` loopback defaults. Alternative naming: `FLS_DB_URL` etc. (project-prefixed). Either works; pick one in implement.
3. **`PostgresTestContainerLifecycle` location.** Recommendation: `next/server/src/test/java/ch/fls/server/testsupport/` for first consumer; S-015 extracts if a second story needs it. Alternative: place at `next/server/src/testFixtures/` from day one to signal the shared contract. Operator decision.
4. **`migrator` vs. `app_runtime` Postgres role split.** S-009 acceptable single-user (`postgres`) for dev/test. **Production split is a MUST** but decided in S-013/S-016 ops planning. Surface so the ops story carries the responsibility forward.
5. **Tenant-rules.yaml edit ownership.** Recommendation: S-009 includes the `flyway_schema_history` + `app_meta` SYSTEM_GLOBAL overrides in `next/database/tenant-rules.yaml`. Alternative: a separate follow-up story. Recommend in-scope here — otherwise S-011's classifier emits UNKNOWN on the next re-run.

<!-- modernize-refine: end -->

## Assumptions made (implement-time, 2026-05-16)

Operator decisions on the refinement's 5 open design questions:

1. **flywayValidate CI trigger: every PR** (Q1) — extended `next-build` lane in `.github/workflows/ci.yml`; ~30s cost per PR run when `next/**` changes.
2. **Datasource env-vars: `DATASOURCE_URL` / `DATASOURCE_USER` / `DATASOURCE_PASSWORD`** (Q2) — generic, matches Spring's own naming.
3. **`PostgresTestContainerLifecycle` location: `next/server/src/test/java/ch/fls/server/testsupport/`** (Q3) — server module for the first consumer; S-015 extracts if a second story needs it.
4. **Postgres role split (Q4) — deferred** to S-013 / S-016 ops. S-009 dev/test runs as the container's default `fls_test` user. Production split (`migrator` vs `app_runtime`) is flagged on those stories.
5. **tenant-rules.yaml SYSTEM_GLOBAL overrides: in-scope for S-009** (Q5) — `flyway_schema_history` + `app_meta` added to `next/database/tenant-rules.yaml` as part of this story so S-011's classifier stays clean.

