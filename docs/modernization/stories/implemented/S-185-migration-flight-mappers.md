---
id: S-185
title: Migration-bundle — flight sub-package mappers
epic: E-02
status: done
started_at: 2026-05-29
done_at: 2026-05-29
depends_on: [S-183, S-184]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: true
refined_at: 2026-05-29
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
github_issue: 171
github_pr: 172
merged: true
merged_at: 2026-05-29
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

<!-- modernize-refine: start -->

## Producer / S-141 hand-offs

Bundle-shape contracts the consuming code can't enforce by inspection of the mapper alone.

- **Producer-side `Location` fan-out** (S-139). Legacy `Locations` has no per-club ownership; V7 requires `t_location.club_id NOT NULL`. Producer emits one bundle row per `(legacy Location, referencing Club)` pair (referencing-Club set = `Flights.{StartLocationId, LdgLocationId}` ∪ `Clubs.HomebaseId` ∪ `Aircrafts.HomebaseId` joined through the Aircraft's computed `managing_club_id`), with a fresh UUID v7 per replica. The Aircraft union step adds the managing-club replica explicitly when no flight references the homebase.
- **`legacy_id_map_location` composite key** (S-141 temp-table change). `(legacy_guid uuid, club_id uuid)` not single-column. Per-bundle Location row-count multiplier is a parity-oracle equivalence class (NOT row-equal by design — surface in S-187). FK resolution: `Flight.{start_location_id, ldg_location_id}` picks the replica matching `operating_club_id`; `Aircraft.homebase_id` matches `managing_club_id` with deterministic lowest-UUID fallback.
- **Producer-side `Aircraft.managing_club_id` cascade** (S-139). (1) legacy `OwnerClubId` if NOT NULL; (2) else if `AircraftOwnerPersonId` set AND that Person has exactly one `PersonClub` row in the bundle, use that ClubId; (3) else drop the row + emit `migration_run.warnings.AIRCRAFT_NO_MANAGING_CLUB` with the legacy `AircraftId`. V10's seed-club-1 backfill default is for fresh deployments only — never used on the migration path.
- **`Aircraft` ownership exclusivity** (`owner_club_id` XOR `aircraft_owner_person_id`) — service-layer invariant per V3 schema comment. Producer strips violations; mapper trusts the producer.
- **`FlightType` seed collision** (S-141). `ReferenceDataSeeder` runs first; bundle ingest second. On `(operating_club_id, flight_type_name)` collision, the seeded UUID wins and the legacy `Flight.FlightTypeId` FK resolves via natural-key lookup.
- **Self-FK `Flight.tow_flight_id`** (S-141). NOT in `foreignKeys()`; S-141 two-pass UPDATE after the FLIGHT pass (PersonCategory precedent — S-184). Soft-deleted tow ref preserves the chain in both rows tombstoned — forensic invariant pinned by `FlightMapperTest.towFlightIdPreservedAcrossSoftDeleteToggle`.

## Parity exclusions

- **Per-Club Location row count** is NOT exact between legacy and new — fan-out multiplies. S-187 oracle must scope row-count equality per `(Club, table)` excluding Location, and assert FK integrity via the replica-selection rule above.
- **`Flight.flight_plan_opened_on`** is lossy for flights that transitioned through `AirStateId=5` and onward (current state ≠ 5 → timestamp dropped). Accepted per S-060 (computed-not-stored).
- **Cross-bundle Aircraft dedupe** is out of scope — `legacy_id_map_aircraft` is bundle-local (`ON COMMIT DROP` per S-183). Future multi-bundle merge via S-051.

## Downstream hand-offs (other stories)

- **S-186** owns the `@AuditRedact` coverage gate for the free-text PII columns (`Flight.{comment, incident_comment, validation_errors, outbound_route, inbound_route}`, `Aircraft.comment`, `AircraftAircraftState.remarks`, `Location.description`).
- **S-187** consumes the flight mappers + the `@ParitySentinel` on `Flight.flight_plan_opened_on` (LegacyFixtureSeeder must seed at least one `AirStateId=5` row per Club).
- **S-188** owns the JMH bench against `FlightCrewMapper.readEntity` (budget: ≥ 200K rows/sec single-thread, ≤ 50 MB/s allocation). Allocation discipline carries from S-183's `Mapper` contract — no per-row allocation beyond Jackson + JDBC inherent. No second bench: Flight (~40 cols, 5M rows) + Aircraft (~30 cols) trivially clear the budget if FlightCrew does.
- **S-141 bundle-size flag.** 25M FlightCrew × ~200 B NDJSON ≈ 5 GB pre-gzip vs S-141's 2 GB body cap; gzip on the UUID+timestamp shape typically yields 4-6× → ~1 GB. S-141 implementation validates against the customer-scale fixture before cutover.

<!-- modernize-refine: end -->
