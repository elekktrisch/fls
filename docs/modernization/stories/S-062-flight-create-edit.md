---
id: S-062
title: Flight create/edit (glider + tow forms — single entity, dual UI)
epic: E-07
status: todo
depends_on: [S-058, S-059, S-060]
acceptance:
  - Glider flight form (mirrors legacy `GliderFormDirective`).
  - Tow flight form (mirrors legacy `TowFormDirective`).
  - Both forms write to the same Flight entity (with appropriate FlightAircraftType discriminator).
  - Copy-flight UX preserved (`/flights/copy/:id` equivalent).
  - Spec `04-flights-create.spec.ts` and `05-flights-edit.spec.ts` pass.
estimate: L
adr_refs: [0005, 0008]
parity_test: tests/flights/04-flights-create.spec.ts, tests/flights/05-flights-edit.spec.ts
refined: true
refined_at: 2026-05-14
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer, frontend-form-engineer]
---

## Context
The bulk of E-07's UI work. Two separate forms, one underlying entity. Largest surface in flight-ops.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Backend: extend Flight controller with create + update + copy endpoints.
- [ ] Validation rules ported from legacy `ValidateFlightBasics` and type-specific validators.
- [ ] SPA: glider form component.
- [ ] SPA: tow form component.
- [ ] SPA: list page (paginated, filterable per legacy).
- [ ] SPA: copy-flight flow.
- [ ] Spec verification.

## Notes
L because two separate forms + list + copy + validation = a lot. Split tasks by form vs. list vs. backend.

Validation depth (illegal crew composition, missing fields, out-of-range timestamps) is unprobed in legacy specs (R14); add depth coverage in S-101.

<!-- modernize-refine: start -->

## Design notes

### Module layout

**Server: `ch.fls.flight.*`** (extending what S-058 already created)
- `web/FlightController.java` — REST controller, endpoints listed under API surface below.
- `service/FlightApplicationService.java` — orchestrates create/update/copy/delete. **Owns the dual-row transaction** (glider + tow rows in one `@Transactional`). Only place outside the tx boundary that writes Flight rows during CRUD.
- `service/FlightValidator.java` — port of `ValidateFlightBasics` plus the start-type-specific arms from `FlightService.cs:985-1039`. Pure function over `Flight` (no DB calls). Returns `List<ValidationError>`.
- `service/FlightCopyService.java` — clones a `Flight` minus identity, times, comments, counters (mirrors `FlightsController.js:232-255`).
- `service/FlightFactory.java` — builds the initial-state Flight from `myClub` defaults. Port of `initForNewFlight` (`FlightsController.js:190-215`) onto the server so SPA new-flight POST returns a populated draft via `GET /flights/new-template`.
- `mapper/FlightMapper.java` — entity ↔ DTO (MapStruct or manual).
- `dto/FlightDetailDto.java`, `dto/FlightListItemDto.java`, `dto/FlightCreateDto.java`, `dto/FlightUpdateDto.java`, `dto/FlightCopyDto.java`, `dto/FlightSearchFilterDto.java`, `dto/ValidationErrorDto.java`.

**Client: `next/web/src/app/flights/`**
- `flight-routes.ts` — Angular standalone route config (`/flights`, `/flights/new`, `/flights/:id`, `/flights/copy/:id`).
- `flight.store.ts` — single `FlightStore` (`withEntities` for list, `withState` for current detail). See §FlightStore in Alternatives below.
- `services/flight-api.ts` — thin wrapper over the orval-generated client (isolates the store from generated-client churn).
- `pages/flight-list/flight-list.component.ts` + `.html` — paginated `<fls-data-table>`, filter bar, "new flight" button.
- `pages/flight-list/flight-list-filter.ts` — typed filter form.
- `pages/flight-edit/flight-edit.component.ts` — **shell page**. Owns route params, masterdata loading, save/cancel, glider↔tow time/location coordination. Hosts one of the two form components.
- `pages/flight-edit/glider-flight-form.component.ts` + `.html` — glider form (mirrors `flight-edit-glider-form.html`). Standalone, takes typed `FormGroup<GliderFlightFormModel>` as input.
- `pages/flight-edit/tow-flight-form.component.ts` + `.html` — tow form. Standalone, takes `FormGroup<TowFlightFormModel>` as input. Conditionally rendered when `startType === Towing`.
- `pages/flight-edit/flight-form.model.ts` — typed Reactive Forms model + `buildFlightForm()` factory.
- `pages/flight-edit/flight-form-coordinator.ts` — cross-form orchestration (start-time copy glider→tow, location mirror, duration warning). Plain TS, no Angular DI.
- `pages/flight-edit/flight-form-defaults.ts` — pulls `myClub.DefaultStartType / HomebaseId / DefaultGliderFlightTypeId / DefaultTowFlightTypeId`. Port of `initForNewFlight`.
- `masterdata.signals.ts` — derived signals over existing master-data stores: `gliderAircrafts`, `towerAircrafts`, `gliderPilots`, `towingPilots`, `winchOperators`, `instructors`, `gliderFlightTypes`, `towingFlightTypes`.

**Motor-flight UI is out of scope** for S-062 — it's the legacy `airmovements/` module owned by S-064.

**DB: no new Flyway migrations.** Schema lives in S-013; `flight.version` for optimistic concurrency comes from S-067.

### Domain model

No new entities. The constraints S-062 must honor (already laid down by S-058):

```java
@Entity @Table(name = "flight")
class Flight {
    @Id @GeneratedValue UUID id;
    @TenantId @Column(name = "operating_club_id", nullable = false) UUID operatingClubId;
    @Enumerated(EnumType.ORDINAL) @Column(name = "flight_aircraft_type_id") FlightAircraftType flightAircraftType;
    @Enumerated(EnumType.ORDINAL) @Column(name = "flight_process_state_id") FlightProcessState processState;
    // FlightAirState is NOT a column. Derived in mapper at serialization time (S-060).
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "tow_flight_id") Flight towFlight;   // self-FK; cascade behavior owned by S-063
    @OneToMany(mappedBy="flight", cascade=ALL, orphanRemoval=true) Set<FlightCrew> crew;
    // ... scalar fields (date, times, counters, comments) ...
    @Version Long version;   // S-067 — used for If-Match
}
```

**Cross-tenant references called out:**
- `FlightCrew.person` — Person is intentionally cross-tenant per ADR 0008. Lookup by PK only; no tenant predicate. Eligibility ("is this Person allowed to fly at this club") is enforced by querying `PersonClub` for `(personId, operatingClubId)`.
- `Flight.startType` — reference data, cross-tenant.
- `Flight.towFlight` — same tenant as parent. The dual-row write **must** explicitly set `towFlight.operatingClubId = parent.operatingClubId` before persisting (don't rely on the resolver — brittle in jobs).

### API surface

All under `/api/v1/flights`. All require an authenticated principal; `@TenantId` filtering is automatic.

| Method | Path | Request DTO | Response DTO | Status | `@PreAuthorize` |
|---|---|---|---|---|---|
| POST | `/flights/search?page=&size=` | `FlightSearchFilterDto` body | `Page<FlightListItemDto>` | 200 | `isAuthenticated()` |
| GET | `/flights/{id}` | — | `FlightDetailDto` | 200, 404 | `isAuthenticated()` |
| GET | `/flights/new-template?startType=` | — | `FlightDetailDto` (`id=null`, club defaults applied) | 200 | `isAuthenticated()` |
| GET | `/flights/{id}/copy-template` | — | `FlightDetailDto` (cleared per copy-reset rules) | 200, 404 | `isAuthenticated()` |
| POST | `/flights` | `FlightCreateDto` | `FlightDetailDto` | 201, 400, 409 | `isAuthenticated()` |
| PUT | `/flights/{id}` | `FlightUpdateDto` + header `If-Match: <version>` | `FlightDetailDto` | 200, 400, 404, 412, 409 | `isAuthenticated()` |
| DELETE | `/flights/{id}` | — | — | 204, 404, 409 (DeliveryBooked) | `isAuthenticated()` |

Notes:
- List endpoint uses POST (filter body) — preserves the legacy choice (commit `3234810` traded GETs for POSTs to keep filter bodies clean).
- DELETE additionally rejects at service layer when `processState == DeliveryBooked` (parity with `FlightService.cs:1308-1312`).
- Lock/transition endpoints belong to S-059, not S-062.

**DTO shape: two-tier.**
- `FlightListItemDto` — flat, ~15 fields (immatriculation, pilot name, dates, durations, computed air state, process state). Mirrors `FlightOverview`.
- `FlightDetailDto` — `{ id, flightDate, startType, version, glider: GliderFlightDetailsDto, tow: TowFlightDetailsDto | null, motor: MotorFlightDetailsDto | null }` — nested-by-discriminator. Mirrors legacy `FlightDetails`.
- `FlightCreateDto` / `FlightUpdateDto` — same as `FlightDetailDto` minus server-managed fields (`id`, `version`, `processState`, `airState`, `validationErrors`, audit metadata).

### Integration with other stories

**Inputs:**
- **S-058**: `Flight`, `FlightAircraftType`, `FlightCrew`, repository finders.
- **S-059**: `FlightProcessState` + `FlightStateService.transition(...)`. S-062 does **not** mutate `processState` directly; validation step sets `Valid`/`Invalid` inline (parity with `FlightService.cs:1041-1050`); every other transition routes through S-059.
- **S-060**: `FlightAirState` derivation; called from `FlightMapper` at serialization time.
- **S-013**: schema (no migration here).
- **S-006**: `SignalStore` reference + per-domain refetch convention.
- **S-007**: typed Reactive Forms + `<fls-field-errors>`.
- **S-008**: UI primitives kit.

**Outputs:**
- **S-063** (glider↔tow link integrity): consumes `FlightApplicationService.create/update` as integration point. S-062 must expose seams so S-063 can wire recursion + cascade-on-delete without refactor. Tests for those live in S-063.
- **S-067** (optimistic concurrency): S-062 plumbs `If-Match` end-to-end now; S-067 adds the `@Version` column + 412 mapping.
- **S-101** (validator depth tests): S-062 ships the validator; S-101 adds depth.
- **S-102** (state transition expansion): S-062 only routes Valid/Invalid through the validator; transitions belong to S-059.

### Alternatives considered

**Q1 — Two form components vs. one parametrized form.** Chose **two separate standalone components** sharing a typed-form model. Reason: glider has fields tow doesn't (winch operator, coupon, invoice recipient, passenger, engine counters); one parametrized form would be `*ngIf` soup. Rejected: parametrized component (template becomes 30% conditional rendering); class inheritance (anti-pattern in standalone-signal Angular).

**Q2 — Where does Flight↔Tow orchestration live?** **Server-side, in `FlightApplicationService` under one `@Transactional` boundary.** Mirrors legacy `InsertFlightDetails`/`UpdateFlightDetails` which writes both rows in one `SaveChanges()` (`FlightService.cs:1249-1299`). Client `FlightFormCoordinator` only handles UX-level mirroring (start-time, location, outbound route glider→tow at submit time per `FlightsController.js:348-378`).

**Q3 — One FlightStore or several?** **One `FlightStore`** with `withEntities` for list + `withState` for current detail. Reason: legacy `Flights` and `PagedFlights` cover the same logical entity; splitting list and detail forces invalidation choreography (the kind legacy already gets wrong). Refetch policy per S-006: "flights refetch-on-visibility." Rejected: separate stores (doubles invalidation surface); per-route ephemeral (loses state during copy flow).

**Q4 — DTO shape: two-tier vs. merge.** **Two-tier** (`FlightListItemDto` + `FlightDetailDto`). Mirrors legacy; ADR 0005 consequences explicitly call out "separate list-view DTOs from detail DTOs as the current code already does." Rejected: one `FlightDto` (payload bloat — crew + locations would be 100s of KB per page).

**Q5 — Split or ship as a single L?** **Recommendation: split into three sub-stories.** S-062 currently bundles 6 deliverables. Proposed split (each individually M):
- **S-062a** — Flight CRUD backend + DTOs + validator port (`new-template` + `copy-template` endpoints). Outputs a working HTTP API exercised via Swagger UI; no UI yet. Depends on S-058/S-059/S-060.
- **S-062b** — Flight list page (paginated, filterable). `FlightStore` skeleton + list component + filter bar + `<fls-data-table>`. Parity smoke. Depends on S-062a + S-006 + S-008.
- **S-062c** — Flight create/edit forms (glider + tow) + copy. Edit shell, both form components, coordinator, copy flow. `FlightStore` extended with detail state + save/delete. Parity specs `04`/`05`. Depends on S-062a + S-062b + S-007.

Why split: (1) reviewability — backend and form changes are different stacks; (2) risk surface — validator port is the parity-critical piece, isolate it; (3) dependency unlock — S-063 only needs S-062a; (4) estimate accuracy — each piece is independently M.

**See `## Open design questions` for the split-shape vote** — requirements-engineer proposed a 5-way split (S-062a..e) instead of this 3-way one.

## Edge cases & hidden requirements

### Edge cases (per acceptance criterion)

**AC1 — Glider flight form**
- Null/empty: `AircraftId`, `PilotPersonId`, `FlightTypeId`, `StartLocationId`, `LdgLocationId`, `StartTypeId`, `FlightDate` may all be null on the wire — server defers required-field checks to async validation, not insert. Insert succeeds in `NotProcessed` state regardless (`FlightService.cs:1073-1100`, `1249-1268`). **Decide: preserve permissive insert (legacy) or reject at create (new). TBD — see Open questions.**
- Boundary: `NrOfLdgs` accepted as null when `NoLdgTimeInformation=true`; minimum 1 when landing time set (`FlightService.cs:1102-1109`).
- Boundary: `StartDateTime > LdgDateTime` is not rejected at insert — legacy only warns client-side (`FlightsController.js:590-599`).
- Glider with `StartType=SelfStart(3)` / `WinchLaunch(2)` / `ExternalStart(4)` / `MotorFlightStart(5)`: no tow flight allowed; legacy strips `TowFlightDetailsData` when `!needsTowplane` (`FlightsController.js:375-377, 418-420`).
- Glider with `StartType=Towing(1)`: tow flight required by validation but **not** by create (see Hidden requirements).
- `IsSoloFlight + CoPilotPersonId` set → CoPilot silently cleared (`FlightsController.js:425-427`). Server doesn't enforce; client does.
- Engine counters: `EngineEnd < EngineStart` produces 0 duration client-side (`FlightsController.js:767-777`); server has no validator. Decide whether new rejects.
- Unauthorized: edit on `ProcessState >= Locked` without ClubAdmin role → `CanUpdateRecord=false` (`FlightService.cs:1741-1770`); update still goes through if client bypasses the flag — server does NOT re-check on `UpdateFlightDetails` (`FlightService.cs:1270-1299`). Only `DeliveryBooked` is hard-blocked. **Legacy bug worth porting correctly.**
- Cross-tenant: `Pilot/CoPilot/Instructor/Observer/Passenger/WinchOperator` PersonId may belong to a different `OperatingClub` — legitimate. Verify the form accepts persons from other clubs (`Persons.getGliderPilots()` controls this).

**AC2 — Tow flight form**
- Tow without parent glider: legacy `TowFlight` rows are **always** children of glider rows when `StartType=Towing(1)` — no standalone tow insert path. New: confirm tow form renders only as sub-section of glider form, not standalone route (`FlightsController.js:418-420, 666-673`).
- Tow inherits `StartDateTime` + `StartLocationId` + `OutboundRoute` from parent glider at save (`FlightsController.js:370-372`). Editing these on the tow form has no effect — they're overwritten.
- Boundary: tow landing < glider start → warn but allow (`FlightsController.js:590-599`).
- Tow `AircraftId` empty → tow data discarded entirely (`FlightsController.js:375-377`). Empty Guid normalized to `""` client-side (`FlightsController.js:319-324`).

**AC3 — Single entity, discriminator**
- Discriminator derivation: not in DTO — derived server-side from which `*DetailsData` block is populated (`FlightDetails.cs:13-17`). New API must define this explicitly (per OpenAPI / ADR 0005 — discriminated unions need a tag field).
- Concurrent edit: two users edit same flight in two tabs; second save overwrites first silently. Legacy has no optimistic concurrency (`FlightService.cs:1282-1297`). **TBD whether new gets ETag/version checks** — depends on S-067 sequencing.
- Deleted-mid-flow: glider deleted while tow form open → tow form save fails (FK violation). Legacy cascades tow→glider delete (`FlightService.cs:1314-1319`).
- Discriminator mid-edit change: legacy doesn't allow Glider → Motor (separate `*DetailsData` blocks); confirm new API enforces.

**AC4 — Copy-flight**
- Copy preserves: `FlightDate`, `StartType`, `GliderFlightDetailsData` (mostly), `TowFlightDetailsData` (mostly).
- Copy clears: `FlightId`, all `StartDateTime`/`LdgDateTime`, `FlightComment`, `CouponNumber`, engine counters (`FlightsController.js:232-254`).
- Copy of `DeliveryBooked` flight: legacy allows it (creates new `NotProcessed` copy) — terminal state only blocks edit/delete on the original.
- Copy of cross-club-owned flight: not derivable — flight detail GET is club-scoped via `IsCurrentUserInClub`; needs verification under ADR 0008's `@TenantId` semantics.
- Unauthorized: any authenticated user can hit `/flights/copy/:id` — no role gate (`AuthService.js:141-149`). Decide whether new gates copy by `CanUpdateRecord` on source.

**AC5 — Specs 04/05 pass**
- Spec 04 (`04-flights-create.spec.ts:109-131`) injects values directly on `$scope` to bypass selectize widgets — new SPA needs equivalent hooks (`data-testid` on aircraft/pilot dropdowns) or the spec must be rewritten. Story doesn't say which.
- Spec 05 round-trips `FlightComment` via `GliderFlightDetailsData` shape (`05-flights-edit.spec.ts:88, 96-98`). If new API flattens the nested DTO, spec breaks unless rewritten. **Critical for S-058's reshape decision.**

### Hidden requirements (legacy behavior the story doesn't mention)

- **Create endpoint inserts with `ProcessState=NotProcessed` regardless of completeness** — validation is async via `POST /flights/validate` (`FlightService.cs:917-946`, `1249-1268`).
- **`PUT /flights/{id}` rejects only `DeliveryBooked`** — not `Locked`, not `DeliveryPrepared`. Client uses `CanUpdateRecord` to hide UI, but server doesn't re-enforce (`FlightService.cs:1276-1280`).
- **Cascading tow-flight delete** when deleting parent glider — manual (no SQL Server self-FK cascade) at `FlightService.cs:1314-1319`. Postgres + Hibernate self-FK behavior must be deliberately specified.
- **Empty-Guid normalization on tow data** (`00000000-...-000000000000` → `""`) at `FlightsController.js:319-324`. New API should reject empty UUIDs at the wire.
- **Tow fields auto-synced from glider on save:** `StartDateTime`, `StartLocationId`, `OutboundRoute` copied glider→tow at `prepareForSaving` (`FlightsController.js:370-372`). Preserve server-side or document the client must do it.
- **`copyTowingFromLast` + `lastTowAircraftId` in localStorage** (`FlightsController.js:147-152, 348-358`). Client UX convenience: remember last-used tow aircraft/pilot per workstation.
- **`HomebaseId` default** for new flight locations from `myClub` (`FlightsController.js:200-206`).
- **`SoloFlightCheckboxEnablementCalculator`** auto-derives solo-flag from `FlightType.IsSoloFlight`/`IsPassengerFlight` and `Aircraft.NrOfSeats==1` (`FlightsServices.js:75-98`, `FlightsController.js:111-124`).
- **Number-of-seats warning**: `Aircraft.NrOfSeats < FlightType.MinNrOfAircraftSeatsRequired` (`FlightsController.js:583-588`). Non-blocking, client-only.
- **Outbound/Inbound route validation against `InOutboundPoints` allowlist** per location (`FlightService.cs:1112-1136`) — only at validation time, not create.
- **Defaults: `StartType` from `myClub.DefaultStartType || "1"`** and `FlightType` from `myClub.DefaultGliderFlightTypeId` / `DefaultTowFlightTypeId` (`FlightsController.js:198-206, 159`).
- **`GetFlightDetails` returns `CanUpdateRecord`/`CanDeleteRecord` flags** computed from `ProcessState` + role (`FlightService.cs:1725-1771`). New API must include them or SPA loses disable-edit behavior.

### Scope clarifications

**In:** create / read / update / copy / delete (glider + tow + glider-with-tow). Single-tenant view. `NotProcessed` initial state. `CanUpdate/CanDelete` flag computation. Cascading tow delete.

**Out:**
- State transitions other than initial create (S-059 / S-061).
- Async validate-flights workflow trigger (`POST /flights/validate`) — that's a job.
- **Motor flight form** — story doesn't mention but legacy `MotorFlightDetailsData` exists. Ambiguous; see Open questions. (Architect's view: out — covered by S-064 air-movements.)
- List filters by AirState + ProcessState (the dropdown directives aren't called out).
- Audit-log emission (C12 — cross-cutting, S-027).

**Ambiguous:** motor flight form; eager-vs-deferred create validation; optimistic concurrency; DTO discriminated-union vs. nested-by-discriminator. All flagged in Open questions.

### NFR call-outs

- **Performance**: list endpoint is the hot path; p95 < 250ms server-side target (more in Performance plan).
- **Security**: `[Authorize]` everywhere (per ADR 0007); `CanUpdateRecord` must be server-enforced on PUT/DELETE (legacy gap).
- **Observability**: audit log per C12 — every Insert/Update/Delete/Copy emits an audit event.
- **i18n**: `VALIDATION_ERROR_*` keys come back as message keys, translated client-side. Under C15 they need to exist in `next/web/`'s bundles.
- **Accessibility**: selectize is hostile to assistive tech (and to Playwright — `04-flights-create.spec.ts:64`). New form needs native or a11y-tested replacement.

## Security plan

### Threat model

- **Cross-tenant Person FK injection (high)**: tenant-A POSTs `FlightCreate` with `pilot_person_id` belonging to a Person who has no `PersonClub` row for tenant A. Legacy UI hides this (Persons dropdown is server-filtered via `PersonClubs.Any(ppc=>ppc.ClubId==current)`) but legacy `InsertFlightDetails`/`ValidateFlightBasics` (`FlightService.cs:1073-1137`, `:1249-1268`) do NOT re-validate at write time — **latent legacy vuln**. Mitigation: write-time membership check (see Authorization).
- **Cross-tenant Flight read/edit (high)**: tenant-A passes a tenant-B `flightId` to GET/PUT/DELETE/copy. Mitigation: `@TenantId` on `flight.operating_club_id` (S-022) auto-filters; lookup returns empty → controller returns 404 (NOT 403 — 403 leaks existence). Verified by S-024.
- **Cross-tenant Aircraft/FlightType/Location FK injection (high)**: same shape for `aircraft_id`, `flight_type_id`, `start_location_id`, `ldg_location_id`. Mitigation: resolve each FK via tenant-scoped repository before persisting; mismatch → 422.
- **TowFlight FK swap (high)**: tenant-A glider passes `tow_flight_id` pointing at tenant-B tow row. Same mitigation. Equally important: nested tow create payload inherits `operating_club_id` from parent, not from client.
- **Mass-assignment via DTO (med)**: client posts `operating_club_id`, `owner_id`, `process_state_id`, `validation_errors`, `version`, audit columns. Mitigation: `FlightCreate`/`FlightUpdate` DTOs do not expose these; tenant set by resolver, state always `NotProcessed` on create. Reject unknown JSON fields (`FAIL_ON_UNKNOWN_PROPERTIES=true`).
- **Process-state bypass via update (high)**: client edits a `DeliveryBooked` flight. Legacy throws `LockedFlightException` (`FlightService.cs:1276-1280`). Mitigation: PUT/PATCH/DELETE reject when `process_state == DeliveryBooked`; additionally reject `Locked`/`DeliveryPrepared` unless caller is `CLUB_ADMINISTRATOR` (mirrors `SetFlightOverviewSecurity` `:1675-1687`).
- **State-transition smuggling (high)**: client puts a new state value into Create/Update DTOs. Mitigation: state field is read-only on these DTOs; transitions only via S-059.
- **Optimistic-concurrency bypass (med)**: two admins edit same flight; second wins silently. Mitigation: S-067 `@Version` + `If-Match`; 412 on mismatch.
- **PII leak via validation error echo (med)**: server returns full Person in 422 payload. Mitigation: 422 body uses only IDs + i18n keys.
- **PII leak via audit `before_state`/`after_state` (high under FADP)**: naive serialization includes name/email. Mitigation: audit serializer redacts via S-027 PII config — store `person_id` only.
- **Stored-XSS via free-text fields (med)**: `flight_comment`, `outbound_route`, `inbound_route`, `coupon_number` echoed into Angular and Excel. Mitigation: length cap + reject control chars; client uses interpolation, never `[innerHTML]`.
- **Unauthenticated create (high)**: no bearer → 401. Mitigation: ADR 0007 baseline.
- **Role-elevation via JWT claims (med)**: spoofed `realm_access.roles`. Mitigation: JWKS signature validation (S-026).

### Authorization

- `POST /flights`: `@PreAuthorize("isAuthenticated()")`. Service enforces operating_club_id = current tenant via `@TenantId`. Matches legacy `[Authorize]` on `Insert` (`FlightsController.cs:228`).
- `PUT /flights/{id}`: `@PreAuthorize("isAuthenticated()")`. Service refuses when `process_state >= Locked` AND caller lacks `CLUB_ADMINISTRATOR`; refuses unconditionally when `process_state == DeliveryBooked`. Mirrors `SetFlightOverviewSecurity` (`:1675-1687`) and `:1276-1280`.
- `DELETE /flights/{id}`: `@PreAuthorize("isAuthenticated()")`. Refuses on `DeliveryBooked` (legacy `:1308-1312`); club admin required when `>= Locked`. Cascade-deletes linked TowFlight (`:1315-1318`).
- `POST /flights/copy/{sourceId}`: `@PreAuthorize("isAuthenticated()")`. Reads source via tenant-scoped repo (cross-tenant → 404); produces fresh `Flight` owned by caller's tenant.
- GET endpoints: `@PreAuthorize("isAuthenticated()")` — `@TenantId` filters; no role gate (legacy `[Authorize]`).
- **Tenant gate (all endpoints)**: `@TenantId`. No `runUnscoped` in this story.

### Input validation

- `flight_date`: `@NotNull`, `@PastOrPresent` with tolerance (`VALIDATION_ERROR_No_flight_date_set`, `:1075-1076`).
- `flight_aircraft_type`: `@NotNull`, enum. Must match aircraft category (cross-field).
- `aircraft_id`: `@NotNull`, tenant-scoped repo → 422 `VALIDATION_ERROR_No_aircraft_set` / `..._Aircraft_not_in_club` if not found.
- `pilot_person_id` (FlightCrew, type `PilotOrStudent`): `@NotNull` + **business invariant: Person must have `PersonClub` with `club_id == operating_club_id` and `is_pilot=true`**. Stricter than legacy (closes latent vuln). Cite: legacy `Persons.getGliderPilots()` filters via `PersonClubs.Any(ppc.ClubId == current && !ppc.IsPassenger)` (`PersonService.cs:538, 612-613`) — server enforcement closes the gap.
- `co_pilot_person_id` / `instructor_person_id` / `observer_person_id` / `passenger_person_id` / `winch_operator_person_id`: same membership invariant; instructor additionally requires `is_instructor=true`.
- `start_date_time` / `ldg_date_time`: nullable iff matching `no_*_time_information` flag is true (`:1084-1088`). `ldg > start` when both present. Both within `flight_date` ± 24h.
- `start_location_id` / `ldg_location_id`: `@NotNull`, tenant-scoped (`:1090-1094`).
- `start_type_id`: `@NotNull`, enum (`:1096-1097`). When `TowingByAircraft` → `tow_flight_id` (or nested tow payload) required for `GliderFlight` (`:987-994`). When `ExternalStart` → tow_flight_id must be null (`:1019-1022`). When `WinchLaunch` → `winch_operator_person_id` required (`:1024-1030`).
- `flight_type_id`: `@NotNull`, tenant-scoped; flag must match (`is_for_glider_flights` etc.).
- `tow_flight_id`: when present, tenant-scoped lookup. For nested tow create, server inherits `operating_club_id` from parent.
- `nr_of_ldgs`: `@NotNull` iff `ldg_date_time` set (`:1102-1109`); `@Min(1)`.
- `outbound_route` / `inbound_route`: `@Size(max=200)`; required iff location has `is_outbound_route_required=true` and value must be in `InOutboundPoints` allow-list (`:1112-1136`). Case-insensitive.
- `flight_comment`: `@Size(max=2000)`; reject ASCII control chars except `\n\r\t`.
- `coupon_number`: `@Size(max=50)`, alphanumeric + `-`.
- `engine_*_operating_counter_in_seconds`: `@Min(0)`; end ≥ start when both present.
- `process_state` / `operating_club_id` / `owner_id` / `validated_on` / audit columns: **not on Create/Update DTOs** — mass-assignment defense.
- `version` (S-067): required on update via `If-Match`; mismatch → 412.

### PII handling

- `Person.firstname` / `lastname` / `email` / `mobile` / `medical_*` / `licence_*`: Person PII. In Flight DTOs, only IDs are stored/echoed; names appear only in projected list/detail DTOs and are not logged.
- Audit `before_state` / `after_state`: persist FK IDs only — never embed denormalized Person fields. S-027 PII redaction list must include `firstname`, `lastname`, `email`, `mobile`, `medical_*`, `licence_*`.
- Validation error payloads: i18n keys + field path only, never the offending PII value.
- Application logs: never full request body. Log `actor_user_id`, `tenant_club_id`, `flight_id`, `event_type` only.

### Audit-log events

- `flight.created` — on successful `POST /flights`. Payload: `{actor_user_id, tenant_club_id, event_type:"flight.created", target_entity_type:"Flight", target_entity_id:<new_id>, before_state:null, after_state:<full Flight JSON minus PII>}`.
- `flight.updated` — on `PUT`. `before_state` (pre-mutation) and `after_state` both included.
- `flight.deleted` — on `DELETE`. If linked TowFlight cascade-deletes (`:1315-1318`), emit a **second** `flight.deleted` for the TowFlight ID — one event per row mutated. Both share `request_id`.
- `flight.copied` — on copy. Payload includes `metadata: {source_flight_id}`.
- **Linked glider+tow create:** emit **two** `flight.created` events sharing `request_id` (parent + tow). Rationale: querying "all changes to flight X" must return events keyed by `target_entity_id` consistently.
- Failed mutations: `flight.*.failed` events with status + error code (S-027 `failed=true`). Failing request body **not** persisted (could carry PII).

### Cross-tenant leakage

- Auto-filtered: `Flight`, `Aircraft`, `FlightType`, `Location`, `FlightCrew` (via parent). No `findByIdUnscoped`, no native SQL.
- **Cross-tenant Person FK is legitimate** per ADR 0008 — Person is NOT `@TenantId`-scoped. Membership check via `PersonClub` (which IS scoped) is the gate.
- **No `runUnscoped`** in this story. Any introduction triggers S-023 audit-log alert.
- S-024 leakage test must include: (a) cross-tenant Flight read → 404; (b) cross-tenant PUT/DELETE → 404; (c) cross-tenant copy → 404; (d) cross-tenant aircraft/flight-type/location FK → 422; (e) Person without tenant `PersonClub` → 422.

### OWASP applicability

- **A01 Broken Access Control**: dominant risk. Mitigations: `@TenantId`, service-layer state/role gates, FK-tenancy resolution, 404 (not 403) for cross-tenant.
- **A02 Cryptographic Failures**: JWT signature validation (S-026); TLS at ingress. No app-level crypto here.
- **A03 Injection**: JPA parameterized queries only; reject control chars in free-text.
- **A04 Insecure Design**: process-state gate + cross-club Person membership gate + optimistic concurrency = design-level mitigations.
- **A05 Security Misconfiguration**: profile sets `FAIL_ON_UNKNOWN_PROPERTIES=true`, `spring.jpa.open-in-view=false`, CORS narrowed (legacy was `*`).
- **A06 Vulnerable Components**: (N/A — dependency-scanning story).
- **A07 Auth Failures**: (N/A — inherited from ADR 0007 + S-026).
- **A08 Software & Data Integrity Failures**: optimistic concurrency (S-067) + audit-event chain.
- **A09 Logging & Monitoring Failures**: audit events per S-027; failed mutations logged.
- **A10 SSRF**: (N/A — no outbound fetch).
- **GDPR/FADP**: Person PII handling per §PII; audit-event redaction load-bearing.

## Test plan

### Coverage contract (read first)

This story owns **happy-path parity** for create/edit/copy on glider and tow flights, plus unit-level coverage of mapping, copy-reset logic, and dual-form-to-single-entity orchestration. Depth is explicitly deferred:

| Dimension | Owner |
|---|---|
| Validation rejection paths (`ValidateFlightBasics` failures) | **S-101** |
| Illegal `FlightProcessState` transitions | **S-102** |
| Time-gate boundaries (≥2d / ≥3d) | **S-103** |
| Permission matrix per endpoint × role | **S-104** |
| Glider↔Tow cascade / orphan / concurrent | **S-105** (depth) + **S-063** (impl) |
| Cross-tenant isolation per endpoint | **S-024** (CI) + **S-106** (HTTP) |
| Optimistic concurrency 412 | **S-067** |

S-062's job: green the two legacy parity oracles (`04-flights-create.spec.ts`, `05-flights-edit.spec.ts`) on the new stack, and provide just-enough lower-pyramid coverage to support the depth stories.

### Test pyramid for this story

- **Unit**: ~22 — DTO↔Flight mapping, copy-reset rules, discriminator assignment, FlightCrew aggregation.
- **Integration**: ~18 — controller + service against Testcontainers Postgres, `@WithTenant`, happy-path round-trips per type + tenant smoke.
- **E2E new in this story**: 2 — copy-flight happy-path; glider+tow paired-create.
- **Parity**: 2 specs handed off unchanged to S-109 — `04` + `05`. Zero-delta gate.

### Unit tests

Server (JUnit 5, no Spring context):

- `flightCreateDtoToEntity_glider_selfLaunch_setsAircraftTypeGlider` — `FlightDetailsMapper#toEntity`.
- `flightCreateDtoToEntity_glider_towing_setsLinkedTowFlight` — two `Flight` rows linked via `towFlightId`. `FlightDetailsMapper#toEntity`.
- `flightCreateDtoToEntity_motorFlight_setsAircraftTypeMotor` — discriminator path.
- `flightDetailsToDto_includesCalculatedAirState` — DTO carries computed `FlightAirState` from S-060.
- `flightDetailsToDto_preservesProcessState` — stored state round-trips.
- `flightCrewMapping_assignsRolesPerPosition` — pilot/co-pilot/instructor/observer/winch-operator → right `FlightCrewType`.
- `flightCrewMapping_noPaxByDefault`.
- `copyReset_clearsFlightIds` — `FlightCopyService#copyFrom`. (`FlightsController.js:232-255`.)
- `copyReset_clearsTimestamps`.
- `copyReset_clearsFlightComments`. (`FlightsController.js:244-251`.)
- `copyReset_clearsEngineCounters`. (`FlightsController.js:246-247`.)
- `copyReset_preservesFlightDate`. (`FlightsController.js:236`.)
- `copyReset_preservesStartType`. (`FlightsController.js:238`.)
- `copyReset_preservesCrew`.
- `copyReset_preservesAircraft`.
- `copyReset_doesNotPersist`.
- `flightProcessState_newFlightDefaultsToNotProcessed` — verifies S-059 wiring.

Web (Vitest + Angular Testing Library):

- `gliderForm_disablesTowFieldsWhenStartTypeSelfLaunch` — UI conditional render.
- `gliderForm_enablesTowFieldsWhenStartTypeTowing` — tow form section becomes visible + required-marked.
- `towForm_engineCounterShownOnlyForEngineGliders` — legacy "2-seater no-engine skips engine block" comment at `04-flights-create.spec.ts:96`.
- `gliderForm_submitDisabledUntilRequiredFieldsPresent` — mirrors `04:146`.
- `copyFlightFlow_navigatesToNewWithPrefilledFormState` — route handler test for `/flights/copy/:id`.

### Integration tests

Slice: `@SpringBootTest` + Testcontainers Postgres + transactional rollback per test + `@WithTenant(CLUB_A)` (per S-015).

**`FlightCreateControllerIT`**
- `postGliderFlight_selfLaunch_returns200AndPersists` — mirrors injected shape from `04:108-138`.
- `postGliderFlight_towing_pairCreated` — POST with both blocks → two rows linked. Foundation for S-063 / S-105.
- `postMotorFlight_persistsWithCorrectDiscriminator` — smoke (full coverage in S-064).
- `postFlight_newFlightLandsInNotProcessedState` — verifies S-059 default wiring.
- `postFlight_responseIncludesCalculatedAirState` — verifies S-060 wiring.

**`FlightUpdateControllerIT`**
- `putFlight_roundTripsFlightComment` — integration expression of `05:71-78`.
- `putFlight_preservesProcessStateWhenInValidOrLocked` — PUT does not silently transition.
- `putFlight_rejectsWhenFlightInDeliveryBookedState` — 409/423 mirrors `:1276-1280`.
- `putFlight_partialUpdateOfGliderPreservesTowLink` — S-063 line-10 basis.

**`FlightCopyControllerIT`**
- `copyFlight_returnsDraftWithBlankedTimestamps` — server mirror of `FlightsController.js:232-255`.
- `copyFlight_doesNotPersistUntilExplicitSave` — copy is read-only.
- `copyThenPost_createsIndependentRow`.

**`FlightTenantScopingIT`** (smoke; catalog in S-024/S-106)
- `postFlight_isScopedToCallerClub` — `@TenantId` auto-applies.
- `getFlight_acrossTenant_returns404`.
- `putFlight_acrossTenant_returns404`.
- `copyFlight_acrossTenant_returns404`.

**`FlightDtoContractIT`**
- `flightDetailsDto_shapeMatchesLegacyContract` — round-trip against committed JSON fixture captured from legacy. Field-by-field diff with tolerated-fields list (`createdOn`, etc.). Cheap parity probe.

### E2E tests (new in this story)

- `e2e/tests/new/04b-flights-copy.spec.ts` — `flights:copy preserves aircraft + crew, clears timestamps`: create source via API → `/flights/copy/:id` → assert prefill + cleared fields → submit → assert second row distinct from source.
- `e2e/tests/new/04c-flights-paired-create.spec.ts` — `flights:create glider+tow pair via single submit`: `/flights/new` → `StartType=Towing` → fill both sections → submit once → assert two rows in DB (glider with `towFlightId`, tow with matching `flightId`). Legacy `04` uses self-launch to avoid the tow form — this is genuinely new e2e coverage.

### Parity tests

Both run unchanged against new stack (handed off via S-109):

- **`04-flights-create.spec.ts`** — selector adaptation needed: legacy injects via `angular.element(form).scope()` (`:65-138`); new SPA exposes `data-testid` on form fields, dropdowns, save button. DB-level assertion (`:165`) is portable.
- **`05-flights-edit.spec.ts`** — selector adaptation for `input#Comment` (`:50`) → recommend `data-testid="flight-comment"`. API readback (`:72`) and UI reload (`:78`) port unchanged.

**Cutover gate: zero-delta.** Both green on new stack with byte-identical assertions (selector adaptation allowed, behavior assertions not).

### Test data + fixtures

- **Testcontainers Postgres** per S-015, `reuse=true`. Transactional rollback per method (~10ms/test).
- **`FlightFixtures.gliderFlightInValidState()`** — class-scope; builds full Valid Flight in CLUB_A. Modeled after `ensureGliderFlight` (`e2e/test-data.ts`).
- **`FlightFixtures.gliderTowPair()`** — linked-pair shape.
- **`FlightFixtures.gliderFlightInDeliveryBookedState()`** — for rejected-update test.
- **`MasterDataFixtures.minimalClubSetup()`** — one Club, glider Aircraft (2-seater no-engine per `04:96-98`), tow Aircraft, pilot Person, tow pilot Person, 2 Locations (no in/outbound route reqs), Self-launch + Towing StartTypes, glider FlightType (no `IsPassengerFlight`/`InstructorRequired`/`ObserverPilotOrInstructorRequired` per `04:102-104`), tow FlightType. Loaded once per class, rolled back per method.
- **JSON contract fixtures** for `FlightDtoContractIT`: `src/test/resources/parity/flight-details-glider-self-launch.json`, captured against legacy.
- **E2E specs** use existing `withPool` SQL pre-clean pattern (`04:49-53`).

### Coverage gaps (deferred)

- Validation rejection paths (15+ `VALIDATION_ERROR_*` from `:1075-1136`): **S-101**.
- State transitions: **S-059** (matrix) + **S-102** (illegal-pair coverage).
- Time gates: **S-061** (impl) + **S-103** (boundary).
- Permission boundaries per endpoint × role: **S-104**.
- Cross-tenant per-endpoint catalog: **S-024** (CI) + **S-106** (HTTP).
- Glider↔Tow cascade / orphan / concurrent edit: **S-063** (impl) + **S-105** (depth).
- Optimistic concurrency 412: **S-067** (not blocking S-062; legacy doesn't have it either).
- OGN ingestion endpoint write path: **S-066**.
- Air movements (motor-flight UI): **S-064**.

### Risks

- **Selector drift on parity oracles** — legacy specs use `angular.element(form).scope()` bypass. Mitigation: add `data-testid` to every interactive element on both forms; S-109 verifies pre-cutover.
- **DTO shape drift** between Web API and JPA — `FlightDetails` is hand-mapped legacy-side. Mitigation: `FlightDtoContractIT` catches drift.
- **Copy-flight endpoint shape is an open ADR question** — legacy is client-side (`FlightsController.js:232-255`); new might be server-side. Architect recommends server-side; QA flagged this as a fork that changes test placement.
- **Calculated `FlightAirState` reads "now"** — must be pure on timestamps to avoid test flakiness. S-060 verifies.
- **Transaction rollback hides constraint violations** that fire on commit — S-015 provides a `@Commit` helper for these edge cases.
- **Testcontainers cold start on CI** — ~30s first hit per JVM; mitigated by `reuse=true`.

## Performance plan

### Hot paths

- **`POST /api/v1/flights/page`** (list — new equivalent of legacy `gliderflights/page`): dominant read. **Bursty 5–15 rps per club during ops hours**; top-5 route per S-108.
- **`GET /api/v1/flights/{id}`** (edit fetch): low rate (<1 rps) but every form open. Eager graph required.
- **`POST /api/v1/flights`** + **`PUT /api/v1/flights/{id}`**: low rate, bursty around busy Saturday. Concurrent with OGN ingestion.
- **`POST /api/v1/flights/copy/{id}`**: rare; reads full graph then writes.
- **Implicit hot path**: master-data fan-out the form needs (aircraft, persons, locations, flight types, start types) — each form open triggers ~5 GETs. Legacy `AircraftsServices.js` caches; new SPA must too or page-load p95 < 3s is lost.

### Required indexes

Story frontmatter names only three. The legacy filter set (`DBUpdate_v1.9.30.sql`) is broader. Required:

- `flight(operating_club_id, flight_date DESC)` — tenant + default sort. **Make the order match the default sort** so Postgres does an index-order scan (skips Sort).
- `flight(operating_club_id, flight_process_state_id)` — **reverse the column order** in S-013 (currently `flight_process_state_id, operating_club_id`). `operating_club_id` is always present; process state is secondary.
- `flight(aircraft_id)` — `Immatriculation` filter joins through Aircraft. **Likely missing from S-013.**
- `flight(flight_type_id)` — `FlightCode` filter. **Likely missing.**
- `flight(tow_flight_id)` — already in S-013.
- `flight(glider_pilot_person_id)` / `flight(flight_instructor_person_id)` / `flight(tow_pilot_person_id)` — if S-058 lifts these to direct FKs, each is filtered/joined for names. **Confirm.**
- `flight_crew(flight_id)` — list crew lookup, detail fetch. Legacy `IX_FlightCrew_FlightId`.
- `flight_crew(person_id)` — reverse lookup for `/flightreports`. Legacy `IX_FlightCrew_PersonId`.
- `flight(start_location_id)`, `flight(landing_location_id)` — name-substring filters; small.
- **Composite covering index** `flight(operating_club_id, flight_date DESC, start_datetime DESC) INCLUDE (aircraft_id, flight_process_state_id, flight_type_id)` — **only after measuring**. Start with single-column FK indexes.
- **Postgres-specific**: legacy `Immatriculation LIKE '%X%'` and `Lastname LIKE '%X%'` need either prefix-match restriction (semantics break) or `pg_trgm` GIN indexes. Flag for post-cutover budget.

**Action**: open S-013, diff against `DBUpdate_v1.9.30.sql`, add missing FK indexes. Do not ship S-062 until S-013 carries equivalents.

### N+1 risks

The list query in legacy EF is one round-trip. The Hibernate risk is **dropping into default lazy-load** and per-row queries (50 rows × 5 refs = 250 extra). Specific risks:

- **`Flight.aircraft → Aircraft.immatriculation`**: project to DTO in JPQL (`SELECT new FlightListItem(f.id, f.aircraft.immatriculation, ...)`) or use `@EntityGraph(attributePaths={"aircraft"})`. **Prefer DTO projection** — list never needs full Aircraft entity.
- **`Flight.flightType.flightCode`** — DTO-project.
- **`Flight.startLocation.locationName` / `landingLocation.locationName`** — DTO-project.
- **`Flight.gliderPilotPerson` / `flightInstructorPerson` / `towPilotPerson`** — if S-058 lifted to direct FKs, DTO-project the name fields.
- **`Flight.flightCrews` collection** — if pilot/second-crew derived from `FlightCrew`, legacy computes per-flight pilot+second-crew with grouped subquery (`FlightService.cs:464-479`). Port as CTE / window-function projection, **not** `@OneToMany(fetch=LAZY)` iterated in Java. Highest-risk N+1.
- **`Flight.towFlight`** — only for detail. On list, do **not** fetch-join.

**Mitigation rule**: list endpoint executes **exactly one SQL statement** per page. Add a Hibernate `Statistics.queryExecutionCount() == 1` (+1 for pagination count) assertion in tests.

For **edit endpoint**, eagerly fetch full graph in **one query** via `@EntityGraph(attributePaths = {"aircraft", "flightType", "startType", "startLocation", "landingLocation", "flightCrews", "flightCrews.person", "towFlight", "towFlight.aircraft", "towFlight.flightType", "towFlight.flightCrews", "towFlight.flightCrews.person", "towFlight.startLocation", "towFlight.landingLocation"})` — mirror legacy `ValidateFlight()` graph at `:959-976`. See Cartesian risks.

### Cartesian / explosion risks

- **Detail-fetch graph dangerous as single join**: Flight × FlightCrews (1–3) × StartLocation.InOutboundPoints (5–20) × TowFlight.FlightCrews (1–2) × TowFlight.StartLocation.InOutboundPoints (5–20) = ~2400 rows for one flight. Mitigations (pick one):
  1. **Two separate queries** in the controller: scalars + FKs in query 1, lazy collections IN-batched via `@BatchSize` in query 2.
  2. **Hibernate `default_batch_fetch_size`** set globally (e.g. 20). JPQL projects scalars; lazy collections resolve in two batched IN queries. Simpler, same SQL count.
- **List query has no Cartesian risk** if it stays a flat DTO projection. Forbid `JOIN FETCH` on `flightCrews` in the list query.

### Caching strategy

**Server-side:**
- **List endpoint**: no server-side cache. Invalidates on every create/edit/OGN-ingest.
- **Form-load reference data** (FlightType, FlightCostBalanceType, StartType, AircraftType, CounterUnitType, locations): Caffeine, **TTL 10 min**, keyed by `(clubId, dataType)`. Invalidate on master-data mutation via `ApplicationEvent`.
- **Aircraft / Person listitems**: same. Hot — every form open hits them.
- **No HTTP cache headers** on list — too volatile.
- **L2 cache on Flight entity**: do **not**. High write rate creates invalidation churn.

**Client-side (Signal Store, per S-006):**
- **Flights list store**: `withEntities` + paginated. Refetch policy: **on visibility** (route re-open), **on mutation** (after create/edit/delete). Don't refetch on timer. Optimistic update on edit; **do not** optimistic-add on create (server assigns ID + may run validation).
- **Aircraft / Person / Location reference stores**: cache-long, per S-006 ("master data cache-long").
- **Single-flight (edit) store**: load on route entry; clear on route exit. No cross-route caching.
- **Don't cache list across `clubId` switches** (system-admin impersonation): key by `clubId` or wipe on switch — silent cross-tenant cache bleed is the worst class of bug.

### Latency budget

Anchored to NFR (read p95 < 500ms, page-load p95 < 3s) and S-108 baseline:

- **`POST /flights/page`**: p95 < **250ms** server-side at page=50, typical filter. (NFR 500ms; this is the dominant route and 3s page budget has to absorb fan-out + render.)
- **`GET /flights/{id}`**: p95 < **150ms**. PK lookup + bounded batch fetch.
- **`POST /flights`**: p95 < **300ms**. One INSERT + audit + version + serialize.
- **`PUT /flights/{id}`**: p95 < **300ms**. Validation + UPDATE + audit + version check. 412 path < 50ms.
- **`POST /flights/copy/{id}`**: p95 < **400ms**. Reads full graph then inserts.

Fix points if budgets miss: index gaps; N+1 in detail; missing batch-fetch-size; synchronous audit-log writes that should be batched.

### Memory considerations

- **List endpoint**: page cap 100; ~1KB/DTO; 100KB/req — no heap pressure. Legacy "all overviews" 10000 path → port as separate dashboard endpoint with **hard cap 500 rows**.
- **Detail endpoint**: ~10KB. N/A streaming.
- **Copy endpoint**: one in, one out. N/A.
- **OGN ingestion concurrency**: write-at-a-time. Pressure on index updates, not heap.

### Performance test plan

1. **Hibernate query-count assertion in tests** (cheapest N+1 signal):
   - List p=50: `queryExecutionCount == 1` (DTO projection) OR `<= 3` (count + batched crew).
   - Detail: `<= 4` (entity + crew batch + in/outbound points + tow crew).
   - Create/edit: `<= 3` (load existing + write + audit).
   - Any spike fails CI.

2. **k6 load test** (extend S-108; consumed by S-111):
   - 10 VUs, ramp 0→10 over 30s, hold 5 min, list endpoint with mixed filters.
   - Pass: p95 < 250ms; p99 < 500ms; error < 0.1%.
   - Repeat with 1 rps writer to simulate OGN. p95 ≤ 350ms acceptable under contention; **fail if > 500ms**.

3. **Postgres `EXPLAIN (ANALYZE, BUFFERS)`** on list against 50k-flight tenant. Look for: `Seq Scan on flight` (fail), `Sort` step (fail — wrong index order).

4. **Cold-cache page-load** with Playwright + browser-perf-trace: navigate `/dashboard` → `/flights`. **LCP p95 < 3s on throttled Fast 3G**.

5. **Heap + connection-pool monitoring during k6**: JVM peak heap < 50% allocated; no thread-blocked-on-DB-connection events. Sizes HikariCP correctly.

### Stress scenario — busy Saturday

10 operators paging list simultaneously while OGN writes 1 flight every 5–10s + 1–2 operators editing. Risks:

- **Index-write contention** on `flight(operating_club_id, flight_date)`: acceptable; OGN rate is low.
- **Read consistency** (default `READ COMMITTED`): list snapshot internally consistent; refetch picks up new row. Signal Store refetch-on-visibility handles naturally.
- **Optimistic-concurrency 412 frequency** (S-067): correct behavior — surface as "this flight was just edited" toast with refresh action. **Don't retry blindly.**
- **HikariCP sizing**: 10 readers + 2 writers + OGN + jobs need pool ≥ 25. Revisit per S-111.
- **Audit-log write amplification**: ~5 mutations/min at peak is trivial. Audit must be in same tx as mutation (correctness) but verify the audit table has only the indexes it needs.

## Client form mechanics

Focused second-pass output from a `frontend-form-engineer` specialist. The first refinement pass covered architecture / security / tests / performance at 30,000 ft; this section is the field-by-field client-form state machine. The implementer should be able to build the Angular Reactive Form directly from this spec without re-reading the AngularJS legacy.

### Form structure

```ts
// Legacy DTO shape (preserved in modern form):
//   FlightDetails { FlightId, FlightDate, StartType, CanUpdateRecord, CanDeleteRecord,
//                   GliderFlightDetailsData, TowFlightDetailsData (nullable when !needsTowplane) }
// Crew fields are flat scalars on each *DetailsData block, NOT a nested crew array
// (FlightsController.js:425-427, 516, 529, 543, 557; flight-edit-glider-form.html:73,141,163,190,209,228).
// So the typed form mirrors that shape — *not* a nested per-crew-member group.

type FlightForm = FormGroup<{
  flightId:  FormControl<string | null>;          // hidden; null on create
  flightDate: FormControl<Date | null>;           // top-level (flight-edit-form.html:17-20)
  startType:  FormControl<number | null>;         // top-level (FlightsController.js:198, 666-673)

  canUpdateRecord: FormControl<boolean>;          // server-supplied permission (FlightService.cs:1741-1770)
  canDeleteRecord: FormControl<boolean>;

  glider: FormGroup<GliderFlightForm>;            // always present
  tow:    FormGroup<TowFlightForm>;               // present iff startType === Towing (FlightsController.js:418-420, 666-673)
}>;

type GliderFlightForm = {
  aircraftId:        FormControl<string | null>;
  flightTypeId:      FormControl<string | null>;
  pilotPersonId:     FormControl<string | null>;
  coPilotPersonId:   FormControl<string | null>;
  instructorPersonId:FormControl<string | null>;
  observerPersonId:  FormControl<string | null>;
  passengerPersonId: FormControl<string | null>;
  winchOperatorPersonId: FormControl<string | null>;

  startLocationId:   FormControl<string | null>;
  ldgLocationId:     FormControl<string | null>;
  outboundRoute:     FormControl<string | null>;
  inboundRoute:      FormControl<string | null>;

  startTime:         FormControl<string | null>;  // HH:mm
  ldgTime:           FormControl<string | null>;
  duration:          FormControl<string | null>;  // derived; also editable (FlightsController.js:738-743)
  noStartTimeInformation: FormControl<boolean>;
  noLdgTimeInformation:   FormControl<boolean>;

  nrOfLdgs:          FormControl<number | null>;
  engineStartOperatingCounterInSeconds: FormControl<number | null>;
  engineEndOperatingCounterInSeconds:   FormControl<number | null>;
  engineDurationSeconds: FormControl<number | null>; // computed mirror

  flightCostBalanceType:     FormControl<number | null>;
  invoiceRecipientPersonId:  FormControl<string | null>;
  couponNumber:              FormControl<string | null>;
  flightComment:             FormControl<string | null>;

  isSoloFlight:      FormControl<boolean>;        // auto-derived (FlightsServices.js:75-98)
};

type TowFlightForm = {
  aircraftId:        FormControl<string | null>;
  pilotPersonId:     FormControl<string | null>;
  flightTypeId:      FormControl<string | null>;

  // startLocationId / startDateTime / outboundRoute are MIRRORS of glider's values
  // (FlightsController.js:370-372). Modeled as disabled controls bound to a computed signal.
  startLocationId:   FormControl<string | null>;
  startTime:         FormControl<string | null>;
  outboundRoute:     FormControl<string | null>;

  ldgLocationId:     FormControl<string | null>;
  ldgTime:           FormControl<string | null>;
  duration:          FormControl<string | null>;
  noLdgTimeInformation: FormControl<boolean>;

  nrOfLdgs:          FormControl<number | null>;
  inboundRoute:      FormControl<string | null>;
  flightComment:     FormControl<string | null>;
};
```

**Notes:**
- No per-crew-member nested FormGroup in legacy. If new server API normalizes to a `crew[]` collection (per S-058), do that mapping in the API client, **not** in the form.
- `version` for optimistic concurrency lives in the FlightStore alongside the form, not on the form itself (S-067).

### Field-by-field rules

| Field | Required when | Visible when | Disabled when | Default | Notes / legacy cite |
|---|---|---|---|---|---|
| `flightDate` | always (HTML `required`) | always | `!canUpdateRecord` | new: `today` if no StartDateTime; copy: `res.FlightDate` | `flight-edit-form.html:17-20`; `FlightService.cs:1075-1076` |
| `startType` | always (server `:1096-1097`) | always | `!canUpdateRecord` | `flightDetails.StartType` ‖ `myClub.DefaultStartType` ‖ `"1"` | `FlightsController.js:198, 666-673` |
| `glider.flightTypeId` | server (`:1099-1100`); no client `ng-required` | always | `!canUpdateRecord` | `myClub.DefaultGliderFlightTypeId` | `flight-edit-glider-form.html:87-98`; `FlightsController.js:202` |
| `glider.aircraftId` | client (HTML `required`); server (`:1078-1079`) | always | `!canUpdateRecord` | none | `flight-edit-glider-form.html:17-30` |
| `glider.pilotPersonId` | server (`:1081-1082`); no client `required` | always | `!canUpdateRecord` | none | `flight-edit-glider-form.html:63-73` |
| `glider.coPilotPersonId` | never | `!isSoloFlight && !flightType.IsPassengerFlight && !flightType.InstructorRequired` | `!canUpdateRecord` | none; **auto-cleared** when `isSoloFlight==true` | `flight-edit-glider-form.html:173-191`; `FlightsController.js:425-427` |
| `glider.instructorPersonId` | `flightType.InstructorRequired` (visibility implies it) | `flightType.InstructorRequired` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:192-210` |
| `glider.observerPersonId` | `ng-required="flightType.ObserverPilotOrInstructorRequired"` | same | `!canUpdateRecord` | none | `flight-edit-glider-form.html:123-143` |
| `glider.passengerPersonId` | client: not enforced; intent: required when `IsPassengerFlight` | `flightType.IsPassengerFlight` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:145-172` |
| `glider.winchOperatorPersonId` | server: `startType==WinchLaunch` (`:1024-1030`); client: visibility only | `startType.IsWinchStart` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:211-229` |
| `glider.startLocationId` | server (`:1090-1091`) | always | `!canUpdateRecord` | `localStorage.lastStartLocation` ‖ `myClub.HomebaseId` | `FlightsController.js:200` |
| `glider.ldgLocationId` | server (`:1093-1094`) | always | `!canUpdateRecord` | same chain; **mirrored** when `startLocationId` changes | `FlightsController.js:201, 650` |
| `glider.outboundRoute` | `startLocation.IsOutboundRouteRequired`; server `:1112-1123` checks against allow-list | `isOutboundRouteRequired` | `!canUpdateRecord` | none; "copy from last" reads `lastGliderOutbound` | `FlightsController.js:217-219, 703-704` |
| `glider.inboundRoute` | `landingLocation.IsInboundRouteRequired`; server `:1125-1135` | `isInboundRouteRequired` | `!canUpdateRecord` | none; "copy from last" reads `lastGliderInbound` | `FlightsController.js:704` |
| `glider.startTime` | server when `!noStartTimeInformation` (`:1084-1085`) | always | `!canUpdateRecord ‖ noStartTimeInformation` | none; "now" button → current time | `FlightsController.js:716-720, 808-812` |
| `glider.ldgTime` | server when `!noLdgTimeInformation` (`:1087-1088`) | always | `!canUpdateRecord ‖ noLdgTimeInformation` | none | `FlightsController.js:731-736, 814-817` |
| `glider.duration` | never (derived) | always | `!canUpdateRecord` | computed; editing back-computes ldg | `FlightsController.js:601-617, 738-743` |
| `glider.noStartTimeInformation` | n/a | always | `!canUpdateRecord` | `false` | toggle clears `startTime`; **also sets `tow.NoStartTimeInformation`** `FlightsController.js:808-812` |
| `glider.noLdgTimeInformation` | n/a | always | `!canUpdateRecord` | `false` | `FlightsController.js:814-817` |
| `glider.nrOfLdgs` | server: required iff `ldgTime` set (`:1102-1109`); `@Min(1)` | always | `!canUpdateRecord` | `1` on new; `1` on first `ldgTime` blur if unset | `FlightsController.js:203, 727` |
| `glider.engineStartOperatingCounterInSeconds` | never client | `selectedGliderAircraft.HasEngine` | `!canUpdateRecord` | reset on aircraft change when `resetEngineOperatingCounters=true` | `FlightsController.js:115-116, 128-136` |
| `glider.engineEndOperatingCounterInSeconds` | never client | `selectedGliderAircraft.HasEngine` | `!canUpdateRecord` | reset on aircraft change | `flight-edit-glider-form.html:418-429` |
| `glider.engineDuration` (computed) | never | `selectedGliderAircraft.HasEngine` | `!canUpdateRecord` | computed `end - start`, floored at 0 | `FlightsController.js:767-785` |
| `glider.flightCostBalanceType` | `ng-required="flightType.IsFlightCostBalanceSelectable"` | same | `!canUpdateRecord` | `1` on new | `flight-edit-glider-form.html:450-468`; `FlightsController.js:192` |
| `glider.invoiceRecipientPersonId` | `ng-required="PersonForInvoiceRequired"` | `PersonForInvoiceRequired && flightType.IsFlightCostBalanceSelectable` | `!canUpdateRecord` | none; **cleared** when `PersonForInvoiceRequired` becomes false | `FlightsController.js:562-573` |
| `glider.couponNumber` | never | `flightType.IsCouponNumberRequired` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:494-502` |
| `glider.flightComment` | never | always | `!canUpdateRecord` | none | `flight-edit-glider-form.html:442-449` |
| `glider.isSoloFlight` | n/a (derived) | always (icon) | `!flightTypeCheckbox.isChangingAllowed ‖ !canUpdateRecord` | derived: `flightType.IsSoloFlight→true`; `IsPassengerFlight→false`; else preserve | `FlightsServices.js:75-98`; `FlightsController.js:111-124, 575-581` |
| `tow.aircraftId` | server-required when tow validated; client: no `required` | `startType==Towing` | `!canUpdateRecord` | `lastTowAircraftId` (copy-button only) | `FlightsController.js:147-152` |
| `tow.pilotPersonId` | server (`:1081-1082` on tow row) | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `towPilotByAircraftId[aircraftId]` localStorage (copy-button) | `FlightsController.js:147-152, 350-352` |
| `tow.flightTypeId` | server (`:1099-1100` on tow) | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `myClub.DefaultTowFlightTypeId` (set on aircraft selection) | `FlightsController.js:159` |
| `tow.startTime` | (server when `!noStartTimeInformation`) | `startType==Towing` | **always disabled** (mirrors `times.gliderStart`) | mirror of glider | `flight-edit-tow-form.html:95-101`; `FlightsController.js:370` |
| `tow.startLocationId` | server (`:1090-1091`) | `startType==Towing` | **always disabled** (`ng-disabled="true"`); mirrors glider | mirror of glider; default `myClub.HomebaseId` | `flight-edit-tow-form.html:145-157`; `FlightsController.js:205-206, 371, 650-654` |
| `tow.outboundRoute` | server (when required) | `isOutboundRouteRequired` | **always disabled**; mirrors glider | mirror | `flight-edit-tow-form.html:188-197`; `FlightsController.js:372` |
| `tow.ldgLocationId` | server (`:1093-1094`) | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `myClub.HomebaseId` (via `resetTowFlightDefaults`) | `FlightsController.js:158` |
| `tow.ldgTime` | server when `!noLdgTimeInformation` | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId ‖ noLdgTimeInformation` | none | `FlightsController.js:745-758, 819-822` |
| `tow.noLdgTimeInformation` | n/a | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `false` | **not** mirrored from glider | `FlightsController.js:819-822` |
| `tow.duration` | never | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | derived | `FlightsController.js:760-765` |
| `tow.nrOfLdgs` | server: required iff tow `ldgTime` set | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `1` via `resetTowFlightDefaults`; `1` on first `formatTowLanding()` if unset | `FlightsController.js:160, 749` |
| `tow.inboundRoute` | server (when required) | `isInboundRouteForTowFlightRequired` | `!canUpdateRecord ‖ !tow.aircraftId` | none; "copy from last" reads `lastTowInbound` | `FlightsController.js:705` |
| `tow.flightComment` | never | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | none | `flight-edit-tow-form.html:225-233` |

**No `accountingRemark` field exists** in legacy glider/tow forms (grep confirmed). Drop from scope or flag as a new requirement — see Open question #8.

### Cross-field reactive rules

- **`startType` changes** → if Towing and `TowFlightDetailsData == null`, create empty `tow` block. Recompute `selectedStartType` for `IsWinchStart` visibility. `FlightsController.js:418-420, 666-673`.
- **`startType` changes to non-Towing** → tow block hidden via `ng-if="needsTowplane"` (`flight-edit-tow-form.html:2`); tow data **kept in memory**, stripped at submit (`prepareForSaving :375-377`).
- **`glider.flightTypeId` changes** → recompute `selectedFlightType` → re-derive solo via `SoloFlightCheckboxEnablementCalculator.getSoloFlightCheckbox(...)` (`FlightsController.js:430-441`; `FlightsServices.js:75-98`):
  - `flightType.IsSoloFlight==true` → force `isSoloFlight=true`, checkbox `CHECKED`, not changeable.
  - `flightType.IsPassengerFlight==true` → force `isSoloFlight=false`, checkbox `UNCHECKED`, not changeable.
  - else → preserve existing, checkbox toggleable.
  - Also recompute `warnNumberOfSeatsInsufficientForFlightType` (`:583-588`).
- **`isSoloFlight` toggles to `true`** → `coPilotPersonId = undefined` (`FlightsController.js:425-427`).
- **`glider.aircraftId` changes** → set `selectedGliderAircraft`, `gliderCompetitionSign`. If `NrOfSeats===1 && !IsSoloFlight` → force `IsSoloFlight=true`. If `HasEngine` → fetch `AircraftOperatingCounters`, refresh `lastOperatingCounterFormatted`. Optionally clear engine counters. Recompute seat warning. (`FlightsController.js:110-145`.)
- **`glider.flightCostBalanceType` changes** → set `$scope.PersonForInvoiceRequired`. If false, **clear `glider.invoiceRecipientPersonId`** (`FlightsController.js:562-573`).
- **`tow.aircraftId` changes** → set `towplaneRegistration`. Run `resetTowFlightDefaults`: fill `tow.startLocationId / ldgLocationId / flightTypeId` from `myClub` defaults if empty; `tow.nrOfLdgs = 1`. (`FlightsController.js:163-188, 155-161`.)
- **`glider.startLocationId` changes** → `glider.ldgLocationId = glider.startLocationId` (overwrite); also mirror to `tow.startLocationId` and `tow.ldgLocationId`; recompute route requirements. (`FlightsController.js:649-656`.)
- **`glider.ldgLocationId` changes** → recompute `isInboundRouteRequired` (`FlightsController.js:658-660, 704`).
- **`tow.ldgLocationId` changes** → recompute `isInboundRouteForTowFlightRequired` (`FlightsController.js:662-664, 705`).
- **Location lookups resolve** → load `outboundRoutes` / `inboundRoutes` lists via `RoutesPerLocation` for selectize options (`FlightsController.js:690-701`).
- **`glider.noStartTimeInformation` toggles** → clear `times.gliderStart`; **propagates to `tow.NoStartTimeInformation`** (`FlightsController.js:808-812`). Asymmetric: glider landing toggle does *not* propagate (`:814-817`).
- **`glider.startTime` blur** → recompute `times.gliderDuration` and `times.towingDuration` (both anchored to glider start) (`FlightsController.js:709-714`).
- **`glider.ldgTime` blur** → recompute `gliderDuration`; **default `nrOfLdgs=1` if unset** (`FlightsController.js:723-729`).
- **`glider.duration` blur** → back-compute `times.gliderLanding = start + duration` (`FlightsController.js:738-743`).
- **`tow.ldgTime` blur** → recompute `towingDuration`; default `tow.nrOfLdgs=1` if unset (`FlightsController.js:745-751`).
- **`tow.duration` blur** → back-compute `times.towingLanding` (`FlightsController.js:760-765`).
- **Engine counters blur** → recompute `engineSecondsCounterDuration = max(0, end - start)` (`FlightsController.js:767-777`). **`engineDuration` blur** → recompute `engineEnd = engineStart + duration` (`FlightsController.js:779-785`).
- **Glider start + tow landing both valid** → `warnTowFlightLongerThanGliderFlight = gliderDuration < towDuration` (`FlightsController.js:590-599`). Warning only.

### Visibility-mode matrix

`flightAircraftType`: 1 = GliderFlight, 2 = TowFlight (derived, never user-edited), 4 = MotorFlight (separate route under `airmovements/`, out of S-062).

| `startType` | `aircraftType` | Glider block | Tow block | Engine counters (glider) | Winch operator | Notes |
|---|---|---|---|---|---|---|
| Towing (1) | GliderFlight | shown | shown | iff `glider.HasEngine` | hidden | Default; `needsTowplane=true` (`FlightsController.js:418-420`). Tow row created+linked. |
| WinchLaunch (2) | GliderFlight | shown | hidden | iff `glider.HasEngine` | shown + server-required (`:1024-1030`) | `flight-edit-tow-form.html:2` `ng-if="needsTowplane"` hides tow column. |
| SelfStart (3) | GliderFlight | shown | hidden | iff `glider.HasEngine` | hidden | Self-launching motor glider — engine block usually applies. |
| ExternalStart (4) | GliderFlight | shown | hidden | iff `glider.HasEngine` | hidden | Server validates **no** tow linked (`:1017-1022`). |
| MotorFlightStart (5) | GliderFlight | shown | hidden | iff `glider.HasEngine` | hidden | Unusual; server accepts (`:1036-1039`). |
| any | MotorFlight | **N/A — separate route** `/airmovements/...` owned by **S-064**. Form structurally different. | | | | flag boundary. |

Engine-counter visibility is `glider.HasEngine`, independent of `startType` (`flight-edit-glider-form.html:394, 419, 431`).

### Disabled-state rules

- **Whole form**: disabled when `!flightDetails.CanUpdateRecord` (server-supplied). Derivation: `processState >= Locked && (!IsClubAdministrator || processState == DeliveryBooked)` → false; else true. Source: `FlightService.cs:1741-1770` (`SetFlightDetailsSecurity`); mirrored on overviews at `:1675-1687`.
- **Legacy server gap (documented in story §Security)**: `UpdateFlightDetails` only hard-blocks `DeliveryBooked` (`:1276-1280`). `Locked`/`DeliveryPrepared` rely on client `CanUpdateRecord`. New server closes this — see refinement Security plan.
- **Delete button**: gated by `CanDeleteRecord`; same derivation. `DeliveryBooked` rejects at server (`:1308-1312`).
- **Tow sub-controls extra gate**: `tow.pilotPersonId`, `tow.flightTypeId`, `tow.ldgTime`, `tow.ldgLocationId`, `tow.nrOfLdgs`, `tow.inboundRoute`, `tow.flightComment`, `tow.duration` are additionally disabled when `!tow.aircraftId`. Cite: every `ng-disabled` in `flight-edit-tow-form.html:64, 90, 109, 135, 175, 185, 206, 220, 231`.
- **`tow.startLocationId` / `tow.startTime` / `tow.outboundRoute`**: **always** disabled (`ng-disabled="true"` / `disabled`) — mirrored from glider at submit (`flight-edit-tow-form.html:155, 100, 195`).
- **Time fields gated by their "no info" flag**: `glider.startTime` disabled when `noStartTimeInformation`; same for landing. (`flight-edit-glider-form.html:237, 263`; `flight-edit-tow-form.html:109`.)
- **Role-driven** (overriding above): `IsClubAdministrator` users can edit `Locked` / `DeliveryPrepared` / `DeliveryPreparationError` / `ExcludedFromDeliveryProcess` flights — everything except `DeliveryBooked`. Already encoded in `CanUpdateRecord`; SPA needs no separate role check.

### Default-value derivation

**New flight** (`/flights/new` → `newFlight()` then `initForNewFlight()`, `FlightsController.js:190-215, 225-230`):

- `flightDetails.GliderFlightDetailsData.FlightCostBalanceType` ← `1` (`:192`).
- `flightDetails.CanUpdateRecord` ← `true` (`:193`).
- `flightDetails.StartType` ← `existing ‖ myClub.DefaultStartType ‖ "1"` (`:198`).
- `glider.startLocationId` ← `existing ‖ localStorage.lastStartLocation ‖ myClub.HomebaseId` (`:200`).
- `glider.ldgLocationId` ← same chain (`:201`).
- `glider.flightTypeId` ← `existing ‖ myClub.DefaultGliderFlightTypeId` (`:202`).
- `glider.nrOfLdgs` ← `1` (`:203`).
- `tow.startLocationId` / `tow.ldgLocationId` ← `existing ‖ localStorage.lastStartLocation ‖ myClub.HomebaseId` (`:205-206`).
- `flightDate` ← `gld.StartDateTime ‖ gld.FlightDate ‖ (FlightId? unchanged : new Date())` (`FlightsController.js:335`).
- `tow.flightTypeId` ← `myClub.DefaultTowFlightTypeId` (deferred to `resetTowFlightDefaults`, `:159`).
- `tow.nrOfLdgs` ← `1` (deferred, `:160`).

**Copy** (`/flights/copy/:id`): see refinement §"AC4 — Copy-flight". Preserved: `FlightDate`, `StartType`, both `*DetailsData` blocks (mostly). Cleared: `FlightId`, both `StartDateTime`/`LdgDateTime`, `FlightComment`, `CouponNumber`, both engine counters. Then `initForNewFlight` runs on top.

**localStorage hydration** (workstation-scoped UX convenience):

- `lastTowAircraftId` (written at save `:353`) + `towPilotByAircraftId[aircraftId]` (`:350-352`) — hydrated **only on explicit "copy from last" button click** in tow aircraft field (`flight-edit-tow-form.html:36-38` → `copyTowingFromLast` at `:147-152`). Not auto-applied on form load.
- `lastStartLocation` (written `:359`) — **auto-hydrated** as default for both glider start/ldg and tow start/ldg on new flight (`:200-201, 205-206`).
- `lastGliderOutbound`, `lastGliderInbound`, `lastTowOutbound`, `lastTowInbound` — written on save (`:354-358`); hydrated only via per-field "copy from last" history button (`copyRouteFromLast` `:217-219`).

### Submit-time transformations

`prepareForSaving(flightDetails)` (`FlightsController.js:348-378`):

1. **Persist localStorage** for next session: write `towPilotByAircraftId[towAircraftId]`, `lastTowAircraftId`, `lastTowOutbound/Inbound`, `lastGliderOutbound/Inbound`, `lastStartLocation` (`:348-359`).
2. **Compose datetimes** from `flightDate` + `times.gliderStart/gliderLanding/towingLanding` into ISO datetimes (`:364-366, 373`).
3. **Glider→tow sync** (always, before discard check): `tow.StartDateTime = glider.StartDateTime`; `tow.StartLocationId = glider.StartLocationId`; `tow.OutboundRoute = glider.OutboundRoute` (`:370-372`).
4. **Tow discard**: if `!needsTowplane(startType) || !tow.AircraftId` → `flightDetails.TowFlightDetailsData = undefined` (`:375-377`). Partial tow data the user filled in is dropped.

`mapFlightToForm(result)` on **load** — reverse-direction normalization (`:317-346`):

5. **Empty-Guid normalization**: `tow.AircraftId == '00000000-0000-0000-0000-000000000000'` → `""`; same for `tow.PilotPersonId` (`:319-324`). Server returns empty Guid for "no value"; UI treats `""` so selectize shows no selection.

`flightTypeChanged()` / `flightCostBalanceTypeChanged()` / `recalcCheckboxState()` — applied at edit time, also takes effect before save:

6. **CoPilot clear when solo** — `flightTypeCheckbox.state === 'CHECKED'` → `glider.CoPilotPersonId = undefined` (`:425-427`).
7. **InvoiceRecipient clear when not required** — `PersonForInvoiceRequired` flips false → `glider.InvoiceRecipientPersonId = undefined` (`:562-573`).
8. **`IsSoloFlight` force-set** by aircraft seat count (`NrOfSeats===1 && !IsSoloFlight` → `true`) on aircraft change (`:121-124`).

What the form **does not** strip but **should** under the new API: `processState`, `operating_club_id`, `owner_id`, `validation_errors`, `version`, audit columns — must be excluded from Create/Update DTOs at the wire (refinement §Security plan).

### Recommended Angular implementation

**Required-when patterns.** Single `effect()` re-derives validators from a `formValue` signal. Avoid `Validators.required` in the initial builder for conditionally-required fields — set dynamically. Use `setValidators` + `updateValueAndValidity({emitEvent:false})` to avoid re-trigger loops.

```ts
effect(() => {
  const ft = flightTypeSig();          // computed from form value + flightTypes signal
  const required = !!ft?.observerPilotOrInstructorRequired;
  const c = form.controls.glider.controls.observerPersonId;
  c.setValidators(required ? [Validators.required] : []);
  c.updateValueAndValidity({ emitEvent: false });
});
```

**Visible-when patterns.** Drive template via `computed()` signals over `formValue = toSignal(form.valueChanges, { initialValue: form.getRawValue() })`. Template uses `@if`. **Disable hidden controls** so they don't contribute to validity.

```ts
readonly showWinchOperator = computed(() => this.selectedStartType()?.isWinchStart === true);
readonly showInstructor    = computed(() => this.selectedFlightType()?.instructorRequired === true);
readonly showCoPilot       = computed(() =>
  !this.formValue().glider.isSoloFlight
  && !this.selectedFlightType()?.isPassengerFlight
  && !this.selectedFlightType()?.instructorRequired);
```

Pair each visibility computed with an `effect` that enables/disables the control. Reactive form `[disabled]` binding is a footgun; use `control.disable({emitEvent:false})`.

**Cross-field value derivation.** Coordinator pattern as plain TS (per refinement §Module layout — `FlightFormCoordinator` lives in `pages/flight-edit/flight-form-coordinator.ts`, no Angular DI). Subscribes to `valueChanges` of specific controls and writes through.

```ts
// FlightFormCoordinator.attach(form) — called once from FlightEditComponent.ngOnInit()
form.controls.glider.controls.startLocationId.valueChanges
   .pipe(takeUntilDestroyed(destroyRef))
   .subscribe(id => {
     form.controls.glider.controls.ldgLocationId.setValue(id, { emitEvent: false });
     form.controls.tow?.controls.startLocationId.setValue(id, { emitEvent: false });
     form.controls.tow?.controls.ldgLocationId.setValue(id, { emitEvent: false });
   });
```

Same pattern for: `flightTypeId` → solo derivation + `coPilotPersonId` clear; `aircraftId` → force-solo-if-1-seat + reset engine counters; `flightCostBalanceType` → toggle `personForInvoiceRequired` + clear invoice recipient when needed; `noStartTimeInformation` → propagate to tow.

**Disabled-state binding.** Server's `canUpdateRecord` flag flows through FlightStore. On detail load, after `form.patchValue(dto)`, call `form.disable({emitEvent:false})` when `!canUpdateRecord`. For tow's per-field `!tow.aircraftId` gate, an `effect` toggles per-control enable/disable:

```ts
effect(() => {
  const towEnabled = !!this.formValue().tow?.aircraftId && this.canUpdate();
  for (const key of ['pilotPersonId','flightTypeId','ldgTime','ldgLocationId',
                     'nrOfLdgs','inboundRoute','flightComment','duration']) {
    const c = (form.controls.tow.controls as any)[key];
    towEnabled ? c.enable({emitEvent:false}) : c.disable({emitEvent:false});
  }
});
```

`tow.startLocationId` / `tow.startTime` / `tow.outboundRoute` get permanently `.disable()`'d in the builder.

**Where `FlightFormCoordinator` plugs in.** Instantiated once by `FlightEditComponent.ngOnInit()`, given the `FormGroup` + masterdata signals (`gliderAircrafts`, `gliderFlightTypes`, `flightCostBalanceTypes`, `myClub`). Owns: (a) all `valueChanges` subscriptions for cross-field derivations; (b) the submit-time transformation pipeline (`prepareForSaving` equivalent); (c) "copy from last" actions (delegates to a thin `LocalStoragePreferences` service). The coordinator does **not** own visibility computeds — those live on the component because templates bind to them directly. The coordinator only writes form values; visibility is a pure projection.

## Open design questions

These specialists' analyses disagreed or surfaced forks the operator must resolve. Each blocks final execution.

1. **Split shape (5 sub-stories vs. 3 sub-stories vs. don't split).** Both requirements-engineer and solution-architect recommend splitting; their proposed shapes differ:
   - **Architect (3-way)**: S-062a backend+validator, S-062b list page, S-062c forms+copy.
   - **Requirements (5-way)**: S-062a backend CRUD, S-062b validator port, S-062c glider form + list, S-062d tow form + copy, S-062e parity spec wiring.
   - **Don't split**: ship as a single L, accept large PR.
   - The 3-way bundles validator into backend; the 5-way separates it because the validator port is parity-critical and worth isolating. Operator pick.

2. **Motor flight form: in scope for S-062 or deferred?** Legacy `FlightDetails` includes `MotorFlightDetailsData` and `/motorflights/*` endpoints. Architect's view: out — covered by S-064 air-movements. Requirements flagged as ambiguous. If in, the form-component story doubles; if out, S-064 must wire the third form against this story's backend.

3. **Eager or deferred validation on create?** Legacy persists in `NotProcessed` even with missing pilot/aircraft (validation is an async workflow). New could reject 400 at create. Either choice is a behavior change relative to the other (preserve = ship known weakness; reject = potentially breaks OGN ingestion).

4. **Server-side re-check of `CanUpdateRecord` on PUT/DELETE.** Legacy has a known gap (only `DeliveryBooked` is hard-blocked at the server; `Locked`/`DeliveryPrepared` rely on client-side `CanUpdateRecord` flag). Security plan above closes the gap. Confirm or ship the legacy weakness intact.

5. **Copy endpoint: server-side `GET /{id}/copy-template` vs. client-side cloning.** Legacy is client-side. Architect recommends server-side (defaults stay versioned with API). Affects: extra round-trip on copy click; QA test placement (server-side moves tests to integration layer); SPA complexity.

6. **DTO shape: nested-by-discriminator (`{glider, tow, motor}` with three optional fields, mirrors legacy) vs. discriminated union (`{flightType, details: <union>}`, idiomatic for OpenAPI codegen).** ADR 0005's codegen evaluation flagged "discriminated unions for `FlightAircraftType`" as a criterion. Picking discriminated union means a tag field on the wire; picking nested means a portable shape that's awkward in TS unions.

7. **`FlightStateMapper` enum drift (R5).** Both `FlightProcessState` (stored) and `FlightAirState` (computed) flow to the SPA. The new system should derive these from the generated OpenAPI client (closing R5). Confirm `FlightAirState` is included in the OpenAPI spec as an enum, not stringified ad-hoc.

8. **`accountingRemark` field — does it exist?** The legacy form (glider + tow templates) has no `accountingRemark` control (grep of `flsweb/src/flights/*.html` returns nothing). Surfaced by the form-mechanics pass. Decide: drop from scope (legacy parity) or add as a new requirement (would need a new column on `flight` table + new validation rules).

9. **Route allow-list pre-validation client-side.** Server validates `outboundRoute`/`inboundRoute` values against the location's `InOutboundPoints` allow-list case-insensitively (`FlightService.cs:1118-1121, 1131-1134`). Client currently shows autocomplete from the list but accepts free text. Decide: pre-validate client-side (better UX; saves a round-trip on rejection) or keep the legacy free-text behavior. Related to Open question 3 (eager-vs-deferred validation).

10. **Server-required-but-no-client-`required` fields.** Four fields are required by the server but have no client-side `ng-required` attribute in legacy: `glider.flightTypeId`, `glider.pilotPersonId`, `tow.flightTypeId`, `tow.pilotPersonId`. Legacy lets the form save without them — the flight just lands in `NotProcessed`. Aligns with Open question 3. If new system chooses eager rejection at create, these need `Validators.required` client-side too — otherwise the SPA's "save" button enables but the POST returns 400.

11. **`tow.noStartTimeInformation` modeling.** The flag is set indirectly via glider toggle propagation (`FlightsController.js:810`) but has no dedicated UI control on the tow form. Decide: model it as a hidden derived control on the new tow form (mirrors glider), or simply re-compute it on the server from the parent flight's `noStartTimeInformation` at write time. Hidden derived is closer to legacy; server-side computation is cleaner contract.

<!-- modernize-refine: end -->
