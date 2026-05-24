---
id: S-058
title: Flight entity + FlightAircraftType discriminator
epic: E-07
status: done
started_at: 2026-05-24
done_at: 2026-05-24
depends_on: [S-013, S-050, S-051, S-053]
acceptance:
  - `Flight` JPA entity covers all columns from the legacy `Flight` table (with reshape per S-013).
  - `FlightAircraftType` enum: GliderFlight, TowFlight, MotorFlight — modeled as a JPA `@Enumerated` smallint discriminator.
  - The single-entity model is preserved (no separate GliderFlight/TowFlight/MotorFlight classes — discrimination in code, not in schema).
  - Repository finder methods: `findByOperatingClub(...)` (auto-filtered by `@TenantId`), `findByTowFlightId(...)`, `findByProcessState(...)`.
  - Basic CRUD endpoints (no state-machine logic yet — that's S-059).
estimate: M
adr_refs: [0005, 0008, 0018, 0019, 0022, 0023]
parity_test: none
refined: true
refined_at: 2026-05-24
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
github_issue: 112
github_pr: 113
---

## Context

Sacred-cow shape — single Flight entity for glider / tow / motor, discriminated by `FlightAircraftType` (sparse smallint mapping `{GLIDER=1, TOW=2, MOTOR=4}`). Don't split into multiple tables. Crew lives in `FlightCrew` (M:N to Person, aggregate-internal, replaced wholesale on PUT).

## Load-bearing decisions

- **Aircraft cross-tenant pivot (mid-implementation).** The S-058 grilling pass surfaced the charter case (small glider clubs flying tow planes owned by other clubs, or by external owners not in the system). S-159 had structurally closed that case by tenant-scoping Aircraft; S-058 reverts and Aircraft becomes cross-tenant again. `Flight.aircraft_id` may reference any active aircraft regardless of `managing_club_id`. Mutation gating moves to the `AircraftAccess` SpEL bean. See [ADR 0008 amendment 2026-05-24](../adrs/0008-multi-tenancy-mechanism.md).
- **`aircraftId` is mutable post-create at S-058 scope.** The discriminator (`flightAircraftType`) is immutable; `aircraftId` is not — but state-machine gating (block repointing once a Flight is booked / invoiced) is S-059's job.
- **No `SYSTEM_ADMINISTRATOR` gate on tenant-scoped Flight endpoints** per S-159 amendment of ADR 0008.

## Out of scope (S-059 owns)

State-machine transitions (process / air state), validator, `validatedOn` / `deliveryCreatedOn` / `flightReportSentOn` derivation, `isSoloFlight` server-derive, FlightType×FlightAircraftType compatibility, coupon-number enforcement, the range / calculation invariants previously held by the 14 V3 CHECKs (kept stripped per ADR 0022 directive 2), and the state-conditional immutability gate on `aircraftId`.

## Follow-ups filed

- [S-161](S-161-cross-club-aircraft-usage-visibility.md) — cross-club aircraft usage visibility for the managing club's books.
- [S-162](S-162-sysadmin-aircraft-register-endpoint.md) — sysadmin-driven `POST /api/v1/admin/aircraft` variant.
- [S-163](S-163-person-owner-aircraft-edit-predicate.md) — extend `AircraftAccess.canEdit` to the owning Person once S-052 wires User → Person.
- [S-164](S-164-aircraft-detail-counter-redaction.md) — redact `latestCounter` from Aircraft detail GET for non-manager callers.
