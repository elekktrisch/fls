---
id: S-185
title: Migration-bundle — flight sub-package mappers
epic: E-02
status: in_progress
started_at: 2026-05-29
depends_on: [S-183, S-184]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: true
refined_at: 2026-05-29
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
github_issue: 171
github_pr: 172
acceptance:
  - All `EntityType` members in `Group.FLIGHT` ship a concrete `Mapper` under `ch.alpenflight.migration.bundle.flight.*` — `Location`, `StartType`, `FlightType`, `Aircraft`, `AircraftAircraftState`, `AircraftOperatingCounter`, `Flight`, `FlightCrew`.
  - Each mapper implements bidirectional `writeNdjson` + `readEntity` per the contract.
  - `Manifest.TENANT_BYPASS_ALLOW_LIST` widens to add `FLIGHT`, `FLIGHT_CREW`, `AIRCRAFT`, `AIRCRAFT_AIRCRAFT_STATE` (cross-tenant Person + Aircraft refs per ADR 0008). Mapper `foreignKeys()`: `Flight.aircraft_id` (→ cross-tenant Aircraft); `FlightCrew.person_id` (→ cross-tenant Person); `Aircraft.aircraft_owner_person_id` + `Aircraft.homebase_id` (→ Person + tenant-scoped Location); `AircraftAircraftState.noticed_by_person_id` (→ Person). Every other flight-group entity declares empty `tenantBypassFks`.
  - `Location` mapper carries `club_id` in `columns()` per V7 tenant-scoping; producer-side fan-out (S-139) emits one row per `(legacy Location, referencing Club)` with fresh UUID v7 per replica; `legacy_id_map_location` becomes composite-keyed `(legacy_guid, club_id)`. Hand-off to S-141 + S-139.
  - `Aircraft` mapper resolves `managing_club_id` (NOT NULL per V10) producer-side via cascade: legacy `OwnerClubId` → single-`PersonClub`-of-`AircraftOwnerPersonId` in bundle → drop row + emit `AIRCRAFT_NO_MANAGING_CLUB` warning. Producer-side step lives in S-139; mapper's bind shape locks the contract.
  - `Flight` mapper translates legacy `AirStateId == FlightPlanOpen` → `t_flight.flight_plan_opened_on = Flight.ModifiedOn` (per V13 + ADR 0022 D2; lossy for flights that transitioned through `5` and onward — accepted per S-060). All other legacy air-state values are dropped.
  - `Flight.tow_flight_id` self-FK is NOT declared in `foreignKeys()`; S-141 two-pass UPDATE resolves it after the FLIGHT pass (mirrors `PersonCategoryMapper.parent_person_category_id` precedent — S-184).
  - `Flight.flight_aircraft_type_id` SMALLINT passes through verbatim (no mapper-side value-set guard) — sparse-enum (1,2,4) rejection lives on the Flight aggregate at S-058 per ADR 0022 D2.
  - `Aircraft.spot_link` mapper-side `^https://` reject at `readEntity` (`BUNDLE_AIRCRAFT_SPOT_LINK_NOT_HTTPS`) — producer-side hygiene before the V3 A10 SSRF CHECK fires.
  - Each mapper passes `AbstractMapperContractTest` + the base class gets two new hooks: `permittedSparseEnumValues(column)` (FlightMapper.flight_aircraft_type_id → {1,2,4}) + skip the self-FK ordinal check when `entityType() == target`.
estimate: M
adr_refs: [0002, 0003, 0008, 0019, 0022]
---

## Context

Scope-split from [S-183](S-183-migration-bundle-mappers-and-parity-oracle.md). Flight-group mappers including the highest-row entity (`FlightCrew`, ~25M rows at customer scale per ADR 0019). `FlightCrew` is the JMH-benched mapper (S-188) — its allocation discipline lands here.

## Cross-story contracts

- **Consumes:** S-183's scaffolding; S-184's `User` mapper for `FlightCrew.PilotPersonId` resolution via per-bundle Person sub-map.
- **Produces:** Flight-group `Mapper`s consumed by S-141 + S-187 + S-188.

## Notes

- `Flight.AirStateId` legacy → new translation per V13: only `FlightPlanOpen` carries; other states dropped (computed not stored per ADR 0022 D2).
- `FlightCrew` is the perf-critical mapper; readers should review with allocation-budget lens (per-row Jackson + JDBC inherent only).

<!-- modernize-refine: start -->

## Design notes

Deltas on top of [S-183](implemented/S-183-migration-bundle-mappers-and-parity-oracle.md) and [S-184](implemented/S-184-migration-identity-mappers.md). Load-bearing decisions there are inherited, not re-derived.

- **`Manifest.TENANT_BYPASS_ALLOW_LIST` widened.** Add `FLIGHT`, `FLIGHT_CREW`, `AIRCRAFT`, `AIRCRAFT_AIRCRAFT_STATE` to the allow-list at `Manifest.java:49-52`. Covers every cross-tenant FK in the flight group: `FLIGHT.aircraft_id` (→ cross-tenant Aircraft per ADR 0008 2026-05-24 amendment), `FLIGHT_CREW.person_id`, `AIRCRAFT.aircraft_owner_person_id`, `AIRCRAFT.homebase_id` (cross-tenant source → tenant-scoped Location target — bypass because the source crosses tenants), `AIRCRAFT_AIRCRAFT_STATE.noticed_by_person_id`. `LOCATION` does NOT join — V7 made it tenant-scoped via `club_id`, which IS the @TenantId discriminator, not a bypass. `START_TYPE` / `FLIGHT_TYPE` / `AIRCRAFT_OPERATING_COUNTER` declare empty bypass. Defense-in-depth gate retains its structural rejection for non-allow-listed entries.
- **AC3 stale wording superseded.** Story body's pre-refine AC3 ("Location.HomeClubId") reflected the pre-V7 cross-tenant Location design. Real contract is the new AC3 above + this block — Location is tenant-scoped via `club_id` (V7); Aircraft is cross-tenant via the S-058 amendment carrying `managing_club_id NOT NULL` as plain metadata.
- **V13 air-state translation in `FlightMapper`.** Mapper does NOT emit `air_state_id` (V13 dropped the column). Source for `flight_plan_opened_on`: legacy `Flight.ModifiedOn` when current `Flight.AirStateId == FlightPlanOpen (legacy_int_id=5)`; otherwise NULL. Lossy for flights that transitioned through and onward — accepted per S-060 (computed-not-stored design). `@ParitySentinel` on `flight_plan_opened_on` so S-187's `LegacyFixtureSeeder` seeds at least one `AirStateId=5` row per Club.
- **Producer-side `Location` fan-out.** Legacy `Locations` has no club ownership; V7 requires `club_id NOT NULL`. Producer (S-139) emits one bundle row per `(legacy Location, referencing Club)` pair with a fresh UUID v7 per replica. Referencing-Club set = `Flights.{StartLocationId, LdgLocationId}` ∪ `Clubs.HomebaseId` ∪ `Aircrafts.HomebaseId` joined by the Aircraft's computed `managing_club_id`. `legacy_id_map_location` becomes composite-keyed `(legacy_guid, club_id)`. FK resolution at S-141: `Flight.{start_location_id, ldg_location_id}` picks the replica whose `club_id == operating_club_id`; `Aircraft.homebase_id` picks the replica whose `club_id == managing_club_id` (the fan-out membership guarantees existence), with deterministic lowest-UUID fallback. Per-Club Location row-count multiplier IS a parity-oracle equivalence class (NOT row-equal by design — surface in S-187).
- **Producer-side `Aircraft.managing_club_id` cascade.** (1) legacy `OwnerClubId` if NOT NULL; (2) else if `AircraftOwnerPersonId` set AND that Person has exactly one `PersonClub` row in the bundle, use that ClubId; (3) else drop the row and emit `migration_run.warnings.AIRCRAFT_NO_MANAGING_CLUB` with the legacy AircraftId. V10 backfill default (seed-club-1) is for fresh deployments only — never used by the migration path.
- **Self-FK `Flight.tow_flight_id`** dropped from `foreignKeys()`. S-141 two-pass UPDATE remaps after the FLIGHT pass (precedent: `PersonCategoryMapper.parent_person_category_id` — S-184). Soft-deleted tow ref preserves both rows tombstoned (V3 schema FK is `ON DELETE SET NULL` but both rows port, just `deleted_on IS NOT NULL`).
- **`Flight.flight_aircraft_type_id` SMALLINT** sparse-enum (1,2,4): mapper passes through as `int` via `node.asInt()` + `setShort`/`setInt`. NO mapper-side value-set guard — rejection lives on the Flight aggregate at S-058 per ADR 0022 D2. Drift surfaces at S-141 ingest when Hibernate enum read-back fails.
- **`Aircraft.spot_link`** mapper-side `^https://` reject at `readEntity` with `BUNDLE_AIRCRAFT_SPOT_LINK_NOT_HTTPS`. The V3 `ck_aircraft_spot_link_https` CHECK is the schema invariant (ADR 0022 D2 A10 SSRF carve-out); the mapper guard fails-fast with a row-attributable error before the CHECK fires mid-COPY.
- **`Aircraft` ownership exclusivity** (`owner_club_id` XOR `aircraft_owner_person_id`) — service-layer invariant per V3 schema comment. Producer-side responsibility (S-139). Mapper passes through; trust the producer.
- **`FlightType` collision with seeded defaults.** ReferenceDataSeeder seeds 4 defaults per Club at trial-deployment provisioning (`ON CONFLICT (operating_club_id, flight_type_name) DO NOTHING`). S-141 runs provisioning first → bundle ingest second → seed wins on name collision; legacy `Flight.FlightTypeId` FK resolves to the seeded UUID via natural-key lookup. Hand off to S-141.
- **Reference-table FKs.** `StartType` + `AircraftState` + `FlightCrewType` + `FlightProcessState` + `FlightCostBalanceType` are SYSTEM_GLOBAL refs resolved via `legacy_int_id` against V3 seeds (same pattern as ClubState per S-184). StartType's legacy PK is INT identity, not UUID — bundle emits `(legacy_int_id, name)` for the mapping; the table itself is not row-ported.
- **Module layout.** One `Mapper` class per AC entity under `ch.alpenflight.migration.bundle.flight`; one `AbstractMapperContractTest<M>` subclass per under `flight/`. `package-info.java` mirrors `identity/package-info.java`.
- **`AbstractMapperContractTest` extension hooks.** Two new hooks: (a) `permittedSparseEnumValues(column)` returning `Set.of((short)1, 2, 4)` for `FlightMapper.flight_aircraft_type_id` so `Faker` draws from the set (no value-set assertion in mapper); (b) skip the existing `foreignKeyTargetsPrecedeSelfInIngestOrder` rule when `target == self.entityType()` (self-FK is structurally legitimate post-S-184). `legacyRow` for `FlightMapper` must produce at least one row with `AirStateId=5` so the V13 translation branch is exercised.
- **EntityType ordering.** Current declaration order is correct: `FLIGHT.aircraft_id → AIRCRAFT` (AIRCRAFT earlier ordinal); `FLIGHT_CREW.flight_id → FLIGHT` (FLIGHT earlier); `AIRCRAFT_*.aircraft_id → AIRCRAFT` (AIRCRAFT earlier). Self-FK `Flight → Flight` excluded from `foreignKeys()`. No reshuffle.
- **ADR 0022 D2 conformance.** No schema-level business logic introduced. Sparse-enum guard, counter/passenger ranges, runway/coupon formats, tow-self invariants — all defer to S-058 aggregates. Air-state translation is a producer-side data shape, not a schema invariant.

## Edge cases & hidden requirements

- **Bundle without any Flight rows for a Club** — `AircraftAircraftState` + `AircraftOperatingCounter` rows still emit (Aircraft is cross-tenant; aggregate-internal rows ride with it). Don't gate aggregate-internal emit on Flight presence.
- **Soft-deleted tow_flight** — legacy preserves the reference when tow is `IsDeleted=1`; both rows port with `deleted_on IS NOT NULL`; two-pass UPDATE binds the surviving UUID. No nullification at ingest — forensic-preserving.
- **`legacy_id_map_location` composite key shape** — `(legacy_guid uuid, club_id uuid)` not single-column. S-141 ingest temp-table DDL change; hand off via this story's design notes.
- **Aircraft fan-out edge case** — if an Aircraft's computed `managing_club_id` is NOT in the set of clubs whose Flights reference its `homebase_id`, the fan-out UNION step adds it explicitly so the Location replica exists. Otherwise FK resolution falls back to lowest-UUID replica.
- **`StartType` not row-ported** — legacy table has INT identity PK + name; new `t_start_type` is UUID-keyed and pre-seeded in V3 with `legacy_int_id` mapping. StartTypeMapper carries the `(legacy_int_id → name)` mapping only; no row-port.
- **`RecordState` legacy column dropped** — every flight-group mapper drops `RecordState` (legacy ASP.NET artifact) silently. One-line Javadoc on each mapper so the next reader doesn't re-derive.
- **`FlightCostBalanceType` legacy default `((1))`** — pass through whatever JDBC reads. NULL stays NULL; default-applied `1` stays `1`. No translation. Destination column is nullable; no parity ambiguity.
- **Cross-bundle Aircraft dedupe** — out of scope (Aircraft is cross-tenant but `legacy_id_map_aircraft` is bundle-local per S-183's `ON COMMIT DROP`). Future-multi-bundle merge via S-051 lookup. Javadoc warning on `AircraftMapper`.
- **Audit hand-off** — PII free-text columns (`Flight.{comment, incident_comment, validation_errors, outbound_route, inbound_route}`, `Aircraft.comment`, `AircraftAircraftState.remarks`, `Location.description`) port verbatim; S-186 owns the `@AuditRedact` coverage gate.
- **`MapperVsSchemaCompatibilityTest`** (in `alpenflight/server/`) — reflective over `KNOWN_MAPPERS`; should pick up flight mappers automatically. Verify on first green build.

## Security plan

- **`Manifest.TENANT_BYPASS_ALLOW_LIST` widening test.** Parameterised in `ManifestTest`: each newly allowed entity accepted; one rejection case (e.g. `FLIGHT_TYPE` declaring non-empty `tenantBypassFks`) must fail closed via the existing `validateTenantBypassAllowList` rule from S-184.
- **Cross-tenant Aircraft FK threat boundary.** Bundle = trust boundary. S-141's existing pre-COPY tenant validator (S-183 plan) walks every `ClubId` against the manifest set; bypass-listed FKs are exempt from the same-tenant gate but NOT from the in-bundle existence check. No new mechanism in S-185.
- **`spot_link` mapper-side https reject** (architect Decision 8 / security plan). Cheap one-regex check before the V3 CHECK fires; fails with a row-attributable error rather than an opaque mid-COPY constraint violation.
- **PII column preservation.** Mappers MUST NOT log row payloads (S-183 ArchUnit ban already covers `Files.*`; mappers in this story add no logging). Audit-redaction enforcement is S-186.
- **Tenant validation contract reminder.** No new mechanism. `Flight.operating_club_id` + `FlightType.operating_club_id` + `Location.club_id` are checked against the manifest's declared Club set by S-141 pre-COPY. Mapper-side contract test asserts emitted tenant column equals the seeded bundle Club.

## Test plan

- **Pyramid.** 8 new `AbstractMapperContractTest<M>` subclasses under `flight/`; +3 `FlightMapperTest` cases for the V13 air-state translation (`AirStateId=5` → non-null timestamp; other values → NULL; NULL → NULL); +1 `FlightMapperTest` assertion `foreignKeys()` does NOT contain `FLIGHT` (self-FK precedent); +1 parameterised `ManifestTest` for the widened allow-list (accept/reject pairs). `KNOWN_MAPPERS` registry extended; `knownMappersListCovers…` self-check (S-183) does the rest. Parity oracle round-trip → S-187. JMH → S-188.
- **Subclass convention.** Per S-183/S-184 pattern — `mapper()` + `legacyRow(Faker)` only, ~10-line stub. `FlightMapper.legacyRow` must produce at least one `AirStateId=5` row.
- **Sparse-enum pass-through.** One `FlightMapperTest` case pinning that legacy values {1,2,4,3,99} all pass through verbatim — guards against an implementer baking a reject into the mapper (would deviate from ADR 0022 D2).
- **`Aircraft.spot_link` mapper-side reject.** One `AircraftMapperTest` case: `readEntity` with `spot_link == "http://..."` throws `BUNDLE_AIRCRAFT_SPOT_LINK_NOT_HTTPS`; with `"https://..."` binds verbatim; NULL binds NULL.
- **ArchUnit additions.** None — S-183's four rules cover the flight package.
- **Cross-story hand-offs.** Manifest test lands here. `MapperVsSchemaCompatibilityTest` extension lives in `alpenflight/server/`; if reflective walk picks up `flight.*` automatically, no edit; otherwise one-line entity-loop entry. Self-FK two-pass UPDATE correctness → S-141.
- **Risks.** Implementer may re-introduce `FLIGHT` into `Flight.foreignKeys()` → contract base + `FlightMapperTest` self-FK assertion catches loudly. No flake risk: pure-Java mocked PS/RS.

## Performance plan

- **`FlightCrewMapper` (S-188 JMH target).** 11 columns: 2 UUID parses (`flight_id`, `pilot_person_id`) + `flight_crew_type_id` SMALLINT primitive + 4 timestamps + 4 audit cols. Allocation = Jackson + JDBC inherent only. No `String.format` / `.intern` / `.trim` / boxing. Must clear S-183's budget (≥ 200K rows/sec single-thread, ≤ 50 MB/s alloc) before S-188 wires the gate.
- **`FlightMapper` (~40 cols, ~5M rows).** Free-text trap: `comment`, `incident_comment`, `validation_errors`, `inbound_route`, `outbound_route` use `Coercions.readStringOrNull` (zero copy past Jackson). `flight_aircraft_type_id` SMALLINT: `node.asInt()` + `setShort` — never `setObject(Integer)`. 10× timestamps via `Coercions.readTimestampOrNull` (inherent cost; no shared buffer). 6× booleans via `node.get(F).asBoolean()` primitive — no `Boolean.valueOf`.
- **`AircraftMapper` (~30 cols).** Same shape as Flight; `comment` is the only free-text. Cross-tenant FK rewrites (`aircraft_owner_person_id`, `homebase_id`) resolve via S-183's per-bundle sub-map array query (single `WHERE legacy_guid = ANY(?::uuid[])` per 500-row batch).
- **No `Map<String,Object>` reuse across columns.** Tempting "optimization" for the 4× timestamp parses on FlightCrew or 10× on Flight — reject. Each `readTimestampOrNull` is independent; sharing a buffer adds a HashMap + boxed keys per row, net loss.
- **JMH baseline is row-count, not per-row-MB.** Wider rows (Flight 40 cols, Aircraft 30 cols) land below 200K rows/sec naturally — fine; contract is FlightCrew at 200K. No separate per-row-MB budget. No second bench (FlightMapper at 5M rows trivially clears budget if FlightCrew does).
- **Bundle-size flag for S-141.** 25M FlightCrew × ~200 B NDJSON ≈ 5 GB pre-gzip vs S-141's 2 GB body cap. Gzip on UUID+timestamp shape typically yields 4-6× → ~1 GB. Validate against customer-scale fixture before cutover; not an S-185 implementation concern.

<!-- modernize-refine: end -->
