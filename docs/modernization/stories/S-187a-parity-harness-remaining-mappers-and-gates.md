---
id: S-187a
title: Parity oracle harness — remaining 25 mappers + coverage gates + negative-path + mutation-smoke
epic: E-02
status: todo
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

Scope-split from [S-187](S-187-migration-parity-oracle-harness.md). S-187 shipped the round-trip plumbing (producer harness + tar.gz envelope + consumer harness + diff engine + reports infra + `MapperVsSchemaCompatibilityTest`) exercised end-to-end against three identity-group mappers (Country + Club + User). This story brings the harness to "all 28 mappers exercised + coverage gates + producer-drop reconciliation + two-pass UPDATE + composite location parity + negative-path + mutation-smoke" — the full S-187 acceptance.

Splitting was the right call at S-187 implement: the full FLSTest legacy schema bootstrap (62+ DBUpdate scripts to MSSQL) is itself a substantial sub-task that the refinement under-weighted, and the producer-drop reconciliation depends on S-141 (consumer-side warnings surface) which has not yet landed.

## Cross-story contracts

- **Consumes:** S-187's harness skeleton (`ParityOracleHarnessTest`, `LegacyFixtureSeeder`, `BundleStream`, `ProducerHarness`, `ConsumerHarness`, `ParityDiffEngine`, `ParityReports`, `MapperLegacyBindings`); S-141's `migration_run.warnings` surface; S-186's `t_audit_log` + `actor_kind` enum.
- **Produces:** the full 28-mapper parity oracle that S-141 (ingest), S-139 (export), and every future migration story rely on as the rehearsal mechanism.

## Notes

- See S-187's refinement block for the load-bearing edge-case list (MSSQL `datetime2(7)` precision, monetary `decimal(18,4)`, `TABLESAMPLE` determinism, cross-tenant FK sweep scope). This story re-confirms each against the full-mapper sweep.
- The full FLSTest schema bootstrap should reuse `flsserver/database/FLSTest/1 create/1 Create Database.sql` + `2 alter/DBUpdate_v*.sql` + `3 insert/*.sql` in the legacy order. Surface a Gradle helper for applying the script tree to a Testcontainers MSSQL instance.
- Refinement before implement (operator runs `/modernize-refine S-187a`).

<!-- modernize-refine: start -->

## Design notes

Deltas on S-187's block; inherited decisions not re-derived. All of S-187a is test-harness code in `migration-bundle/src/parity/` — **zero touch to `alpenflight/server/`, the V2 schema, or any mapper destination.**

- **AC reconciliation (operator-decided this refinement; AC text to be tidied by decompose).** (1) "Read `migration_run.warnings`" → the in-process `ProducerHarness` warning collection; there is **no `t_migration_run` row** in this harness — `migration_run.warnings` is only the S-141 production analog. (2) `ARTICLE_DUPLICATE_NUMBER` is **not** a row-count drop — it is a `@Tag("parity-reject")` whole-bundle reject (Swiss OR Art. 957a; silent dedupe would rewrite legal records). Only `AIRCRAFT_NO_MANAGING_CLUB` + `RESERVATION_NO_PILOT` fold into row counts. (3) Cross-module test-fixture sharing (deferred here by S-141b) stays **out of scope** — file a follow-up if the server IT ever needs the seeder.
- **25 binding entries.** Each is a `MapperLegacyBindings.Binding` keyed off the mapper's own javadoc, which already pins the producer SELECT shape. The non-trivial ones alias a **producer-computed column into the cursor** so the mapper reads it verbatim: Aircraft `ManagingClubId` (cascade `OwnerClubId → single PersonClub of AircraftOwnerPersonId`; no match → omit row + warn), Location `(legacy Location × referencing Club)` fan-out join, Reservation pilot-club resolution. Cascade/fan-out is a JOIN+filter in the SELECT, not Java row logic. SYSTEM_GLOBAL reference entities get empty INSERT + a `legacy_id_map` populator join like the existing COUNTRY/LANGUAGE/CLUB_STATE entries.
- **Producer-drop reconciliation (in-process).** `ProducerHarness.produceTarGz` returns a `List<MigrationRunWarning>` alongside the bytes; `ParityDiffEngine.run(…, warnings)` subtracts `count(warnings ∈ {AIRCRAFT_NO_MANAGING_CLUB, RESERVATION_NO_PILOT} grouped by (clubId, entityType))` from the legacy side before the per-(Club, table) equality.
- **Two-pass UPDATE.** A second `ConsumerHarness` phase after `ingest()` commits: for FLIGHT.`tow_flight_id` + PERSON_CATEGORY.`parent_person_category_id` (self-FKs deliberately absent from `foreignKeys()`), pass-1 inserted NULL; pass-2 `UPDATE … SET <self_fk> = (resolve legacy parent GUID via the in-memory sub-map)`. **Self-FK to a tombstoned/dropped parent (no map entry) → leave NULL, keep the child row** (operator decision; the column is nullable, parity preserves the legacy child). Splitting ingest into explicit pass-1/pass-2 phases is a structural change to the current single-loop `ConsumerHarness`; the FK orphan walk asserts 0 only after pass-2.
- **Composite `legacy_id_map_location`.** Special-case FK walker keyed on `(legacy_guid, club_id)`: Flight.{start,ldg}_location_id via `(legacy_guid, operating_club_id)`; Aircraft.homebase_id via `(legacy_guid, managing_club_id)` with **deterministic lowest-UUID-v7 fallback** (ADR 0019) when no replica matches the managing club. Assertion: `count(legacy_id_map_location) == Σ_legacyLocation |referencing-Club set|` (sum the referencing set — a zero-club Location contributes 0).
- **FK orphan walk — no Hibernate (CLAUDE.md).** FK-target source is `Mapper.foreignKeys()`, **not** an `information_schema` walk: it is the authoritative declared set the ArchUnit ingest-order rule already trusts, and it carries the self-FK / cross-tenant exclusions the harness resolves through the bundle sub-map. Per FULL_PORT entity × declared FK: `SELECT count(*)` of child rows whose FK has no parent PK (cross-tenant FKs join through the Person sub-map). `summary.json.fkOrphans` flips from JSON `null` (S-187) to the measured integer; happy round-trip asserts `== 0`.
- **`@Tag` taxonomy.** `parity` = happy round-trip + coverage gates + sampled diff + soft-delete + tenant/PII invariants. `parity-reject` = negative-path bundle-rejects (incl. `ARTICLE_DUPLICATE_NUMBER`), each asserting error code + Postgres rollback. `parity-meta` = mutation-smoke self-test. All three excluded from `./gradlew test`.
- **Coverage gates run pre-diff** off the registries the bundle already exposes (`KNOWN_MAPPERS`, `Manifest.entityPolicies`, `UnmappedTables.REGISTRY`, `permittedSparseEnumValues`) — in-memory set arithmetic, no new infra; miss → `seed gap: <EntityType>[@<ClubId>]`.
- **ADR 0022 D2 deviation: none.** Test harness only — no CHECK/trigger/generated column, no schema or mapper-destination change.

## Edge cases & hidden requirements

S-187/S-183 already cover `datetime2(7)`→`timestamptz` µs-normalisation, monetary `numeric` exactness, `TABLESAMPLE REPEATABLE` determinism, cross-tenant FK scope, orphan-actor dedupe, absent-vs-zero-rows, `schemaVersion` reject codes — not repeated. New/sharper at full scale:

- **Gate ordering vs soft-delete.** The pre-diff seed-gap gate must fire (and short-circuit with `seed gap: …`) **before** the soft-delete invariant — soft-delete's per-Club ratio is undefined on an under-seeded Club.
- **Composite-location count is a sum, not a distinct-count.** A Location referenced by N Clubs yields N `legacy_id_map_location` rows; a zero-referencing Location contributes 0. Assert `Σ |referencing-set|`, not `count(distinct location)`.
- **Lowest-UUID-v7 fallback must be seed-stable across the nightly 10× re-seed.** If `parity.seed` reuse doesn't pin Location UUID-v7 minting order, the "lowest" winner flips run-to-run. Assert the fallback selection is seed-stable, not merely present.
- **Sparse-enum gate vs no-legal-seed-shape.** A `permittedSparseEnumValues` value whose FK prerequisites are unsatisfiable makes the gate permanently unsatisfiable — emit `no legal seed shape for <enum>=<value>`, distinct from `seed gap`.
- **FlightAirState `AirStateId=5` row required, but the destination is DROPPED (V13, computed not stored).** Parity asserts a **producer-side translation** (`AirStateId==FlightPlanOpen` → `t_flight.flight_plan_opened_on` non-null); all other air-state source rows produce no air-state delta. The value drop is **not** a row drop — per-(Club, table) counts still match.
- **Negative-path rollback asserts a *delta*, not an absolute count.** V2 pre-seeds SYSTEM_GLOBAL reference rows, so "zero new rows after reject" = snapshot per-table counts pre-ingest and assert delta 0 per reject code.
- **Mutation-smoke polarity.** The decorator must drop a **non-`@ParityIgnore`** column (→ `passed=false`); add an inverse case where dropping a `@ParityIgnore` column keeps `passed=true`, else the self-test asserts the wrong invariant.

## Security plan

Inherits the S-183 block (bulk-ingest tenant validation, Person sub-map isolation, orphan-actor bundle-local UUIDs, `@AuditRedact` warnings, no-plaintext-bytes ArchUnit, ingest-authz-in-S-141). Synthetic-Faker-only harness, no HTTP surface, no real PII — the surface is the reports it writes to disk. Deltas:

- **PII allow-list is fail-CLOSED (load-bearing polarity).** The diff-row emitter must **default-deny**: redact any column to name + `[redacted]` unless it is on an explicit *cleared* (non-PII) set. A deny-list of *PII* columns is the wrong polarity — a new mapper's PII column with no entry would leak. The existing `reportsDoNotLeakSeededPii` smoke only catches *seeded* values; the fail-closed default is what actually holds the invariant against an unseeded real-PII column.
- **PII set source.** The bundle module has no Spring dep (CLAUDE.md), so the emitter **cannot** read server-side `AuditRedactionProperties` / `tenant-rules.yaml pii_columns` at test time. It owns a hardcoded in-bundle set, annotated as the deliberate mirror of `tenant-rules.yaml` (the authoritative copy). Drift guard is deferred to a server-side test (S-024/S-027 have both on classpath): assert the bundle emitter's PII set ⊇ every `pii_columns` entry whose table maps to a `KNOWN_MAPPER`.
- **Tenant-isolation invariant references the canonical constant.** Assert `seeded-manifest bypass-bearing entities ⊆ Manifest.TENANT_BYPASS_ALLOW_LIST` (the 11; canonical in `Manifest`) — never re-list the 11 in the test. The 11 *entity types* are broader than `tenant-rules.yaml`'s 5 cross-tenant *target* rows (it counts every cross-tenant FK-holder), so assert against the constant, not the YAML. The empty-bypass arm is subsumed by `fkOrphans == 0`; no new query.

## Test plan

The ACs enumerate *what* to assert; this is *how* to organise it.

- **Tag wiring.** Three `Test` tasks over the one `parity` source set: `parityTest` (existing) excludes `parity-meta`/`parity-reject`; add `parityRejectTest` (`includeTags("parity-reject")`) + `parityMetaTest` (`includeTags("parity-meta")`). All PR-gated (reject cases abort at/before COPY; meta is one mutated round-trip). Nightly `main` runs all three at `-Dparity.scale=10`. The `parity-meta` tag's only runner is `parityMetaTest`, so the mutation decorator can never enter the real sweep even if a class is mis-annotated — isolation by task filter, not convention.
- **One `@BeforeAll`, many readers.** MSSQL per-class reuse + the 62-script FLSTest bootstrap + one seed + one `produceTarGz` + one `ingest` happen **once in `@BeforeAll`**; every happy `@Test` (gates, FK walk, location parity, soft-delete, sampled diff, tenant/PII) is a **read-only assertion over the cached `summary.json`/`diffOutcome`/fixture**. This is the only way to fit ≤ 5 min — no happy `@Test` may re-ingest or mutate Postgres. The S-183 "per-method truncate" collapses to **per-class** truncate-then-ingest-once.
- **Reject cases get their own class.** Each `parity-reject` case runs its own bundle against a privately-reset Postgres in its own `@Test` (separate class, reuses the MSSQL container), then asserts the per-table delta is 0 post-rollback. Keeps them off the happy class's shared state.
- **Coverage gates are guard tests over the fixture**, asserted before the diff (`@BeforeAll`-time or `@Order(0)`), so a green diff over an under-seeded fixture is impossible.
- **Mutation-smoke** writes to a distinct run-id directory so it never overwrites the happy `summary.json` the PII smoke reads.
- **Risks.** (1) MSSQL cold-start + 62-script bootstrap is the dominant walltime + flakiness term — `batchesApplied > 0` assertion already fails loud on a partial bootstrap (else it reads as phantom seed gaps). (2) `TABLESAMPLE … REPEATABLE` must use the same seed both sides. (3) Nightly 10× amplifies any datafaker / UUID-v7 tie-break non-determinism — pin the seed and assert the deterministic-fallback path in the location test.

## Performance plan

Operative SLO is harness **walltime**, not latency — production throughput/alloc/COPY/N+1/WAL belong to S-188, not re-derived here.

- **Fixed costs dominate; optimise the bootstrap, not the mappers.** At scale 1 the 28-mapper round-trip + diff is cheap (small per-Club counts). The dominators are MSSQL cold start (~30–60 s) + the 62-script FLSTest bootstrap (serial DDL, GO-batch by GO-batch over one connection, no parallelism — ~30–90 s). At the 90 s end, cold-start + bootstrap alone is ~3 min before a single mapper runs.
- **Full 28-mapper sweep on the PR gate is forced** by the coverage-gate AC (`≥ 1 seeded row per KNOWN_MAPPER per Club`) — a reduced PR subset would violate it. So: keep the full sweep, instrument `summary.json.timings{containerStartMs, schemaBootstrapMs, roundTripMs, diffMs}`, and size the bootstrap first. **Mitigation lever if apply-every-run exceeds budget: a schema-applied MSSQL image** (`docker commit` / CI layer keyed on `flsserver/database/FLSTest/**` hash) so per-run cost is container-start only. Defer the image if apply-every-run measures under ~2 min.
- **TABLESAMPLE BERNOULLI(1) samples ~0 rows at PR scale** (1% of a few hundred rounds to zero); `REPEATABLE` fixes determinism, not sample size. Floor it: full-scan sentinel comparison at PR scale (cheap), reserve real BERNOULLI(1) for nightly 10× where 1% is meaningful, seed pinned.
- **Nightly 10×.** Linear in `parity.scale`: row counts, COPY volume, the O(rows) FK-orphan + composite-location walks, sample size. Fixed: container start + bootstrap (DDL, not data). 10× of cheap rows stays well under 30 min — confirm by asserting `timings.total` against the ceiling in the nightly job.

<!-- modernize-refine: end -->
