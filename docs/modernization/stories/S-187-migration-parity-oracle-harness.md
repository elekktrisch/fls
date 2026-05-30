---
id: S-187
title: Migration-bundle — parity oracle harness + LegacyFixtureSeeder + MapperVsSchemaCompatibilityTest
epic: E-02
status: in_progress
started_at: 2026-05-30
depends_on: [S-183, S-184, S-185, S-186]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: true
refined_at: 2026-05-30
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
context7_last_checked: 2026-05-30
github_issue: 175
github_pr: 176
acceptance:
  - **Parity oracle harness.** Testcontainers MSSQL 2022 (per-class reuse) seeded by `LegacyFixtureSeeder` (Faker-only, deterministic seed) + Testcontainers Postgres 17. Round-trip via in-process call to the producer side of every mapper → in-memory `tar.gz` → consumer side → diff. Reports under `build/reports/parity/<run-id>/{summary.json, report.md, deltas/*.json}`. Asserts `summary.json.passed && totalDeltas==0 && fkOrphans==0`. Gated `@Tag("parity")`, excluded from `./gradlew test`.
  - **Row-count diff** exact per (Club, table). **FK-integrity** via reflective walk of Hibernate-declared FKs; orphan count must be zero. **Sampled-value** 1% sample (`TABLESAMPLE BERNOULLI(1) REPEATABLE(<seed>)`); zero tolerance on sentinel columns (every FK + status enum + monetary + timestamp + generated column). `@ParitySentinel` / `@ParityIgnore` annotations (from S-183) opt columns in/out.
  - **Soft-delete invariant** per soft-deletable entity per Club. Hard fail if tombstones lost.
  - `LegacyFixtureSeeder` Faker-only, seed from sysprop `parity.seed` default `42`, targets post-final-DBUpdate FLSTest schema. ≥ 2 Clubs mandatory (exercises per-bundle Person sub-map). Audit fixture includes three actor shapes: real actor, orphan actor, NULL actor.
  - **`MapperVsSchemaCompatibilityTest`** lives in `alpenflight/server/` (uses live Hibernate `Metadata`). Asserts `bundleMapper.columns() ⊆ hibernateTable.columns()` + every non-nullable non-defaulted column is in `columns()`. Skips `@Generated`, `legacy_int_id` shadow columns, `@TenantId` discriminator.
  - **In-process producer is the temporary affordance.** Harness wires producer side directly until `:migration-tool:shadowJar` (S-139) lands; file a sibling task referenced from S-139's done-criteria for the `ProcessBuilder` swap.
estimate: L
adr_refs: [0002, 0003, 0008, 0019, 0022, 0023]
parity_test: tests/migration/schema-parity.spec.ts (new)
---

## Context

Scope-split from [S-183](S-183-migration-bundle-mappers-and-parity-oracle.md). Once all ~28 mappers (S-184 + S-185 + S-186) land, this story builds the parity oracle harness that round-trips a synthetic legacy bundle through every mapper and asserts zero deltas.

## Cross-story contracts

- **Consumes:** S-183's `Mapper` / `Manifest` / `LegacyIdMapWriter` + S-184/S-185/S-186 mappers. Hibernate `Metadata` via `alpenflight/server/` test context.
- **Produces:** parity oracle that S-141 (ingest) + S-139 (export) and every future migration story rely on as the rehearsal mechanism.

## Notes

- See S-183's refinement block (`<!-- modernize-refine: start --> / end -->` of `S-183-migration-bundle-mappers-and-parity-oracle.md`) for the load-bearing edge-case list: MSSQL `datetime2(7)` precision, monetary `decimal(18,4)`, `TABLESAMPLE` determinism, cross-tenant FK sweep scope, etc. Refinement of this story re-confirms each against the post-mappers code.

<!-- modernize-refine: start -->

## Design notes

Deltas on top of S-183's refinement block (inherited; not re-stated).

- **Harness lives in a dedicated `src/parity/java/` source set** under `migration-bundle/`, package `ch.alpenflight.migration.bundle.parity`, registered in `build.gradle.kts` with its own `parityTest` task. Testcontainers (`testcontainers-junit-jupiter`, `testcontainers-mssqlserver`, `testcontainers-postgresql`) stay out of `testImplementation` — `./gradlew test` keeps its sub-30-s budget. Matches S-188's `src/jmh/java/` source-set precedent. (Deviation from S-183's `@Tag("parity")` line; operator-confirmed 2026-05-30.) `LegacyFixtureSeeder` co-lives in the same source set; Faker moves from `testImplementation` to `parityImplementation`.
- **`MapperVsSchemaCompatibilityTest`** stays in `alpenflight/server/` under the existing Spring Boot test context (S-183 pin — confirmed, not moved). Parametrized over `ArchitectureTest.KNOWN_MAPPERS` (28 cases). Skip-set widens beyond S-183: `keycloak_sub` (S-028 single-writer; mapper binds NULL structurally), and on `t_audit_log` the V18-only columns `legacy_orphan_actor_id` / `legacy_actor_user_id` / `actor_kind` (no non-migrated counterpart).
- **Coverage gates piggy-back on `ArchitectureTest.KNOWN_MAPPERS`** — no second classpath scan (drift already trapped by `knownMappersListCoversEveryConcreteMapperOnTheClasspath`). Four gates run pre-diff: (a) ≥ 1 seeded MSSQL row per `Mapper` per Club (tenant-scoped) or per `Mapper` total (SYSTEM_GLOBAL); (b) every `Manifest.entityPolicies` key is a `KNOWN_MAPPERS` entity; (c) every `UnmappedTables.REGISTRY` table has zero rows post-ingest; (d) every `permittedSparseEnumValues` key has ≥ 1 seeded row per permitted value per Club. Missing → harness fails with explicit `seed gap: <EntityType>[@<ClubId>]` diagnostic.
- **Composite `legacy_id_map_location` parity** (S-185 fan-out — Location is the row-count exclusion). Assert `count(legacy_id_map_location) == Σ_legacyLocation |referencing-Club set|`. FK walker special-cases the entry: `Flight.{start_location_id, ldg_location_id}` resolves via `(legacy_guid, operating_club_id)`; `Aircraft.homebase_id` via `(legacy_guid, managing_club_id)` with deterministic lowest-UUID-v7 fallback when no exact match. Determinism rests on UUID v7 monotonicity + pinned `parity.seed`.
- **Diff engine composition.** Per column: skip if `@ParityIgnore` OR in the structural skip-set (`@Generated`, `legacy_int_id` shadow, `@TenantId` discriminator); else strict-equality compare; `@ParitySentinel` plus the implicit sentinel set (every FK + status enum + monetary `numeric` + `timestamptz` + generated col) gets zero-tolerance treatment on the 1% sample. Skip-set wins over sentinel-set on overlap. LEGACY_MIGRATED audit-row NULL invariants (`actor_keycloak_sub`, `tenant_club_id`, `before_state`, `after_state`) are pinned via `actor_kind=LEGACY_MIGRATED` as the diff-engine sentinel — no second `@ParityIgnore` flavor needed (S-186 pin).
- **Producer-drop reconciliation.** Several mappers drop rows producer-side with a `migration_run.warnings` code: `AIRCRAFT_NO_MANAGING_CLUB` (S-185), `RESERVATION_NO_PILOT` + `ARTICLE_DUPLICATE_NUMBER` (S-186). Row-count diff is `legacy_count − count(warnings WHERE code=<dropped-row-code> AND legacy_entity=<entity>) == new_count` per `(Club, table)`. Harness reads `migration_run.warnings` post-ingest and folds the drops into the equality; zero warnings in the happy fixture means a strict-equality fallthrough.
- **Two-pass UPDATE simulation.** Harness models S-141's two-pass for `PersonCategory.parent_person_category_id` and `Flight.tow_flight_id` (pass 1 insert with NULL; pass 2 UPDATE via `legacy_id_map_*`). Skipping them would mask the only producer-side ordering bug class the oracle exists to catch. FK sweep treats both as in-scope and asserts zero orphans post-pass-2.
- **Reports `<run-id>` encoding.** `<git-short-sha>-<parity.seed>-<parity.scale>` so nightly 10× produces 10 distinguishable subdirs (not overwrites). `summary.json` includes per-mapper coverage breakdown; `deltas/<EntityType>.json` keyed by legacy GUID — predictable diff target between PR runs.
- **Sibling task for S-139 shadowJar swap.** File `S-139a-parity-harness-processbuilder-swap.md` with `depends_on: [S-139, S-187]`. Single AC: replace the in-process producer call with `ProcessBuilder` invoking `migration-tool-all.jar`; assert no behavioural delta on the same seeded fixture. Reference added to S-139's done-criteria as a non-blocking follow-up.
- **ADR 0022 D2 conformance.** Harness ships zero Flyway migrations, zero CHECK constraints, zero generated columns, zero triggers. Consumes both schemas as-is. Step 4.5 update to `legacy-migration-plan.md` is a no-op.

## Edge cases & hidden requirements

- **`LegacyFixtureSeeder` shape obligations** beyond S-183: ≥ 1 `Flights.AirStateId=5` row per Club (S-185 lossy-translation sentinel); ≥ 1 row for every value in `permittedSparseEnumValues` per Club (today `Flight.AircraftTypeId ∈ {1,2,4}`); audit fixture seeds the same legacy `UserName` across ≥ 2 rows to exercise S-183 orphan-dedupe (single `legacy_orphan_actor_id` per distinct UserName) and includes one row whose UserName ALSO matches a real `Users.UserName` (resolves to real `actor_user_id`).
- **Self-FK two-pass fixtures.** Seed a tow chain across ≥ 2 flights per Club (with one tombstoned tow ref to exercise S-185's preserved-chain invariant) and a `PersonCategory` tree ≥ 2 deep per Club. The reflective FK walk consults Hibernate metadata (not `Mapper.foreignKeys()`) so self-FKs aren't walked past.
- **AircraftReservation degenerate range.** Seed ≥ 1 empty-range row (`reservation_start == reservation_end`) per Club; passes through to Postgres as-is (aggregate-read rejection is out of scope — covered by S-068).
- **Negative-path bundle-reject tests** (separate `@Tag("parity-reject")` cases, not part of happy round-trip): legacy `LanguageId` outside the V2 seed set → `BUNDLE_LANGUAGE_NOT_SEEDED` (S-184); bumped `schemaVersion` → `BUNDLE_SCHEMA_UPGRADE_NEEDED`; legacy `Aircraft.SpotLink` starting `http://` → `BUNDLE_AIRCRAFT_SPOT_LINK_NOT_HTTPS` (S-185); unmapped legacy table not in `UnmappedTables.REGISTRY` → "register or unmap" diagnostic.
- **`MapperVsSchemaCompatibilityTest` skip-set additions** (over S-183's `@Generated` / `legacy_int_id` / `@TenantId`): `keycloak_sub` on `t_user`; `legacy_orphan_actor_id` + `legacy_actor_user_id` + `actor_kind` on `t_audit_log` — these are V18-only columns with no non-migrated counterpart and mapper binds NULL / synthesised values structurally.
- **Container lifecycle on assertion failure.** Both Testcontainers torn down in `@AfterAll` regardless of test outcome (`withReuse(false)`); no inter-run state carryover. Tear-down covers `parity-reject` cases where the consumer-side aborts mid-stream.
- **Determinism rests on a frozen call graph.** `parity.seed` pins Faker; UUID v7 monotonicity pins the Aircraft homebase fallback selection; `TABLESAMPLE BERNOULLI(1) REPEATABLE(<seed>)` pins the sample. A re-ordering of `LegacyFixtureSeeder` Faker calls — even logically equivalent — perturbs every downstream UUID. Any reorder is a behaviour change; harness-self-test (Test plan) catches accidental regressions.

## Security plan

Inherits S-183 (no plaintext bundle bytes at rest, per-bundle Person sub-map `ON COMMIT DROP`, audit-actor orphan UUIDs bundle-local, `@AuditRedact` on `legacy_actor_user_id`, tenant-bypass FK allow-list structurally enforced at `Manifest` parse). Harness adds:

- **Tenant-isolation invariant the oracle asserts.** For every `Manifest.entityPolicies` entry with empty `tenantBypassFks`, all FK targets must resolve within the same `operating_club_id`; `fkOrphans == 0` subsumes this. Any non-empty `tenantBypassFks` in the seeded manifest must land only on the 11 allow-list entities (`Manifest.TENANT_BYPASS_ALLOW_LIST`) — defense-in-depth against producer drift.
- **Plaintext-leak smoke test reach widens.** S-183's marker-plant assertion covers `java.io.tmpdir` AND `build/reports/parity/<run-id>/`. Reports MAY contain row-counts, column names, sentinel-mismatch context, legacy GUIDs (forensic surface per ADR 0019). Reports MUST NOT contain raw Faker PII columns (`Users.UserName`, `Persons.{Firstname,Lastname,Email,AddressLine1,PrivateMobile}`, licence/medical-cert numbers). Diff-row emitter consults a PII-column allow-list; mismatches on PII columns surface column name + redacted placeholder only.
- **`LegacyFixtureSeeder` data classification.** Faker output is synthetic, no real-PII risk. MSSQL container ephemeral, `withReuse(false)`, JDBC-URL-only access (Testcontainers-assigned host port), torn down in `@AfterAll` even on assertion failure.
- **CI-artifact handling.** If `build/reports/parity/<run-id>/` is uploaded on failure, the upload step runs the same PII-column scrubber over diff content (belt-and-braces with the in-process guard). Documented in the CI job, not code in this module.

## Test plan

Deltas on top of S-183's inherited test plan (pyramid, oracle cases, fixture conventions, JMH gates, S-024 / S-027 hand-offs). Test method names live in the test files.

- **Fixture-coverage gate (the gate that makes "all 28 mappers exercised" enforceable).** Pre-flight: for every `KNOWN_MAPPERS` entry, assert ≥ 1 seeded row per `(EntityType, Club)` (tenant-scoped) or ≥ 1 row total (SYSTEM_GLOBAL). Missing → `seed gap: <EntityType>[@<ClubId>]` before any diff runs. Same gate covers `permittedSparseEnumValues` per-value rows.
- **Sparse-enum-aware seeding.** `LegacyFixtureSeeder` consults `permittedSparseEnumValues` (from `AbstractMapperContractTest`) — currently `FlightMapper.flight_aircraft_type_id ∈ {1,2,4}`; Faker draws from that set when present, else uniform.
- **S-186 audit-row invariants.** Three-actor fixture (S-183) gets `LEGACY_MIGRATED` parity: assert `actor_kind=LEGACY_MIGRATED` ⇒ `actor_keycloak_sub IS NULL ∧ tenant_club_id IS NULL ∧ before_state IS NULL ∧ after_state IS NULL` on every migrated audit row. Real-actor branch asserts FK to `t_user`; NULL-UserName branch asserts both `actor_user_id` and `legacy_orphan_actor_id` NULL.
- **Composite legacy_id_map_location resolution.** Oracle's FK walker special-cases the entry — assert `Flight.{start_location_id, ldg_location_id}` resolves through `(legacy_guid, operating_club_id)`; `Aircraft.homebase_id` through `(legacy_guid, managing_club_id)` with deterministic lowest-UUID-v7 fallback.
- **Two-pass simulation.** Pass 1 inserts with self-FK NULL; pass 2 `UPDATE` resolves via `legacy_id_map_*`. Oracle asserts post-pass-2 zero orphans on `PersonCategory.parent_person_category_id` + `Flight.tow_flight_id`. Pass-1 transient state is not asserted.
- **Container lifecycle.** MSSQL `@Container static` per parity test class (single `ParityOracleHarnessTest` per the Performance plan); Postgres per-method with truncate-between (ADR 0021). `withReuse(false)` on both.
- **Harness self-test (mutation smoke).** One `@Tag("parity-meta")` test wraps a mapper with a decorator that drops a column from `columns()` before round-trip; asserts `summary.json.passed=false ∧ totalDeltas>0` AND `deltas/*.json` names the dropped column. Proves the diff engine isn't silently green. Runs in `parityTest`, not `./gradlew test`.
- **Negative-path bundle-reject cases** (`@Tag("parity-reject")`): `BUNDLE_LANGUAGE_NOT_SEEDED`, `BUNDLE_SCHEMA_{UPGRADE,DOWNGRADE}_NEEDED`, `BUNDLE_AIRCRAFT_SPOT_LINK_NOT_HTTPS`, unmapped-table-no-registry-entry — each asserts the specific error code + that the Postgres transaction rolled back (zero new rows). Run in `parityTest` alongside the happy round-trip.
- **`MapperVsSchemaCompatibilityTest` (in `alpenflight/server/`).** Parametrized over 28 mappers via `KNOWN_MAPPERS`. Asserts column subset + non-nullable non-defaulted coverage; skip-set per the Design notes addition.
- **Gating.** `useJUnitPlatform { excludeTags("parity-meta") }` on the `parityTest` task is unnecessary because the source set itself is gated — `parityTest` runs every class in `src/parity/java/`. `./gradlew test` does not see the parity source set at all. Nightly workflow: `./gradlew parityTest -Dparity.scale=10 -Dparity.seed=42`. Failure opens a tracking issue; does not block `main`.

## Performance plan

Inherits S-183 (FlightCrew hot path ≥ 200K rows/sec single-thread, ≤ 50 MB/s alloc, COPY binary for `legacy_id_map`, ANY(?::uuid[]) batches, UUID v7 B-tree locality, `synchronous_commit = OFF`). Harness adds:

- **Container reuse calculus.** Single `ParityOracleHarnessTest` class — 1× MSSQL cold start (~30-60 s) + 1× Postgres cold start (~5-10 s) per parity-job run. Splitting per-EntityType-group would multiply start tax by ~6; failure-isolation gain not worth the walltime, since per-`(EntityType, column)` sentinel deltas already pinpoint root cause.
- **`parity.scale` sysprop.** Multiplies per-entity row count (default `1`; nightly `10`). Club count stays pinned at 2 — scaling Clubs would multiply Person sub-map size + cross-tenant FK sweep cost without validating anything new.
- **Sampled diff cost.** Nightly 10× (~250K FlightCrew rows) × `TABLESAMPLE BERNOULLI(1)` ≈ 2500 sampled rows × ~10 sentinels = ~25K comparisons. Dominated by MSSQL BERNOULLI scan, not Java diff.
- **FK sweep batch size.** Reuses S-141's 500-row `ANY(?::uuid[])` shape; harness MAY shrink to 100 for diff readability (no throughput SLO at parity scale).
- **Walltime budget.** PR-gated normal scale ≤ 5 min; nightly 10× on `main` ≤ 30 min. No latency SLO — batch test path, not request path.

<!-- modernize-refine: end -->
