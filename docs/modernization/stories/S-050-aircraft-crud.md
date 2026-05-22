---
id: S-050
title: Aircraft CRUD (+ aircraft types/states)
epic: E-06
status: in_progress
started_at: 2026-05-22
github_issue: 98
github_pr: 99
depends_on: [S-049, S-047, S-026, S-022]
acceptance:
  - `Aircraft`, `AircraftType`, `AircraftState`, `AircraftAircraftState`, `AircraftOperatingCounter` ported.
  - Aircraft is `@TenantId`'d (per-club).
  - The "Add aircraft" modal pattern works on the new SPA.
  - The aircraft → flight-type filter dropdowns (glider/tow/motor) work end-to-end.
  - Spec `26-aircraft-crud.spec.ts` passes.
estimate: M
adr_refs: [0005, 0008, 0018, 0019, 0022, 0023]
parity_test: e2e/tests/masterdata/aircrafts-crud.spec.ts
refined: true
refined_at: 2026-05-22
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context
Aircraft is referenced by Flight, Reservation, PlanningDay — most of the downstream feature graph depends on this story.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entities + mappings.
- [ ] Controllers + DTOs.
- [ ] SPA stores + screens.
- [ ] Aircraft type discriminator wired correctly (drives flight-type filtering).
- [ ] Spec verification.

## Notes
`Aircraft.immatriculation` is also a filter key in the accounting rules engine (R3). Make sure it's queryable + indexed.

<!-- modernize-refine: start -->

## Design notes

**Tenancy reshape — overrides stale AC #2.** Per `alpenflight/database/tenant-rules.yaml:275-313` (reclassified 2026-05-16), Aircraft + AircraftAircraftState + AircraftOperatingCounter are **cross-tenant**: no `@TenantId`, `owner_club_id` nullable (charter / loan / private ownership). Sacred-cow parallel to Person. Flight queries by aircraft are not affected — Flight carries its own `operating_club_id` (tenant catalog §"Flights"). Captured in `## Assumptions made`; AC #2 should be rewritten by `/modernize-decompose` — see Open design questions.

**Authz model.** Hybrid, enforced at the service layer (`AircraftsService.assertCanMutate`), not Hibernate.
- Read (any `GET /aircraft*`): any authenticated principal — full-fleet visibility is intentional (charter/loan use cases). Non-owning-club readers see a DTO with `comment` omitted.
- Write (`POST` / `PUT` / soft-`DELETE`): `SYSTEM_ADMINISTRATOR` OR `CLUB_ADMINISTRATOR` of `aircraft.owner_club_id` (when non-null). `owner_club_id IS NULL` (charter): SYSTEM_ADMIN-only.
- State change (airworthiness): `FLIGHT_OPS` or above of `owner_club_id`; SYSTEM_ADMIN for null-owner. (Maintenance is an operational event, not an admin one.)
- Counter record: `FLIGHT_OPS` of any club — counters are airframe-lifetime, accumulate across operating clubs.
- Ownership transfer (`owner_club_id` change): dedicated `POST /aircraft/{id}/transfer-ownership`, SYSTEM_ADMIN-only. Field is **omitted from `AircraftUpdateDto`** (A04 mass-assignment defense).

**Module layout (per ADR 0023, four-package hexagonal-lite, mirroring `ch.alpenflight.locations`):**
- `ch.alpenflight.aircraft.domain` — `Aircraft` (AR), `AircraftAircraftState` + `AircraftOperatingCounter` (aggregate-internal entities, package-private setters), value-object IDs + measurement VOs (`AircraftId`, `Immatriculation`, `FlarmId`, `Mtom`, `SpotLink`), `AircraftRepository` interface, domain exceptions (`AircraftNotFoundException`, `AircraftStateConflictException`, `DuplicateImmatriculationException`).
- `ch.alpenflight.aircraft.application` — `AircraftsService`, request/response DTOs, mapper, `AircraftAccess` SpEL bean for `@PreAuthorize`.
- `ch.alpenflight.aircraft.web` — `AircraftsController` (read + write merged; method-level `@PreAuthorize` does the split), local `@RestControllerAdvice`.
- `ch.alpenflight.aircraft.infra` — `JpaAircraftRepository`, `@SQLDelete` for soft-delete.
- `ch.alpenflight.referencedata` — **extend** with `AircraftType` + `AircraftState` (read-only seeded; parallels `Country` / `LocationType`).

**Aggregate mutation verbs (ADR 0022 directive 2 — invariants on the AR, not the schema):**
`Aircraft.register(...)` (factory; opens initial state row); `aircraft.updateMasterdata(...)`; `aircraft.changeState(newStateId, validFrom, noticedByPersonId, remarks)` (closes the open state row with `validTo = newValidFrom`, opens new — invariant backed by `ux_aas_current_state_per_aircraft` partial unique); `aircraft.recordCounter(...)` (monotonic non-decreasing totals); `aircraft.setHomebase(locationId)`; `aircraft.transferOwnership(newOwnerClubId, newOwnerPersonId)`; `aircraft.softDelete(userId)`. **No new CHECK constraints**; `ck_aircraft_spot_link_https` already in V3 (A10 SSRF defense-in-depth) is the sole accepted schema-level deviation (already documented).

**Cross-story contracts.**
- *Consumes:* S-049/S-049b Locations (`homebase_id`), S-047 reference data (`AircraftType`, `AircraftState`, `CounterUnitType`), S-022 tenant resolver (writes bypass `@TenantId`; reads use it for `AircraftAccess.ownsAircraft`), S-026 roles.
- *Produces:* `/api/v1/aircraft` (CRUD), `/api/v1/aircraft/{id}/states` (POST = change, GET = history), `/api/v1/aircraft/{id}/counters` (POST = record, GET = history), `/api/v1/aircraft/{id}/transfer-ownership` (SYS_ADMIN), `/api/v1/aircraft/picker` (slim projection for FE pickers), `/api/v1/aircraft-types` + `/api/v1/aircraft-states` (read-only).
- Downstream consumers: S-058 Flight, S-068 Reservation, S-070 PlanningDay.

**Filter-slice strategy.** Single list endpoint `GET /api/v1/aircraft?type=GLIDER|TOWING|MOTOR&towingCapable=&flyable=`. `type` maps to `aircraft_type` boolean columns (GLIDER = `has_engine=false`; TOWING = `is_towing_aircraft=true`; MOTOR = `has_engine=true`). `flyable=true` joins current state row where `is_aircraft_flyable=true AND valid_to IS NULL`. `hasEngine` is a **DTO-derived field** in the application mapper (not a column on Aircraft). Collapses legacy `GetGliderAircraftListItems` / `GetTowingAircraftListItems` / `GetMotorAircraftListItems`.

**Cross-tenant homebase FK.** Aircraft (cross-tenant) → Location (tenant-scoped per S-049b). **Write**: writer's club must have the location visible via S-049b's visibility join; SYSTEM_ADMIN bypasses. **Read**: homebase returned regardless of visibility (hiding it from a sibling club is meaningless when the aircraft is shared).

**Soft-delete (parity with S-049).** `deleted_on` / `deleted_by_user_id`. `@SQLDelete` + `@Where(clause = "deleted_on IS NULL")`. State + counter history rows are not separately soft-deleted (FK has `ON DELETE CASCADE` for hard delete only); they're hidden by the AR's read path.

**Counter / state-history scope.** Ship state change + read; counter record + read. **No counter-edit / counter-delete admin UI** (counter history is append-only by design — corrections are a follow-up story). **No AircraftType admin UI** (read-only Flyway seed, parallels S-049's LocationType deferral).

**Parity test reconciliation.** Legacy spec path corrected in frontmatter (`aircrafts-crud.spec.ts`, not `26-aircraft-crud.spec.ts`). Legacy spec stays the parity oracle against legacy stack; a **new** Playwright spec `e2e/tests/masterdata-next/aircraft-crud.spec.ts` asserts the same observable behavior against the new stack (per ADR 0022 — semantics, not URL shape).

## Edge cases & hidden requirements

- **Immatriculation invariant — global unique, normalized for rules-engine lookup.** V3 ships `ux_aircraft_immatriculation` partial unique `WHERE deleted_on IS NULL` (global, not per-club — schema comment line 350 confirms regulator-convention). Legacy normalizes on read (`replace("-","").toUpperCase()`, `AircraftService.cs:378`) for R3 rules-engine matching. Store user-entered value; expose a `normalizedImmatriculation` getter computed via VO. R3 lookup uses the index directly on the raw column.
- **AircraftType discriminator — derive from `aircraft_type.has_engine` / `aircraft.is_towing_aircraft`, not from the legacy int.** Legacy `HasEngine = AircraftTypeId >= GliderWithMotor` was a bitmask-shaped hack; new code joins on the boolean columns already on `aircraft_type` (V3:132-134). Glider slice = `Type ∈ {Glider, GliderWithMotor}` (legacy `AircraftService.cs:303-304`); preserve membership.
- **State-history admin UI deferred.** S-050 ships "current state" on the edit form (legacy `AircraftDetails.AircraftStateData` parity); full state-history grid + filter-by-state-at-date is a follow-up.
- **Counter-unit-type dropdowns DO ship** (`flight_operating_counter_unit_type_id`, `engine_operating_counter_unit_type_id` on the Add Aircraft form — counters can't be recorded without the unit set first). Read-only seeded reference data from S-047.
- **Sale / club merger** (ownership change via the dedicated transfer endpoint) does NOT cascade to historical flights — flagged for S-016 (cutover's `flight.operating_club_id` snapshot is correct at the time of the flight, not retroactive).

Open questions:
- Rewrite AC #2 via `/modernize-decompose`: "Aircraft is cross-tenant — no `@TenantId`; per-flight tenancy lives on `Flight.operating_club_id`." Strongly recommended (the current AC contradicts the shipped schema + tenant rules and would cause a wrong implementation if read at face value).
- Charter-agreement / public-rental authz check (`tenant-rules.yaml:288` "may this club use this aircraft?") — deferred? Or scoped here as a stub interface? Lean: deferred — it touches Reservation + Flight which haven't shipped.

## Security plan

**Tenancy posture.** Aircraft is cross-tenant; authz lives in `AircraftsService.assertCanMutate`, not Hibernate. AircraftAircraftState + AircraftOperatingCounter inherit via the parent AR.

**Per-endpoint authz.**
- `GET /aircraft*` — any authenticated principal. DTO omits `comment` for non-owning-club readers; field is owner-club + SYSTEM_ADMIN gated.
- `POST /aircraft` — SYSTEM_ADMIN, OR CLUB_ADMIN AND `payload.owner_club_id == principal.clubId`.
- `PUT /aircraft/{id}` + soft-`DELETE` — SYSTEM_ADMIN, OR CLUB_ADMIN of `aircraft.owner_club_id` (non-null). Null-owner: SYSTEM_ADMIN-only.
- `POST .../states` — FLIGHT_OPS or above of `owner_club_id`; SYSTEM_ADMIN for null-owner.
- `POST .../counters` — FLIGHT_OPS of any club (airframe-lifetime, cross-club).
- `POST .../transfer-ownership` — SYSTEM_ADMIN-only; `owner_club_id` + `aircraft_owner_person_id` excluded from `AircraftUpdateDto`.

**Input validation (server-side only).** `immatriculation` `^[A-Z0-9-]{2,15}$` (regulator-shape; bind upper-case). `flarm_id` `^[A-F0-9]{6}$` (ICAO 24-bit hex). `competition_sign` `^[A-Z0-9]{1,5}$`. `spot_link` `https://`-prefix (defense-in-depth alongside `ck_aircraft_spot_link_https`; A10 SSRF). Free-text `comment` + state `remarks` length-capped (250 / 500); HTML-escaped on render.

**PII / audit (deferred emitter, S-027).** `comment` is PII (FADP); `aircraft_owner_person_id` is `pii_ride_through`. Sensitive-not-PII (`immatriculation`, `flarm_id`, `mtom`, `noise_*`, `spot_link`) excluded from non-owning-club DTO. Following S-049 precedent: record `principalUserId()` on `deleted_by_user_id` / `modified_by_user_id` now; mark create / update / soft-delete / state-change / counter-record / ownership-transfer with `// TODO(S-027): emit AuditEvent`.

**OWASP focus.** A01 (cross-tenant write authz) — every mutation method covered by an integration test asserting Club-B principal → 403 against Club-A aircraft. A04 (mass-assignment) — ownership fields not bindable on update. A08 — `immatriculation` UNIQUE conflict surfaced as a typed domain exception, not 500.

## Test plan

**Pyramid.** Unit (domain, no Spring): ~6 aggregate invariant tests on `Aircraft.changeState` / `recordCounter` / `setHomebase` boundary cases. Integration (Testcontainers + real PG per ADR 0021): ~10 covering authz matrix, soft-delete semantics, state-history invariants, reference-data seed. ArchUnit: 0 new — inherits S-155 rules; add a `cross-tenant` package marker assertion (no `@TenantId` annotation on Aircraft / its internals). Playwright: 1 new spec against new stack; legacy `aircrafts-crud.spec.ts` keeps running against legacy until cutover. Vitest: 1 `AircraftStore` reducer spec — no component-spec DOM.

**Non-obvious scenarios.**
- *Cross-tenant authz matrix* (integration): `owner_club_id=X` mutable by CLUB_ADMIN(X) + SYS_ADMIN, 403 for CLUB_ADMIN(Y); `owner_club_id=NULL` (charter) mutable only by SYS_ADMIN. Reads are unscoped — CLUB_ADMIN(Y) can `GET` any aircraft.
- *State-history aggregate invariant* (integration, needs DB to exercise `ux_aas_current_state_per_aircraft`): `aircraft.changeState(s2)` after `changeState(s1)` → exactly one row with `valid_to IS NULL`; prior row's `valid_to` set to new `valid_from`. Concurrent change from stale AR → typed `AircraftStateConflictException`, not 500.
- *Immatriculation global uniqueness*: duplicate across two clubs → 409. Soft-deleted row no longer blocks reuse.
- *Soft-delete semantics*: child rows (`aircraft_aircraft_state`, `aircraft_operating_counter`) NOT cascaded by `deleted_on`; filtered by AR read path; `GET /{id}` of soft-deleted returns 404.
- *HasEngine derived getter* (unit, parameterized): boundary at `GliderWithMotor` — catches off-by-one if seed re-orders.
- *Cross-FK homebase*: aircraft of any club may pin homebase to any non-soft-deleted location (S-049b visibility is enforced on **write** only).

**Parity strategy.** Legacy oracle = `aircrafts-crud.spec.ts` against `fls-e2e`. New spec asserts observable contract (CRUD round-trip, type slices, state transition, immat uniqueness) against `alpenflight-dev`. Cutover gate: zero-delta on behavioral assertions; known deltas (response envelope, soft-delete reuse) recorded in `## Parity exclusions`.

**Fixtures (non-obvious).** `AircraftFixtures.charter()` (`owner_club_id=NULL`); `AircraftFixtures.crossClubHomebase(...)`; `AircraftStateFixtures.withOpenState(...)`.

**Coverage gaps (deferred).** Flight referential coverage → S-058. Reservation → S-068. Rules-engine immat matching → S-073-077. Cross-tenant leakage roster (Aircraft / its internals carry no `@TenantId`) → S-024's parameterized list, not this story.

## Performance plan

Hot path: aircraft picker (every Flight create / Reservation create / Planning setup). V3 already ships every index this story needs.

**Indexes — verified in V3, no new ones needed.** `ux_aircraft_immatriculation` (partial unique WHERE `deleted_on IS NULL` — line 352; satisfies R3 lookup + regulator uniqueness). `ix_aircraft_owner_club`, `ix_aircraft_type`, `ix_aircraft_homebase` (lines 353-356). `ux_aas_current_state_per_aircraft` (partial unique WHERE `valid_to IS NULL AND deleted_on IS NULL`, line 561-563 — current-state lookup as Index Only Scan + enforces "one open state" structurally). `ix_aoc_aircraft_recorded (aircraft_id, at_date_time DESC) INCLUDE(...)` (line 599) — latest-counter Index Only Scan. Implementer must use `ORDER BY at_date_time DESC LIMIT 1`, not MAX subquery, so the planner picks the covering index.

**N+1 trap.** List endpoint touches three lazy associations per row (`aircraft_type` for type / discriminator; current `aircraft_aircraft_state` for flyability; latest `aircraft_operating_counter`). Mitigation: **flat projection DTO** (constructor expression / interface projection) LEFT JOINing `aircraft_type` + `aircraft_aircraft_state WHERE valid_to IS NULL`, plus LATERAL subquery for latest counter. Do NOT `JOIN FETCH` the counter collection — Cartesian explosion against state history.

**Picker endpoint.** Separate `GET /api/v1/aircraft/picker` returning `(id, immatriculation, aircraft_type_id, is_towing_aircraft)` only. SPA caches in Aircraft Signal Store with mutation-bus invalidation.

**Pagination.** Default 25, cap 200. Sort whitelist: `immatriculation, owner_club_id, aircraft_type_id, modified_on`; un-indexed columns 400.

**Out of scope:** flight-list JOIN cost (S-058 owns `ix_flight_aircraft_date`); counter-write throughput (S-058+); statistics / OGN paths. No latency budget — first new-stack baseline captured during this story's parity run.

<!-- modernize-refine: end -->

## Assumptions made

- **Aircraft tenancy reshape (2026-05-16).** Aircraft + AircraftAircraftState + AircraftOperatingCounter are **cross-tenant** per `alpenflight/database/tenant-rules.yaml:275-313` — no `@TenantId`, `owner_club_id` nullable. The story's AC #2 ("Aircraft is `@TenantId`'d (per-club)") is stale; design notes record the contract and Open design questions recommends rewriting via `/modernize-decompose`.
- **Authz model.** Hybrid SYSTEM_ADMIN-always + CLUB_ADMIN-of-owner-club (when non-null); FLIGHT_OPS for operational events (state, counter). Reads are unscoped. See Security plan.
- **AC #5 (parity spec name)** is drift: real file is `e2e/tests/masterdata/aircrafts-crud.spec.ts`, not `26-aircraft-crud.spec.ts`. `parity_test:` frontmatter corrected.

## Implementation status (paused 2026-05-22)

**Done in this PR (#99 against #98):**

- Backend module `ch.alpenflight.aircraft.{domain,application,web,infra}` (~22 Java files, 4-package hexagonal-lite per ADR 0023).
- Aircraft AR with `AircraftStateHistoryEntry` + `AircraftOperatingCounter` aggregate-internals. All invariants on the AR per ADR 0022 §2 (immatriculation regex + length, state-history "one open period", counter monotonicity, FLARM-id / competition-sign / spot-link defenses).
- Reference-data extension: `AircraftType` + `AircraftState` JPA entities + repositories + controllers + DTOs + mapper under `ch.alpenflight.referencedata.*` (read-only, V3-seeded).
- Typed IDs: `AircraftId` (prefixed `ac-`), `AircraftTypeId`, `AircraftStateId`; Jackson + path-converter wired.
- Authz model: `AircraftAccess` SpEL bean — SYSTEM_ADMIN always, CLUB_ADMIN of `owner_club_id` when set, FLIGHT_OPS for state / counter; null-owner = SYSTEM_ADMIN-only. `owner_club_id` / `aircraft_owner_person_id` excluded from `AircraftUpdateDto` (A04 defense); dedicated `POST /transfer-ownership` SYSTEM_ADMIN-only.
- Endpoints: `GET / POST / PUT / DELETE /api/v1/aircraft`, `/picker` slim projection, `/{id}/states` (POST + GET history), `/{id}/counters` (POST + GET history), `/{id}/transfer-ownership`, `/api/v1/aircraft-types`, `/api/v1/aircraft-states`.
- 21 domain unit tests passing (`AircraftDomainTest`).
- ArchUnit + Spring Modulith inherit cleanly; cross-tenant marker is implicit (no `@TenantId` annotation on Aircraft or its internals).
- Boyscout: ADR 0024 amendments 2026-05-22a + 2026-05-22b — vendored two design-system reference bundles under `docs/modernization/design-reference/` and `docs/modernization/design-system/`.

**Open / TODO before mark-done:**

1. **Fix `AircraftsControllerIT` 500s on state/counter endpoints (5 failing tests of 19).** Root cause diagnosed: `em.merge(aircraft)` cascades to transient child entities by COPYING them — the service holds the original transient `entry` / `counter` reference with `id = null`, and `AircraftMapper.toStateResponse` / `toCounterResponse` fails on the `Objects.requireNonNull(entry.getId(), ...)` check. `saveAndFlush` did not fix it because the in-memory reference held by the service is the transient pre-merge instance, not the managed copy.

   **Fix options (pick one):**
   - **Easiest** — assign UUIDs in the `AircraftStateHistoryEntry.open(...)` and `AircraftOperatingCounter.record(...)` factory methods upfront (`this.id = UuidV7.next()`). Aligns with ADR 0019 + the existing `FlsUuidV7Generator` pattern. Drop the `@GeneratedValue(strategy = UUID)` on the child entities.
   - Alternative — after `saveAndFlush(a)`, look up the matching child in `a.getStateHistory()` / `a.getOperatingCounters()` by `(validFrom, aircraftStateId)` / `(atDateTime)` and map that. More fragile.
   - Alternative — use the explicit `EntityManager.persist(entry)` before saving the parent, bypassing merge cascade. Breaks the AR encapsulation.

2. **`AircraftsAuthorizationIT` not yet run.** Compiled, but blocked by the same merge-cascade bug for any state/counter assertion. Once #1 is fixed, run the suite.

3. **Frontend slice not started.** Per the design-system reference bundle:
   - `alpenflight/web/src/app/features/aircraft/aircraft.store.ts` — NgRx Signal Store (CRUD + state-change + counter actions + reference-data lookups). Mirror `locations.store.ts` shape (244 LOC reference).
   - `aircraft-list.page.ts/html` — table page with type-filter dropdown (GLIDER / TOWING / MOTOR) + airworthiness column. Reference: `docs/modernization/design-system/reference/screens-logbook.jsx` for the table-of-aircraft pattern, `preview/components-table.html` for the kit primitive.
   - `aircraft-edit.page.ts/html` — form with reference-data dropdowns + state-history "current state" embedded + read-only counter latest. Reference: `docs/modernization/design-system/reference/screens-entry.jsx` for the form pattern.
   - Add nav-bar entry for "Aircraft" under Masterdata in the SPA shell.
   - Vitest reducer test for `AircraftStore`.
   - New Playwright spec `e2e/tests/masterdata-next/aircraft-crud.spec.ts` asserting observable behavior against the new stack (CRUD round-trip + type-filter slice + state transition).

4. **AC #2 rewrite via `/modernize-decompose`.** Per the refined design, AC #2 ("Aircraft is `@TenantId`'d (per-club)") is stale and contradicts shipped tenant-rules. Run `/modernize-decompose` against S-050 to rewrite as: "Aircraft is cross-tenant — no `@TenantId`; per-flight tenancy lives on `Flight.operating_club_id`. Mutation authz is SYSTEM_ADMIN + CLUB_ADMIN-of-owner-club via the `AircraftAccess` SpEL bean."

5. **Reviewer panel + auto-fix** (Step 7 of `/modernize-implement`). Spawn maintainability + security + parity + tech-writer reviewers against the diff once frontend is in.

6. **Prune + mark-done** (Step 8).

**Pickup command:** `git checkout story/S-050-aircraft-crud && /modernize-implement S-050` — the skill will see `status: in_progress` and `github_pr: 99` and resume rather than restart.

## Parity exclusions

- **Counter-edit / counter-delete admin UI** — deferred. Counter history is append-only by design; corrections are a follow-up.
- **AircraftType + AircraftState admin CRUD** — Flyway-seeded read-only dropdowns only (parallels S-049's LocationType deferral).
- **Charter / public-rental authz checks** (`tenant-rules.yaml:288`) — deferred to Reservation / Flight stories (S-068, S-058).
- **Legacy URL shape** (`/api/v1/aircrafts/listitems/gliders`, `OwnershipType`, `RecordState`, `{Items: [...]}` envelope, `X-HTTP-Method-Override`) — intentionally not preserved (ADR 0022); new spec asserts observable behavior only.
