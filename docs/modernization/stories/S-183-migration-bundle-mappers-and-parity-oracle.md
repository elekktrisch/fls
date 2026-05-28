---
id: S-183
title: Migration-bundle — full mapper coverage + parity oracle harness
epic: E-02
status: todo
depends_on: [S-016]
integration_base: integration/migration
origin: scope-split
origin_story: S-016
acceptance:
  - All remaining ~59 mappers across `identity/`, `flight/`, `accounting/` sub-packages — bidirectional `writeNdjson` (export-side, JDBC `ResultSet` → Jackson `JsonGenerator`) + `readEntity` (ingest-side, Jackson `JsonNode` → `PreparedStatement`) per the contract pinned in S-016's `Mapper` interface.
  - `Manifest` typed class — per-entity port-policy, per-entity tombstone-policy, per-FK tenant-bypass flag, `schema_version`, per-table column allow-list. Jackson-serialized JSON on the wire; consumed by both S-139 and S-141.
  - `legacy_id_map_<entity>` COPY-writer byte format owned by this module + a `LegacyIdMapWriter` helper that S-141 wires `PgConnection.getCopyAPI()` to via the `OutputStream` interface pinned in S-016's design notes.
  - Two-tier reference-data resolution: SYSTEM_GLOBAL refs (`country`, `language`, `start_type`, `role`, `club_state`, unit types, `extension_type`, `accounting_rule_filter_type`, `accounting_unit_type`) resolve via `legacy_int_id`; TENANT_SCOPED refs port from bundle via per-bundle map.
  - Audit-log mapper produces rows with `actor_kind='LEGACY_MIGRATED'` + `legacy_actor_user_id` text + NULL `actor_keycloak_sub`. Orphan actor refs → synthetic `legacy_orphan_actor_id` UUID v7 + warning into `migration_run.warnings`.
  - Unmapped-table registry — `LanguageTranslation`, `PersonFlightTimeCredit*`, `Setting`, `SystemData`, `SystemLog`, `SystemVersion`, `UserAccountState`, `PersonPersonCategory` each carry a manifest "WHY not mapped" entry. Parity oracle gates on every legacy table being mapped or explicitly unmapped.
  - **Parity oracle harness.** Testcontainers MSSQL 2022 (per-class reuse) seeded by `LegacyFixtureSeeder` (Faker-only, deterministic seed) + Testcontainers Postgres 17. Round-trip via in-process call to S-139's writer (subprocess via `:migration-tool:shadowJar` once that lands; in-process producer-library call meanwhile) → S-141 ingest → diff. Reports under `build/reports/parity/<run-id>/{summary.json, report.md, deltas/*.json}`. CI asserts `summary.json.passed && totalDeltas==0 && fkOrphans==0`. Gated `@Tag("parity")`, excluded from `./gradlew test`, runs in a dedicated CI job (PR-gated on `migration-bundle/**` + `flsserver/database/**` paths; nightly at 10× scale on `main`).
  - Row-count diff: exact per (Club, table). FK-integrity: post-commit `LEFT JOIN parent WHERE parent.id IS NULL` over every Hibernate-declared FK; orphan count must be zero. Sampled-value: 1% sample SIZE (`TABLESAMPLE BERNOULLI(1)` MSSQL-side); zero tolerance on sentinel columns (every FK + status enum + monetary + timestamp + generated column). Free-text + denormalized caches excluded via per-mapper `@ParitySentinel` / `@ParityIgnore` annotations.
  - Soft-delete invariant: per soft-deletable entity, `count(legacy.IsDeleted=1) == count(new.deleted_on IS NOT NULL)` per Club. Hard fail if tombstones lost.
  - `MapperVsSchemaCompatibilityTest` — introspects Hibernate metadata + each mapper's `COLUMNS[]`; fails CI if a mapper writes a column the live schema no longer has, or omits a non-nullable column without a default.
  - **ArchUnit rules** in this module: no dependency on `alpenflight.server.person.PersonRepository` or any `@Repository` from `alpenflight.server.*`; no `Files.createTempFile` / `File.createTempFile` / `FileOutputStream`; no `Statement.executeQuery(String)` or `createNativeQuery` with string concatenation.
  - **JMH microbench** on the FlightCrew mapper — 1M synthetic rows through `map(JsonNode, PreparedStatement)`. Pass: ≥ 200K rows/sec single-thread; ≤ 50 MB allocation/sec (`-prof gc`). Regression threshold: -20% throughput OR +50% alloc-rate vs baseline.
  - **CI workflow wiring** — `.github/workflows/ci.yml` builds the `alpenflight/migration-bundle/` module on every PR that touches `alpenflight/migration-bundle/**`. Parity job is the separate path-filtered workflow above.
  - **S-027 + S-024 cross-story hand-offs land** — S-027's test plan adds read-back coverage for the `LEGACY_MIGRATED` actor_kind variant; S-024's cross-tenant leakage CI exemption list adds Person + audit_log + system tables.
estimate: L
adr_refs: [0002, 0003, 0008, 0019, 0022, 0023]
parity_test: tests/migration/schema-parity.spec.ts (new)
---

## Context

Scope-split from [S-016](implemented/S-016-data-migration-script.md). S-016 shipped the walking skeleton (Gradle module + `Mapper` interface + `EntityType` + `LegacyIdMapTables` + `Coercions` + one concrete `CountryMapper`). This story fills in the remaining ~59 mappers, the parity oracle harness, ArchUnit rules, the JMH bench, and the CI workflow wiring.

All the load-bearing design decisions live in S-016's refinement — see the `<!-- modernize-refine: start --> / end -->` block of the implemented story body. This story is implementation-only; no separate refinement pass is required unless a fork surfaces mid-build.

## Cross-story contracts

- **Consumes:** S-016's `Mapper` interface + `EntityType` + `LegacyIdMapTables` + `Coercions` skeleton; ADR 0018 aggregate boundaries; ADR 0019 UUID v7 strategy.
- **Produces:** the full mapper coverage that **S-141** (encrypted-bundle ingest pipeline) and **S-139** (legacy-export JAR) both depend on at runtime. Once this lands, S-141 implement is unblocked.
- **Hand-offs:** S-027 (audit infra) test plan must add `LEGACY_MIGRATED` read-back coverage. S-024 (cross-tenant leakage CI) exemption list must add Person + audit_log + system tables.

## Tasks

- [ ] Manifest typed class + Jackson wiring.
- [ ] ~59 mappers + their column lists + coercion tables + FK declarations.
- [ ] `LegacyIdMapWriter` (COPY byte format).
- [ ] SYSTEM_GLOBAL ref resolver via `legacy_int_id`.
- [ ] Audit-log mapper with `LEGACY_MIGRATED` discriminator + orphan-actor synthesis.
- [ ] Unmapped-table registry + manifest-coverage gate.
- [ ] Parity oracle harness (MSSQL + Postgres Testcontainers + `LegacyFixtureSeeder` + diff + reporter).
- [ ] ArchUnit rules.
- [ ] `MapperVsSchemaCompatibilityTest`.
- [ ] JMH bench on `FlightCrewMapper`.
- [ ] CI workflow wiring for the new module + parity job.
- [ ] S-141 + S-139 contract verification (in-process producer-library call until S-139 JAR lands).

## Notes

- Estimate is `L` (full scope of S-016's deferred work).
- S-141's implement is blocked on this story.
- A new V14 Flyway migration may be needed if the schema-mapping reveals gaps not anticipated by S-012/S-013/S-014 — file as a sibling under this story.
