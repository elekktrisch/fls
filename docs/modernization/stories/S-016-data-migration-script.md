---
id: S-016
title: Legacy schema-mapping library + parity oracle
epic: E-02
status: in_progress
started_at: 2026-05-28
depends_on: [S-012, S-013, S-014]
integration_base: integration/migration
github_issue: 157
acceptance:
  - `alpenflight/migration-bundle/` library provides one mapper per legacy entity cluster (~60 entity types): per-entity column lists, type coercions, FK rewrites, enum re-encodings (e.g. legacy `BOOLEAN` → string-serialized enums per S-129), tenant-scoping defaults.
  - Library is consumed by S-139 (JAR bundle-writer) AND S-141 (server ingest pipeline) — single source of truth for "what's in the bundle".
  - Mappers cover every legacy table in the S-011 tenant-scoped-entities catalog plus cross-tenant tables (audit, system data).
  - Parity oracle in CI: row-count diff, FK-integrity check, 1% sampled-value diff on Flight / Delivery / PersonClub / AircraftReservation / AccountingRuleFilter against a seeded legacy SQL Server fixture. Fails loud on regression.
  - Machine-readable verification output (JSON) alongside a human-readable report — CI asserts on the JSON.
estimate: L
adr_refs: [0002, 0003, 0019]
parity_test: tests/migration/schema-parity.spec.ts (new)
refined: true
refined_at: 2026-05-28
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
github_pr: 158
---

## Context
The transport for legacy-to-new data is the JAR (S-139) + upload pipeline (S-141). This story owns the *content* both sides depend on: the entity-by-entity mapping rules, and the verification automation that proves a round-trip preserves the data.

Per memory `[[feedback-re-runnable-over-frozen-docs]]`: the parity oracle re-exports from a seeded legacy DB on every CI run, never a committed bundle.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Bootstrap `alpenflight/migration-bundle/` Gradle module; published as an internal Maven artifact consumed by `alpenflight/server/` and `alpenflight/migration-tool/`.
- [ ] One mapper per entity cluster (S-012 / S-013 / S-014 cover the schema groupings).
- [ ] FK-integrity check (every FK in the new schema resolves).
- [ ] Row-count check (per-tenant + total).
- [ ] Sampled-value check (random 1% per table, value-compare key columns).
- [ ] Seed a SQL Server fixture in CI (Testcontainers); re-export through S-139's JAR; round-trip through S-141; diff.
- [ ] Emit JSON + markdown verification reports.

## Notes
- Complexity is real (~60 entity types). Plan for ~10 working days even with transport out of scope.
- Tables migrated in topological FK order; FK constraints disabled during bulk insert then re-enabled — handled inside S-141's ingest, not here.

<!-- modernize-refine: start -->

## Design notes

S-016 is a pure-data-transport Gradle module (`alpenflight/migration-bundle/`) compiled into BOTH `alpenflight/migration-tool/` (S-139's fat-jar; writes NDJSON from legacy SQL Server) AND `alpenflight/server/` (S-141's ingest pipeline; reads NDJSON into Postgres). Schema rules live in Flyway V1..V13 + Hibernate entities; this library owns only the legacy↔new column mappings, the per-bundle `legacy_id_map_<entity>` API, and the parity oracle harness. No business logic; no D2 violations.

- **Mapper granularity: one class per legacy entity, grouped into 3 sub-packages aligned with V2/V3/V4 Flyway boundaries** (`.identity.*`, `.flight.*`, `.accounting.*`). Operator-grilled 2026-05-28. ~60 mapper classes total; small per-class test surface; PRs touching one entity touch one file. Hand-rolled (codegen wins lose to legacy column quirks like `FlightAirState` packing + the BOOLEAN→string-enum re-encodings per S-129).
- **Bidirectional read+write per mapper.** Paired methods on one class: `void writeNdjson(ResultSet rs, PreparedStatement → JsonGenerator)` (S-139 side, JDBC streaming) and `void readEntity(JsonNode row, PreparedStatement ps)` (S-141 side, `StatelessSession.insert` parameter binding). NO intermediate POJOs in the hot path (performance plan owns the rationale). Static `String[] COLUMNS` constant shared by both methods.
- **NDJSON wire schema: new-schema column names (snake_case).** Bundle is an AlpenFlight artifact; ingest reads many more bundles than the JAR writes them, so debuggability on the ingest side wins. Manifest carries `schema_version: 1` — bumped on any wire-incompatible change so ingest rejects mismatched bundles up-front.
- **`legacy_id_map_<entity>` ownership.** Mapper owns the byte format; S-141 owns the temp-table lifecycle (`ON COMMIT DROP`). Contract: S-016 exposes `void streamIds(JsonStream src, OutputStream copyDst)` which writes UUID-pair lines; S-141 wires `PgConnection.getCopyAPI()` to it. S-016 also exposes `String resolveFkArrayQuery(EntityType target)` returning the parameterized `SELECT new_uuid FROM legacy_id_map_<x> WHERE legacy_guid = ANY(?::uuid[])` text — keeps S-141 from hand-rolling it 60 times.
- **Topological insert order = static `INSERT_ORDER` enum constant in S-016.** Order: ReferenceData → Club → Person → PersonClub → User → UserRole → Location → Aircraft → AircraftAircraftState → AircraftOperatingCounter → Article → AccountingRuleFilter → FlightType → PlanningDay → PlanningDayAssignment → AircraftReservation → Flight → FlightCrew → Delivery → DeliveryItem → Audit. Hardcoded > dynamic-from-metamodel (hides the order behind reflection). ArchUnit asserts every entity with a non-null FK appears after its target.
- **Cross-tenant Person sub-map: per-bundle, not per-Club.** `legacy_id_map_person` populated once at Person-phase ingest; subsequent Clubs reuse it (S-141's per-Club txns share the same DB session within the bundle). One `t_person` row, N `t_person_club` rows. User.PersonId rewrite (S-141 AC #10) flows through this temp table.
- **Reference-data resolution: two-tier strategy.** SYSTEM_GLOBAL refs (`country`, `language`, `start_type`, `role`, `club_state`, unit types, `extension_type`, `accounting_rule_filter_type`, `accounting_unit_type`) resolve via the `legacy_int_id SMALLINT UNIQUE` columns that S-012/013/014 added on the new reference tables specifically as the S-016 cutover hook. TENANT_SCOPED refs (`member_state`, `person_category`, `flight_type`, `aircraft_reservation_type`, `planning_day_assignment_type`, `email_template` with `club_id IS NOT NULL`) port from the bundle via per-bundle `legacy_id_map_*` temp tables. Manifest carries per-table policy explicitly, no implicit defaults. S-138's per-Club bootstrap runs as no-op for ported-from categories (collision-handling pinned in S-141: bundle wins).
- **Audit-log actor remap.** Legacy `AuditLog.UserId` → new `audit_log.legacy_actor_user_id TEXT` (opaque); `actor_keycloak_sub` NULL; new discriminator `actor_kind='LEGACY_MIGRATED'`. Hand-off: **S-027 test plan must cover read-back of this row variant + a `LEGACY_MIGRATED` rendering path.** Pre-cutover orphan audit rows (deleted Users with no legacy_id_map_user entry) get a synthetic `legacy_orphan_actor_id` UUID v7 + warning into `migration_run.warnings` JSONB; they do NOT fail the txn.
- **Tombstones port-all by default; manifest skip-overrides per-entity.** Operator-grilled 2026-05-28. Synthesize `deleted_on = COALESCE(legacy.ModifiedOn, legacy.CreatedOn)` and `deleted_by_user_id = legacy.ModifiedBy ?? legacy.CreatedBy` (legacy soft-delete is bi-table — see edge cases). Reference tables skip tombstones by default (noise). Audit continuity + Swiss OR Art. 957a rationale.
- **Manifest = first-class typed Java class** in `migration-bundle`, Jackson-serialized JSON on wire. Carries: per-entity port-policy, per-entity tombstone-policy, per-FK tenant-bypass flag, format `schema_version`, per-table column allow-list (defense-in-depth + PII subset on the export side).
- **Schema deviation from ADR 0022 D2: none.** S-016 is a Java module — no schema migrations originate here. The new-stack `legacy_int_id SMALLINT UNIQUE` columns introduced by S-012/013/014 are reference-table identity hooks, not business logic. `legacy_id_map_<entity>` temp tables are session-scoped state, not constraints.

## Edge cases & hidden requirements

- **Legacy soft-delete is bi-table, not flag-on-row.** `FLSDataEntities` filters `WHERE IsDeleted = 0`; `DeletedFLSDataEntities` flips the discriminator and reads the same physical tables with `IsDeleted = 1` (`flsserver/src/FLS.Server.Data/DeletedFLSDataEntities.cs:18` + `FLSDataEntities.cs:922-968`). S-139 export MUST iterate BOTH contexts per soft-deletable entity (~15 types), or use a raw query that ignores the EF filter. Querying only the live context silently loses tombstones — would defeat the port-all policy without warning.
- **Unmapped legacy tables — enumerate in manifest, parity oracle gates.** `LanguageTranslation` (i18n moved to client JSON per C15), `PersonFlightTimeCredit*` (separate epic), `Setting`, `SystemData`, `SystemLog`, `SystemVersion`, `UserAccountState`, `PersonPersonCategory` (collapsed into `person_club`). Each carries a one-line "WHY not mapped" entry. Parity oracle asserts every legacy table is either mapped or explicitly unmapped — no silent gaps (catches schema drift in upstream legacy `main`).
- **S-129 enum re-encoding is an open hook.** V2-V4 ship raw BOOLEAN columns today; S-129 may convert to string enums later. Mapper accepts a per-column re-encoding hook (boolean → `'YES'`/`'NO'`/`'UNKNOWN'`) without restructuring. Concrete re-encoding maps deferred to S-129 implementation.
- **SMALLINT-CHECK enums stay verbatim.** `flight.flight_aircraft_type_id SMALLINT CHECK IN (1,2,4)` (sparse-enum sacred cow), `delivery.process_state_id SMALLINT CHECK IN (10,20,30,99)`, `user.account_state_id SMALLINT` — these are integer enums on both sides; mapper passes them through. S-129 must not over-reach into these columns.
- **FK rewrite with tenant-bypass per FK.** Each mapper FK declaration carries `tenantBypass: boolean`. Person FK rewrites (Flight crew, Reservation pilot, Delivery recipient, Aircraft owner, AircraftAircraftState noticedBy, PlanningDayAssignment) all set `tenantBypass=true` and resolve via the bundle-global `legacy_id_map_person`. Same for `Location` and `Aircraft` (cross-tenant per S-013 amendment).
- **Audit-column user-id rewrite tolerates orphans.** Every `created_by_user_id` / `modified_by_user_id` resolves via `legacy_id_map_user`; if absent (long-deleted legacy User), coerce to NULL + increment a per-Club orphan counter in `migration_run.warnings`. Failing the bundle on these would block every customer migration.
- **`operating_club_id` denormalization is forward-only.** Several new-schema rows carry `operating_club_id` that doesn't exist on the legacy row directly (e.g. `delivery_item.operating_club_id` is derived from parent `delivery.operating_club_id`). Mapper inherits parent-row tenant context per derivation rule documented in the manifest.
- **`legacy_int_id` is application-read-forbidden post-cutover.** S-012/013/014 added these columns specifically as S-016's resolution surface. Column comment forbids application reads; ArchUnit rule prevents `*Repository` reading them.
- **CI fixture content floor: ≥ 2 Clubs, ≥ 1 cross-club Person, ≥ 1 deleted Aircraft (tombstone path), ≥ 1 Booked Delivery with `delivery_number`, ≥ 1 cross-tenant FlightCrew, ≥ 1 AccountingRuleFilter with NULL filter cells.** Anything less leaves entire mapping branches untested.

## Security plan

- **Parity-oracle seed: synthetic Faker-only fixtures.** Generated by `LegacyFixtureSeeder` (programmatic, deterministic seed) under `flsserver/database/test-fixtures/synthetic/`. **Prohibit committing anonymized real-data exports OR storing them as CI secrets.** CI grep guard fails on known operator TLDs in fixture paths. Anonymized real-data snapshots may run locally via a `parity:prod-snapshot` Gradle task gated on an operator secret — not on every CI run.
- **Test-failure diff redaction.** Person column comparisons assert on `sha256(value)` + row-count + length-histogram, never raw values. Free-text Person fields (firstName/lastname/email/phone/address) routed through a `RedactingAssertion` helper that prints `<redacted:sha8>` on mismatch. Non-PII columns (FK ids, enums, dates) print plain. Logback test pattern strips any log line tagged `pii=true`.
- **ArchUnit rules** (mapper module = `alpenflight.migration.bundle.*`):
  - No dependency on `alpenflight.server.person.PersonRepository` or any `@Repository` from `alpenflight.server.*` — mapper I/O is exclusively `StatelessSession` + raw parameter binds. Defends the cross-tenant Person sacred cow against accidental tenant-filtered loads.
  - No `Files.createTempFile` / `File.createTempFile` / `FileOutputStream` — defends S-141's no-plaintext-at-rest invariant.
  - No `Statement.executeQuery(String)` or `createNativeQuery(String + …)` with concatenation — A03 injection.
- **Read-side allowlist per entity** (`Set<String> ALLOWED_COLUMNS`). Unknown column → `BUNDLE_SCHEMA_MISMATCH`, abort before any row write. Reject-only; no "ignore extra" mode.
- **Enum coercion failure surface.** `ENUM_COERCION_FAILED` payload carries `{entity, column, row_legacy_id}` ONLY — never the offending value (could carry PII).
- **Audit-log mapper hand-off.** New rows carry `actor_kind='LEGACY_MIGRATED'` + `legacy_actor_user_id` text + NULL `actor_keycloak_sub`. **Hand-offs:** S-027 test plan adds read-back coverage for the `LEGACY_MIGRATED` variant; S-024 cross-tenant leakage test adds Person + audit_log + system tables to its exemption set.
- **OWASP applicability** (only what's added here): A03 — parameterized JDBC only (ArchUnit). A04 — buggy mapper drops Person rows by silently filtering tenant is the design risk; mitigation is `StatelessSession` contract + ArchUnit + round-trip Person-count assertion. A09 — structured PII redaction in test logs + reports carrying hashes, not values.

## Test plan

- **Pyramid.** Unit ~40 (per-mapper column-list + coercion + FK rewrite + enum re-encoding, fake `ResultSet`-like rows, no DB); IT ~15 (per-cluster against a tiny canned MSSQL + Postgres roundtrip); Parity oracle ~8 entity sentinels + 1 harness (`@Tag("parity")`, excluded from `./gradlew test`).
- **Row-count.** Exact per (`Club`, table). Tombstones included if port-policy. Zero tolerance. Cross-tenant entities (Person, audit, system tables) compared on global totals only — S-051 + S-024 already excluded them from per-tenant assertions.
- **FK-integrity.** Do NOT trust Postgres FK constraints (S-141 defers them). Parity oracle COMMITs, then runs `LEFT JOIN parent WHERE parent.id IS NULL` over every FK declared in Hibernate metadata. Orphan count per FK in the report; non-zero = hard fail.
- **Sampled-value: 1% sample SIZE (`TABLESAMPLE BERNOULLI(1)` MSSQL-side), zero tolerance on sentinel columns.** Sentinels = every FK + every status/enum + every monetary + `created_at`/`updated_at` + every generated column (`delivery_item.total_amount` is integer-math at 4 decimals → exact match against legacy). Free-text + denormalized caches excluded via per-mapper `@ParitySentinel` / `@ParityIgnore` annotations. Per-type comparators normalize known cosmetic differences (UTC tz-shifted timestamps, trailing whitespace).
- **Soft-delete invariant.** Per soft-deletable entity, `count(legacy.IsDeleted=1) == count(new.deleted_on IS NOT NULL)` per Club. Hard fail if tombstones lost.
- **Manifest-coverage gate.** Every legacy table from `FLSDataEntities.cs:47-103` is either mapped or in the explicit-unmapped list. Unaccounted table = hard fail (catches schema drift in upstream legacy).
- **Schema-evolution regression.** `MapperVsSchemaCompatibilityTest` introspects Hibernate metadata + each mapper's `COLUMNS[]`; fails CI if a mapper writes a column the live schema no longer has, or omits a non-nullable column without a default. Catches "new Flyway migration broke a mapper" at unit speed.
- **Fixture pipeline.** Committed legacy schema = `flsserver/database/FLS/Updates/DBUpdate_v1.8.0..v1.8.11.sql` executed in sequence into a Testcontainers `mcr.microsoft.com/mssql/server:2022-latest` (class-scoped reuse). Data = `LegacyFixtureSeeder` Java fixture builder (programmatic — survives schema-quirk amendments). Postgres side: ephemeral Testcontainers PG 17 with `V1..V13` applied. **No committed bundle.** JAR generated in-test by invoking S-139's writer; round-trips via in-process call (not HTTP — that's S-141's e2e).
- **Reports.** `alpenflight/migration-bundle/build/reports/parity/<run-id>/{summary.json, report.md, deltas/*.json}`. CI asserts `summary.json.passed && totalDeltas==0 && fkOrphans==0`. Workflow uploads as artifacts (30-day retention).
- **CI gating.** `@Tag("parity")` runs in a dedicated CI job, PR-gated on `migration-bundle/**` + `flsserver/database/**` path changes; nightly on `main` at 10× fixture scale (gates the nightly job, not PRs).

## Performance plan

- **Mapper hot path: zero per-row POJO allocation.** Stateless singleton mappers; methods take `ResultSet`/`JsonNode` + `PreparedStatement` and bind parameters directly. Value-coercions (boolean→enum-string, GUID byte-swap, nullable-int) are static methods on a `Coercions` utility. POJO-shaped mappers allowed only on the parity-oracle sampled-value path (≤ ~250K rows worst case).
- **Per-mapper allocation budget: 0 HashMap/List construction in the hot loop.** Static `int[]` of column ordinals computed once at mapper construction.
- **legacy_id_map COPY writer owned by S-016.** Byte format pinned inside the library; S-141 only wires `PgConnection.getCopyAPI()` to the writer's `OutputStream`. Per-entity temp-table naming via `LegacyIdMapTables.tempTableName(entityType)`.
- **Parity oracle wallclock budget: PR CI ≤ 10 min absolute ceiling** (steady-state ~4 min: 90 s SQL Server cold-start + 60-90 s decrypt+ingest + 30-60 s diff). If a refactor pushes past 10 min, row-count + FK-integrity stays on PR, sampled-value moves to nightly. Nightly variant at 10× scale, ≤ 60 min budget.
- **Testcontainers SQL Server: per-class `@Container static`, Ryuk on, NO `withReuse(true)`** (cross-PR state-bleed risk on shared runners). Seed loaded once via `LegacyFixtureSeeder`; per-test isolation via `TRUNCATE … RESTART IDENTITY CASCADE`.
- **Jackson Streaming everywhere.** One reusable `JsonGenerator`/`JsonParser` per stream; no per-row `ObjectMapper.writeValueAsString`. NDJSON: ASCII-LF delimiter, UTF-8, null-not-omitted (distinguishes "explicit null" from "absent"), UUID v7 in 36-char canonical string.
- **Microbench (JMH) on the FlightCrew mapper.** 1M synthetic rows through `map(JsonNode, PreparedStatement)`. Pass: ≥ 200K rows/sec single-thread, ≤ 50 MB allocation/sec (`-prof gc`). Regression = -20% throughput or +50% alloc-rate vs baseline.

<!-- modernize-refine: end -->
