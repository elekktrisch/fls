---
id: S-187a
title: Parity oracle harness — remaining 25 mappers + coverage gates + negative-path + mutation-smoke
epic: E-02
status: todo
depends_on: [S-187, S-141]
integration_base: integration/migration
origin: scope-split
origin_story: S-187
refined: false
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

Scope-split from [S-187](S-187-migration-parity-oracle-harness.md). S-187 shipped the round-trip plumbing (producer harness + tar.gz envelope + consumer harness + diff engine + reports infra + `MapperVsSchemaCompatibilityTest`) exercised end-to-end against three identity-group mappers (Country + Club + User). This story brings the harness to "all 28 mappers exercised + coverage gates + producer-drop reconciliation + two-pass UPDATE + composite location parity + negative-path + mutation-smoke" — the full S-187 acceptance.

Splitting was the right call at S-187 implement: the full FLSTest legacy schema bootstrap (62+ DBUpdate scripts to MSSQL) is itself a substantial sub-task that the refinement under-weighted, and the producer-drop reconciliation depends on S-141 (consumer-side warnings surface) which has not yet landed.

## Cross-story contracts

- **Consumes:** S-187's harness skeleton (`ParityOracleHarnessTest`, `LegacyFixtureSeeder`, `BundleStream`, `ProducerHarness`, `ConsumerHarness`, `ParityDiffEngine`, `ParityReports`, `MapperLegacyBindings`); S-141's `migration_run.warnings` surface; S-186's `t_audit_log` + `actor_kind` enum.
- **Produces:** the full 28-mapper parity oracle that S-141 (ingest), S-139 (export), and every future migration story rely on as the rehearsal mechanism.

## Notes

- See S-187's refinement block for the load-bearing edge-case list (MSSQL `datetime2(7)` precision, monetary `decimal(18,4)`, `TABLESAMPLE` determinism, cross-tenant FK sweep scope). This story re-confirms each against the full-mapper sweep.
- The full FLSTest schema bootstrap should reuse `flsserver/database/FLSTest/1 create/1 Create Database.sql` + `2 alter/DBUpdate_v*.sql` + `3 insert/*.sql` in the legacy order. Surface a Gradle helper for applying the script tree to a Testcontainers MSSQL instance.
- Refinement before implement (operator runs `/modernize-refine S-187a`).
