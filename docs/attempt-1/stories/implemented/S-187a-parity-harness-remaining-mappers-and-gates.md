---
id: S-187a
title: Parity oracle harness — remaining 25 mappers + coverage gates + negative-path + mutation-smoke
epic: E-02
status: done
started_at: 2026-05-31
done_at: 2026-05-31
merged: true
merged_at: 2026-05-31
depends_on: [S-187, S-141]
integration_base: integration/migration
origin: scope-split
origin_story: S-187
refined: true
refined_at: 2026-05-31
refined_specialists: [requirements, solution, qa, security, performance]
github_issue: 188
github_pr: 189
acceptance:
  - **Remaining 25 mappers exercised end-to-end.** Bring `LegacyFixtureSeeder` to the obligations the S-187 design notes enumerate: ≥ 1 `Flights.AirStateId=5` row per Club; ≥ 1 row per permitted sparse-enum value per Club; audit fixture with three actor shapes (real, orphan, NULL) plus orphan-dedupe; self-FK two-pass chains (PersonCategory tree, Flight tow chain with a tombstoned ref); ≥ 1 degenerate `AircraftReservation` range; full FLSTest schema bootstrap via `flsserver/database/FLSTest/` scripts.
  - **Coverage gates pre-diff** (S-187 Design notes (a)–(d)): ≥ 1 seeded row per `KNOWN_MAPPERS` per Club (or per mapper total for SYSTEM_GLOBAL); every `Manifest.entityPolicies` key in `KNOWN_MAPPERS`; every `UnmappedTables.REGISTRY` table zero rows post-ingest; every `permittedSparseEnumValues` key with ≥ 1 seeded row per permitted value per Club. Missing → harness fails with `seed gap: <EntityType>[@<ClubId>]` diagnostic.
  - **Producer-drop reconciliation.** Read `migration_run.warnings` post-ingest; fold S-185 + S-186 drop codes (`AIRCRAFT_NO_MANAGING_CLUB`, `RESERVATION_NO_PILOT`, `ARTICLE_DUPLICATE_NUMBER`) into the row-count equality per `(Club, table)`. Requires S-141 (consumer) and S-186 (audit_log + migration_run surface).
  - **Two-pass UPDATE simulation** for `PersonCategory.parent_person_category_id` + `Flight.tow_flight_id` (pass 1 insert NULL; pass 2 UPDATE via `legacy_id_map_*`). FK sweep asserts zero orphans post-pass-2.
  - **Composite legacy_id_map_location parity.** Special-case the FK walker for `Flight.{start_location_id, ldg_location_id}` (resolve via `(legacy_guid, operating_club_id)`) and `Aircraft.homebase_id` (resolve via `(legacy_guid, managing_club_id)` with deterministic lowest-UUID-v7 fallback). Assert `count(legacy_id_map_location) == Σ_legacyLocation |referencing-Club set|`.
  - **Sampled-value diff** (1% `TABLESAMPLE BERNOULLI(1) REPEATABLE(<seed>)`) over the sentinel-column set (every FK + status enum + monetary `numeric` + `timestamptz` + generated column). Zero tolerance on sentinels.
  - **FK orphan walk** via reflective walk of Hibernate-declared FKs; cross-tenant FKs walk through the per-bundle Person sub-map. `summary.json.fkOrphans` is asserted == 0 in the happy round-trip.
  - **Soft-delete invariant** per soft-deletable entity per Club. Hard fail if tombstones lost.
  - **Negative-path bundle-reject cases** (`@Tag("parity-reject")`): `BUNDLE_LANGUAGE_NOT_SEEDED`, `BUNDLE_SCHEMA_{UPGRADE,DOWNGRADE}_NEEDED`, `BUNDLE_AIRCRAFT_SPOT_LINK_NOT_HTTPS`, unmapped-table-no-registry-entry. Each asserts the specific error code + that the Postgres transaction rolled back (zero new rows).
  - **Harness self-test (mutation smoke, `@Tag("parity-meta")`).** Wrap a mapper with a decorator that drops a column from `columns()` before round-trip; asserts `summary.json.passed=false ∧ totalDeltas>0` AND `deltas/*.json` names the dropped column.
  - **Tenant-isolation invariant.** For every `Manifest.entityPolicies` entry with empty `tenantBypassFks`, all FK targets must resolve within the same `operating_club_id` (subsumed by `fkOrphans == 0`). Any non-empty `tenantBypassFks` in the seeded manifest must land only on the 11 allow-list entities.
  - **PII-column allow-list at report time.** Diff-row emitter consults a PII-column allow-list; mismatches on PII columns (`Users.UserName`, `Persons.{Firstname,Lastname,Email,AddressLine1,PrivateMobile}`, licence/medical-cert numbers) surface column name + redacted placeholder only.
  - **Walltime budget.** PR-gated normal scale ≤ 5 min; nightly 10× ≤ 30 min.
estimate: L
adr_refs: [0008, 0019, 0022, 0023]
parity_test: alpenflight/migration-bundle/src/parity/java/ch/alpenflight/migration/bundle/parity/ParityOracleHarnessTest.java (existing — extends here)
---

## Context

Scope-split from [S-187](implemented/S-187-migration-parity-oracle-harness.md). S-187 shipped the round-trip plumbing exercised against five identity mappers. S-187a's full scope was "all 28 mappers + coverage gates + producer-drop reconciliation + two-pass UPDATE + composite location parity + negative-path + mutation-smoke."

**Shipped here (infra slice):** the container-independent parity machinery, each unit-tested in `./gradlew test` — `ParityCoverageGate`, `ProducerDropWarning` + `ProducerDropReconciliation`, `ParityValueRedactor`, `ColumnDroppingMapper`, `ForeignKeyOrphanPlan`, `Manifest.tenantBypassAllowList()` — plus the `parityRejectTest` / `parityMetaTest` Gradle tasks and the `summary.json.fkOrphans` measured-value plumbing.

**Deferred to [S-187b](S-187b-parity-roundtrip-identity-group.md) / [S-187c](S-187c-parity-roundtrip-flight-group.md) / [S-187d](S-187d-parity-roundtrip-accounting-and-cross-cutting.md):** the schema-coupled round-trip breadth — the per-group `MapperLegacyBindings` entries, `LegacyFixtureSeeder` fixtures, and live MSSQL→Postgres round-trip. It needs the FLSTest MSSQL container, which isn't available in the dev sandbox; it is verified only in CI's parity job. Splitting the verifiable machinery from the Docker-gated breadth follows ADR 0022 D1 (working software over a large unverifiable diff).

## Cross-story contracts

- **Consumes:** S-187's harness skeleton (`ParityOracleHarnessTest`, `LegacyFixtureSeeder`, `ProducerHarness`, `ConsumerHarness`, `ParityDiffEngine`, `ParityReports`, `MapperLegacyBindings`).
- **Produces:** the parity machinery S-187b/c/d wire into the live round-trip.

## Load-bearing decisions

These survive into S-187b/c/d (the AC text predates them; honor these over the AC where they conflict):

- **In-process producer warnings, not `migration_run`.** The harness has no `t_migration_run` row — `ProducerHarness` collects bundle-local `ProducerDropWarning`s and `ProducerDropReconciliation` folds them; `migration_run.warnings` is only the S-141 production analog. `migration-bundle` cannot depend on the server-side `MigrationRunWarning` (the dependency runs server → bundle).
- **`ARTICLE_DUPLICATE_NUMBER` is a reject, not a row-count drop** (Swiss OR Art. 957a — silent dedupe would rewrite legal records). Only `AIRCRAFT_NO_MANAGING_CLUB` + `RESERVATION_NO_PILOT` fold into row counts (`ProducerDropReconciliation.ROW_DROP_CODES`).
- **PII redaction is fail-CLOSED** (`ParityValueRedactor`): a value emits only if its column is a `@ParitySentinel` and not PII; everything else redacts, so an unannotated future column can't leak. The PII set is a superset of `tenant-rules.yaml pii_columns` for mapped tables; the bundle has no Spring dep so the drift guard (bundle set ⊇ tenant-rules) is a server-side test deferred to S-187d.
- **Tenant invariant references `Manifest.tenantBypassAllowList()`** (the 11 cross-tenant entities) — never re-listed. `Manifest` already rejects a widened bypass set at parse; the empty-bypass arm is subsumed by `fkOrphans == 0`.
- **FK-orphan source is `Mapper.foreignKeys()`, not `information_schema`** (`ForeignKeyOrphanPlan`): the bundle has no Hibernate dep, and `foreignKeys()` carries the self-FK / cross-tenant exclusions the harness resolves through the per-bundle Person sub-map.
- **Self-FK to a tombstoned/dropped parent → resolve-to-NULL, keep the child** (two-pass UPDATE, S-187b): the column is nullable; parity preserves the legacy child.
- **Composite `legacy_id_map_location`** (S-187c): key on `(legacy_guid, operating_club_id)` for Flight start/ldg, `(legacy_guid, managing_club_id)` for `Aircraft.homebase_id` with a deterministic lowest-UUID-v7 fallback (must be seed-stable); assert `count == Σ_legacyLocation |referencing-Club set|`.
- **`AirStateId=5` is a producer-side translation, not a row drop** (S-187c): `FlightPlanOpen → flight_plan_opened_on`; other air-state values produce no delta and counts still match.
- **Coverage gate (a/b/c) shipped; sparse-enum dimension (d) deferred to S-187d** (it needs the per-mapper permitted-value sets, which arrive with the fixtures).
- **Machinery lives in `src/main`, not `src/parity`** — a deliberate tradeoff so it unit-tests in the fast `./gradlew test` lane (the parity source set is Docker-gated and excluded from `check`), mirroring `ParityMarkers`/`ParityIgnore`/`ParitySentinel`. No schema change (ADR 0022 D2 holds).
