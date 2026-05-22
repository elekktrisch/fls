---
id: S-050
title: Aircraft CRUD (+ aircraft types/states)
epic: E-06
status: done
started_at: 2026-05-22
done_at: 2026-05-22
github_issue: 98
github_pr: 99
depends_on: [S-049, S-047, S-026, S-022]
acceptance:
  - `Aircraft`, `AircraftType`, `AircraftState`, `AircraftAircraftState`, `AircraftOperatingCounter` ported.
  - Aircraft is **cross-tenant** — no `@TenantId`; per-flight tenancy lives on `Flight.operating_club_id` (S-058). Mutation authz is SYSTEM_ADMIN + CLUB_ADMIN-of-owner-club via the `AircraftAccess` SpEL bean.
  - The "Add aircraft" modal pattern works on the new SPA.
  - The aircraft → flight-type filter dropdowns (GLIDER / TOWING / MOTOR) work end-to-end (server-side filter via `?type=` query param preserves legacy membership).
  - Parity spec `e2e/tests/masterdata/aircrafts-crud.spec.ts` (legacy oracle) + new-stack spec `alpenflight/web/e2e/tests/masterdata/aircraft-crud.spec.ts` pass.
estimate: M
adr_refs: [0005, 0008, 0018, 0019, 0022, 0023]
parity_test: e2e/tests/masterdata/aircrafts-crud.spec.ts
refined: true
refined_at: 2026-05-22
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context

Aircraft is referenced by Flight, Reservation, PlanningDay — most of the downstream feature graph depends on this story. **Cross-tenant** per `alpenflight/database/tenant-rules.yaml:275-313`: a single airframe rides across operating clubs (charter / loan / private ownership), `owner_club_id` is nullable, no `@TenantId`. Authz lives in `AircraftAccess` (SpEL bean) at the service layer.

`Aircraft.immatriculation` is a filter key in the accounting rules engine (R3); the V3 partial-unique index `ux_aircraft_immatriculation` (WHERE `deleted_on IS NULL`) serves both the regulator-uniqueness constraint and the R3 lookup.

## Cross-story contracts

**Consumes:** S-049/S-049b Locations (`homebase_id`), S-047 reference data (`AircraftType`, `AircraftState`, `CounterUnitType` — the last one shipped here as a boyscout, since S-047 stopped short of it), S-022 tenant resolver (writes bypass `@TenantId`; reads use it for cross-club visibility), S-026 roles (SYSTEM_ADMINISTRATOR / CLUB_ADMINISTRATOR / FLIGHT_OPS).

**Produces:**
- `GET / POST / PUT / DELETE /api/v1/aircraft` (CRUD; list supports `?type=GLIDER|MOTOR|TOWING`).
- `GET /api/v1/aircraft/picker` — slim projection for Flight / Reservation pickers (S-058, S-068).
- `POST /api/v1/aircraft/{id}/states` + `GET .../states` — state change + history.
- `POST .../counters` + `GET .../counters` — counter record + history.
- `POST .../transfer-ownership` — SYSTEM_ADMIN-only (A04 mass-assignment defense — `owner_club_id` / `aircraft_owner_person_id` deliberately absent from the update DTO).
- `GET /api/v1/aircraft-types`, `GET /api/v1/aircraft-states`, `GET /api/v1/counter-unit-types` — read-only catalogs.

**Downstream consumers:** S-058 Flight, S-068 Reservation, S-070 PlanningDay.

## Open design answers

- **AC #2 rewritten inline (was: "Aircraft is `@TenantId`'d (per-club)").** Resolved per the design-notes reshape; the new AC matches the shipped tenant-rules + `AircraftAccess` bean. No separate decompose pass needed.
- **Charter / public-rental authz check (`tenant-rules.yaml:288` "may this club use this aircraft?")** — deferred to S-068 Reservation + S-058 Flight, which own the use-side semantics.

## Parity exclusions

- **Counter-edit / counter-delete admin UI** — deferred. Counter history is append-only by design; corrections are a follow-up.
- **State-history admin UI** — only "current state" surfaced on the edit form. Full state-history grid + filter-by-state-at-date is a follow-up.
- **AircraftType + AircraftState admin CRUD** — Flyway-seeded read-only dropdowns only (parallels S-049's LocationType deferral).
- **Legacy URL shape** (`/api/v1/aircrafts/listitems/gliders`, `OwnershipType`, `RecordState`, `{Items: [...]}` envelope, `X-HTTP-Method-Override`) — intentionally not preserved (ADR 0022); new spec asserts observable behavior only.
- **Immatriculation dash-stripping for duplicate-check (new-stack divergence).** Legacy `AircraftService.cs:378` compared `Immatriculation.Replace("-","").ToUpper()`, so `HB-ABC` and `HBABC` were the same row for uniqueness purposes. New stack stores user-entered casing (via the VO uppercase-normalize) but does NOT strip dashes for the uniqueness check — `HB-ABC` and `HBABC` are distinct. Regulator convention preserves dashes; cutover (S-016) will collapse any historical dash-variants if they exist.
- **Counter monotonicity is a new domain invariant.** Legacy had no monotonicity check on counter totals; new stack rejects non-monotonic + duplicate `at_date_time` via typed exceptions → 409. Corrections of bad historical entries will use the deferred counter-edit admin UI.
- **`immatriculation` visibility for non-owning-club readers.** Security plan originally listed it among sensitive-not-PII owner-only fields, but it ships on the list projection (regulator-public identifier) and the detail mapper keeps it visible — the security review + IT (`nonOwningClubReader_doesNotSeeOwnerOnlyFields`) ratify the wider exposure. The owner-only set is `comment` + `flarmId` + `mtom` + `noiseClass` + `noiseLevel` + `spotLink`.
- **`/aircraft/{id}/transfer-ownership` does not cascade to historical flights.** Cutover's `flight.operating_club_id` snapshot is correct at the time of the flight; retroactive re-attribution after a club merger is out of scope (S-016 follow-up).

## Pending boyscout follow-ups (cross-cutting, deferred to the next story to touch them)

- Extract `<af-checkbox>` atom + `<af-row-actions>` molecule + `<af-textarea>` atom — duplication crosses three features now (clubs / locations / aircraft).
- Replace `window.confirm` for destructive delete with ng-zorro `NzModalService.confirm` or `<af-dialog>` — same three features.
- `Aircraft.changeState` rename to `changeStateInMemory` + visibility-narrowing — flagged but kept for unit-test convenience.
- Move the `close → flush → open` dance from `AircraftsService.changeAircraftState` into a single `JpaAircraftRepository.persistStateChange(...)` infra method — keeps JPA semantics out of the domain port.
- `RegisterAircraftCommand` / `UpdateMasterdataCommand` records to replace the 22-arg AR factory + updater positional parameter lists.
- `CounterUnitTypeId` typed-id (parallel to `AircraftTypeId`) + retrofit the two `Aircraft` FK columns + DTOs.
- Format the `since {validFromDisplay}` / `recorded {atDateTime}` UI strings via `Intl.DateTimeFormat` instead of raw ISO timestamps.
