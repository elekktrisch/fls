---
id: S-062a
title: Flight CRUD backend gaps + validator port
epic: E-07
status: done
started_at: 2026-05-25
done_at: 2026-05-25
depends_on: [S-058, S-059, S-060]
acceptance:
  - REST endpoints under `/api/v1/flights`: POST search, GET `{id}`, GET `new-template`, GET `{id}/copy-template`, POST, PUT, DELETE (full surface table in Design notes).
  - Glider + Tow paired-create lands two `Flight` rows in **one** `@Transactional` boundary; `tow_flight_id` linkage set; tow row inherits `operating_club_id` from parent (mirrors legacy `FlightService.cs:1249-1299`).
  - `FlightValidator` ports the `ValidateFlightBasics` rule set (`FlightService.cs:985-1136`) as a pure function over `Flight` returning `List<ValidationError>`. Validation step writes `Valid`/`Invalid` `processState` inline (parity with `:1041-1050`); every other transition routes through S-059.
  - `FlightCopyService` clones a `Flight` minus identity, times, comments, counters (parity with `FlightsController.js:232-255`); does not persist.
  - `FlightFactory.newFlightTemplate(...)` builds an initial-state Flight from caller's `myClub` defaults (port of `initForNewFlight`, `FlightsController.js:190-215`).
  - `If-Match` plumbed end-to-end on PUT (returns 412 on stale version once S-067 wires the `@Version` column).
  - PUT/DELETE reject when `processState == DeliveryBooked` (parity with `:1276-1280`, `:1308-1312`); additionally reject when `processState >= Locked` unless caller is `CLUB_ADMINISTRATOR` (closes legacy gap — see Security plan).
  - Cross-tenant flight access (`flightId` from another club) returns 404 (not 403); cross-tenant aircraft/flight-type/location FK returns 422; Person without `PersonClub` for caller's tenant returns 422.
  - DELETE on a glider with linked tow cascade-deletes the tow row in the same transaction (parity with `:1314-1319`); emits **two** audit events sharing `request_id`.
  - Integration tests (Testcontainers Postgres, `@WithTenant`) cover happy-path round-trips per type + tenant smoke; query-count assertions (list ≤ 3 SQL, detail ≤ 4 SQL).
  - All endpoints exercised via Swagger UI; no UI yet.
estimate: M
adr_refs: [0005, 0007, 0008]
parity_test: none (depth in S-101 / S-102 / S-105)
refined: true
refined_at: 2026-05-14
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
split_from: S-062
github_issue: 120
github_pr: 121
merged: true
merged_at: 2026-05-25
---

## Context

First of three sub-stories splitting the original S-062. S-058/S-059/S-060 shipped the CRUD + DTO + tenant-scoping + audit + state-transition surface; S-062a's implementation is the **gap-only scope** the operator confirmed at implement-entry — the 2026-05-14 refinement was intentionally left stale, with downstream consumers (S-062b list, S-062c forms) anchored to the shipped flat-DTO + GET-list shape.

## Load-bearing decisions

- **Pragmatic scope.** Refinement (2026-05-14) assumed reshapes (nested-DTO discriminator, GET-list → POST /search, dual-row paired-create) that conflict with what S-058/9/60 shipped. The reshape was rejected; the gaps were filled instead. ACs (frontmatter) describe the *originally-planned* surface — implementer reconciled at landing.
- **`FlightValidator` is a pure function over `Flight`.** Returns `List<ValidationError>`; never mutates state. The legacy "writes Valid/Invalid inline" step is the validation **job's** responsibility (S-083) and routes through the S-059 transition matrix — not the validator itself.
- **State-gates close the legacy gap.** PUT/DELETE reject `DELIVERY_BOOKED` → 409; reject `LOCKED` / `DELIVERY_PREPARED` / `DELIVERY_PREPARATION_ERROR` / `EXCLUDED_FROM_DELIVERY_PROCESS` → 403 for non-`CLUB_ADMINISTRATOR` callers. Legacy `UpdateFlightDetails` (`FlightService.cs:1276-1280`) blocked only `DELIVERY_BOOKED`, letting line-pilots silently edit Locked flights.
- **Tow cascade is application-layer.** No DB self-FK cascade by design; `FlightsService.softDeleteFlight` walks the tow row inside the same transaction and emits a per-row audit event.
- **If-Match → 412.** Pre-load comparison against `@Version` (from S-059). Concurrent in-flight modification (race within a transaction) continues to surface as 409 via `ObjectOptimisticLockingFailureException`. `FlightDetail` now carries `version` so the SPA can round-trip.
- **AC-DIR-1 last-context.** `GET /flights/last-context?aircraftId=&date=` returns the most-recently-saved flight's seed fields for the (aircraft, date) tuple, sorted by `created_on DESC` (column added to `Flight` for deterministic ordering). Times are deliberately omitted. Cross-tenant aircraftId → 404 via `@TenantId`.
- **`FlightCrewTypeIds` constants holder.** Single source of truth for the seven `flight_crew_type` seed UUIDs (validator + mapper + future consumers). Catches seed re-ID drift at compile time.

## Out of scope (deferred to other stories)

- **S-101** — validation rejection-path depth + route-allow-list rules (require resolved Location.InOutboundPoints hydration).
- **S-102** — illegal `FlightProcessState` transition coverage.
- **S-103** — time-gate boundaries (≥ 2d / ≥ 3d).
- **S-104** — permission matrix per endpoint × role.
- **S-105** — glider↔tow cascade depth + concurrent-edit tests.
- **S-024 + S-106** — cross-tenant isolation per endpoint.
- **S-062c** — UI-driven parity (specs 04 / 05).

## Out of scope (NOT deferred — left as known gaps; refinement called for these)

- **Cross-tenant aircraft / flight-type / location FK pre-validation.** Currently surfaces as 400 via `DataIntegrityViolationException` rather than the refinement's 422. Cleaner pre-check + targeted message is a follow-up (defer to S-101 with the validator-depth work).
- **PersonClub membership check for crew person FKs.** Person sacred-cow ride-through per ADR 0008 means the FK resolves cross-tenant; the per-tenant membership gate is not enforced at write time. Filing as a security follow-up against the validator-depth story.
- **Nested-by-discriminator DTO reshape.** S-058 ships flat `FlightDetail` with `FlightAircraftType` discriminator field; the refinement's nested `{ glider, tow, motor }` shape would require reshaping the entire DTO + breaking the OpenAPI contract just-after-shipping. Out of S-062a scope; revisit only if codegen ergonomics demand.
- **GET list → POST /search reshape.** Same reason as above — S-058 shipped keyset-cursor GET; FE consumers already wired to that shape.
- **Dual-row paired-create with nested tow payload.** Current model links glider to existing tow via `towFlightId` on update. Nested-create-with-tow is a future refactor.
