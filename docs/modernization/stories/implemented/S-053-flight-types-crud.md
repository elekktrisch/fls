---
id: S-053
title: Flight types + flight cost balance types CRUD
epic: E-06
status: done
started_at: 2026-05-24
done_at: 2026-05-24
depends_on: [S-050]
acceptance:
  - `FlightType` (tenant-scoped per V3 `operating_club_id`) ported with full field set including `is_for_glider/tow/motor` flags + `is_check_flight`/`is_passenger_flight`/`is_solo_flight`/`is_flight_cost_balance_selectable`/`is_for_aircraft_reservation_type` + role booleans + `min_nr_of_aircraft_seats_required`.
  - `FlightCostBalanceType` (system-global reference per V3 — no tenant column) ported with `code` + `is_for_glider/tow/motor` flags + `person_for_invoice_required`. Aggregate enforces the V3-documented at-least-one-flag invariant (was the dropped `ck_fcbt_at_least_one_flag` CHECK).
  - REST surface: `/api/v1/flight-types/**` CLUB_ADMINISTRATOR per S-159; `GET /api/v1/flight-cost-balance-types` open to any authenticated principal (S-047 reference pattern); sysadmin CRUD for FCBT at `/api/v1/admin/flight-cost-balance-types/**` (defer if no current consumer).
  - 404-not-403 on cross-tenant FlightType detail reads.
  - List/edit screens; flag-based filtering matches legacy UI.
  - New Playwright spec at `alpenflight/web/e2e/tests/masterdata/flight-types-crud.spec.ts` (greenfield — no legacy oracle spec).
estimate: S
adr_refs: [0005, 0008, 0022, 0023]
parity_test: alpenflight/web/e2e/tests/masterdata/flight-types-crud.spec.ts
parity_excluded:
  - Legacy `e2e/tests/masterdata/29-flight-type-crud.spec.ts` — doesn't exist in legacy; the new spec is the contract.
  - `FlightCostBalanceType.FlightCostBalanceTypeId int` PK — new stack uses UUID (V3 already created the table this way; `legacy_int_id SMALLINT` preserved for cutover lookup).
  - `min_nr_of_aircraft_seats_required` legacy `0`-treated-as-null ambiguity — new DTO rejects `0` at the boundary; `null` is the only "no constraint" wire form.
  - `FlightCostBalanceType.IsActive` legacy soft-deactivate flag — V3 schema dropped it; FCBT mutation is full CRUD with physical DELETE gated by FK RESTRICT from consumers (no `is_active` toggle ships).
  - Legacy `FlightCostBalanceTypeName` (max 100, user-display) + `Comment` (max 500, internal) columns collapsed into a single `description` (max 200) per V3. S-058 picker UI will bind to `description`; cutover importer concatenates the two if the operator wants a richer string.
  - `FlightType.isForAircraftReservationType` form-checkbox NOT surfaced in the S-053 edit UI (DTO field round-trips, defaults to false on create). S-068 AircraftReservation ships the user-facing toggle when the feature consumer arrives.
refined: true
refined_at: 2026-05-24
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer]
github_issue: 108
github_pr: 109
---

## Context

Two aggregate roots with **different tenancies** in one module
(`ch.alpenflight.flighttypes`): `FlightType` is tenant-scoped via Hibernate
`@TenantId` on `operating_club_id`; `FlightCostBalanceType` is system-global
reference data with no tenant column. The wrong-shape copy-paste trips the
S-024 leakage sweep and the `@TenantId` resolver, hence the explicit call-out
for the implementer of the next story that adds a similar dual-tenancy
module. Consumed by S-058 Flight (`flight.flight_type_id` + `flight.flight_cost_balance_type_id`)
and S-072 AccountingRuleFilter; pre-req for E-07 + E-09.

## Open decisions (carried for downstream stories)

- **FCBT sysadmin admin route deferred.** No current consumer demands it
  (S-058 / S-072 haven't shipped). When the first consumer needs to maintain
  the catalogue, mount as a **distinct** controller class on
  `/api/v1/admin/flight-cost-balance-types/**` with class-level
  `hasRole('SYSTEM_ADMINISTRATOR')` — never as a nested path under the
  read controller (path-confusion risk per the original threat model). The
  FCBT entity already carries `updateFlags(...)` with the at-least-one
  invariant; the future story adds a `register(...)` factory + DTOs.
- **FCBT FE store deferred.** The original design imagined an FCBT picker
  on the FlightType edit form gated by `isFlightCostBalanceSelectable`; on
  closer reading the picker belongs in the S-058 Flight edit form. The
  backend `GET /api/v1/flight-cost-balance-types` endpoint, DTOs, and
  generated TS client all ship now so S-058 can drop in the consumer.
