---
id: S-062a
title: Flight CRUD backend + DTOs + validator port
epic: E-07
status: todo
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
parity_test: tests/flights/04-flights-create.spec.ts (depends on S-062c for full coverage; S-062a green when API contract IT (FlightDtoContractIT) is green)
refined: true
refined_at: 2026-05-14
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
split_from: S-062
---

## Context

First of three sub-stories splitting the original S-062 (see [S-062b](S-062b-flight-list-page.md) and [S-062c](S-062c-flight-edit-forms.md)). This story stands the Flight REST API up with no UI — Swagger UI is the integration surface. Once green, S-062b builds the list against this API and S-062c builds the create/edit forms.

The validator port is the parity-critical piece. Bundling it here keeps "one reviewable PR per stack" — backend reviewers see CRUD + validator + DTO mapping in one diff.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] DTOs: `FlightDetailDto`, `FlightListItemDto`, `FlightCreateDto`, `FlightUpdateDto`, `FlightCopyDto`, `FlightSearchFilterDto`, `ValidationErrorDto`.
- [ ] `FlightMapper` (entity ↔ DTO; MapStruct or manual). Discriminator derivation from populated `*Details` block.
- [ ] `FlightController` with the seven endpoints.
- [ ] `FlightApplicationService` — owns dual-row tx. Methods: `create`, `update`, `delete`, `copy`, `newTemplate`.
- [ ] `FlightValidator` — port of `ValidateFlightBasics` + start-type-specific arms (`FlightService.cs:987-1039`).
- [ ] `FlightCopyService.copyFrom(Flight)` — clone-minus-identity (server-side mirror of `FlightsController.js:232-255`).
- [ ] `FlightFactory.newFlightTemplate(startType, myClub)` — port of `initForNewFlight`.
- [ ] Audit-event emission (`flight.created`, `flight.updated`, `flight.deleted`, `flight.copied`) per the Security plan, including the paired-row case (two events sharing `request_id`).
- [ ] FK-tenancy resolvers: aircraft, flight type, locations resolved via tenant-scoped repos; tow row inherits `operating_club_id` from parent.
- [ ] Server-layer state-gate enforcement (close the legacy `Locked`/`DeliveryPrepared` gap — see Security plan).
- [ ] `If-Match` parsing + 412 mapping (placeholder until S-067 lands `@Version`).
- [ ] Unit tests: mapping, copy-reset, discriminator, FlightCrew aggregation (~22 tests; full list in Test plan).
- [ ] Integration tests: `FlightCreateControllerIT`, `FlightUpdateControllerIT`, `FlightCopyControllerIT`, `FlightDeleteControllerIT`, `FlightTenantScopingIT` (smoke), `FlightDtoContractIT`.
- [ ] Hibernate `Statistics.queryExecutionCount()` assertions per endpoint (forbid drift into per-row queries).

## Notes

**Estimate calibration (M):**
- ~7 endpoints + 1 application service + 1 validator + 1 mapper.
- ~22 unit + ~14 integration tests = ~36 tests.
- ~600 lines of legacy code ported (`FlightService.cs:917-1320, 1741-1770` + parts of `FlightsController.cs`).
- No new schema (S-013 owns it); no UI; no e2e (S-062b/c own those).

**Out of scope (deferred to other stories):**
- List endpoint pagination tuning (S-062b runs k6 against it; this story provides the endpoint).
- Form-driven UX flows (S-062c).
- `@Version` column + DB-level concurrency (S-067 — but `If-Match` is plumbed now).
- Async validation workflow trigger (`POST /flights/validate` — owned by `DailyFlightValidationJob` story S-083).
- Glider↔Tow cascade depth + concurrent-edit tests (S-063 implementation; S-105 depth).
- Validation-rejection-path depth (S-101).
- State-transition matrix coverage (S-059 + S-102).
- Permission matrix per endpoint × role (S-104).
- Cross-tenant catalog (S-024 + S-106).

**S-083 (DailyFlightValidationJob) reuses `FlightValidator` directly** — call it out in the validator interface so the job can pass a `Flight` from any tenant under `runUnscoped` without dragging in HTTP plumbing.

<!-- modernize-refine: start -->

## Design notes

### Module layout — server-side only

`ch.alpenflight.flight.*`:
- `web/FlightController.java` — REST controller, endpoints listed under API surface below.
- `service/FlightApplicationService.java` — orchestrates create/update/copy/delete. **Owns the dual-row transaction** (glider + tow rows in one `@Transactional`). Only place outside the tx boundary that writes Flight rows during CRUD.
- `service/FlightValidator.java` — port of `ValidateFlightBasics` plus the start-type-specific arms from `FlightService.cs:985-1039`. Pure function over `Flight` (no DB calls). Returns `List<ValidationError>`. Public API designed to be callable from S-083 (the daily job) without HTTP plumbing.
- `service/FlightCopyService.java` — clones a `Flight` minus identity, times, comments, counters (mirrors `FlightsController.js:232-255`).
- `service/FlightFactory.java` — builds the initial-state Flight from `myClub` defaults. Port of `initForNewFlight` (`FlightsController.js:190-215`) onto the server so SPA new-flight POST returns a populated draft via `GET /flights/new-template`.
- `mapper/FlightMapper.java` — entity ↔ DTO (MapStruct or manual).
- `dto/FlightDetailDto.java`, `dto/FlightListItemDto.java`, `dto/FlightCreateDto.java`, `dto/FlightUpdateDto.java`, `dto/FlightCopyDto.java`, `dto/FlightSearchFilterDto.java`, `dto/ValidationErrorDto.java`.

**No client work in this story.** Client services + signal store live in S-062b.

**DB: no new Flyway migrations.** Schema lives in S-013; `flight.version` for optimistic concurrency comes from S-067.

### Domain model

No new entities. The constraints S-062a must honor (already laid down by S-058):

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
- Lock/transition endpoints belong to S-059, not S-062a.

**DTO shape: two-tier.**
- `FlightListItemDto` — flat, ~15 fields (immatriculation, pilot name, dates, durations, computed air state, process state). Mirrors `FlightOverview`.
- `FlightDetailDto` — `{ id, flightDate, startType, version, glider: GliderFlightDetailsDto, tow: TowFlightDetailsDto | null, motor: MotorFlightDetailsDto | null }` — nested-by-discriminator. Mirrors legacy `FlightDetails`.
- `FlightCreateDto` / `FlightUpdateDto` — same as `FlightDetailDto` minus server-managed fields (`id`, `version`, `processState`, `airState`, `validationErrors`, audit metadata).

### Integration with other stories

**Inputs:**
- **S-058**: `Flight`, `FlightAircraftType`, `FlightCrew`, repository finders.
- **S-059**: `FlightProcessState` + `FlightStateService.transition(...)`. S-062a does **not** mutate `processState` directly; validation step sets `Valid`/`Invalid` inline (parity with `FlightService.cs:1041-1050`); every other transition routes through S-059.
- **S-060**: `FlightAirState` derivation; called from `FlightMapper` at serialization time.
- **S-013**: schema (no migration here).

**Outputs:**
- **S-062b** (list page): consumes `POST /flights/search` + `FlightListItemDto`.
- **S-062c** (forms + copy): consumes the rest of the endpoint set + `FlightDetailDto`.
- **S-063** (glider↔tow link integrity): consumes `FlightApplicationService.create/update` as integration point. S-062a must expose seams so S-063 can wire recursion + cascade-on-delete depth without refactor. Tests for those live in S-063.
- **S-067** (optimistic concurrency): S-062a plumbs `If-Match` end-to-end now; S-067 adds the `@Version` column + 412 mapping.
- **S-083** (daily validation job): consumes `FlightValidator` directly.
- **S-101** (validator depth tests): consumes the validator; S-101 adds depth.
- **S-102** (state transition expansion): S-062a only routes Valid/Invalid through the validator; transitions belong to S-059.

### Alternatives considered

**Q1 — Where does Flight↔Tow orchestration live?** **Server-side, in `FlightApplicationService` under one `@Transactional` boundary.** Mirrors legacy `InsertFlightDetails`/`UpdateFlightDetails` which writes both rows in one `SaveChanges()` (`FlightService.cs:1249-1299`). Client `FlightFormCoordinator` (S-062c) only handles UX-level mirroring (start-time, location, outbound route glider→tow at submit time per `FlightsController.js:348-378`).

**Q2 — DTO shape: two-tier vs. merge.** **Two-tier** (`FlightListItemDto` + `FlightDetailDto`). Mirrors legacy; ADR 0005 consequences explicitly call out "separate list-view DTOs from detail DTOs as the current code already does." Rejected: one `FlightDto` (payload bloat — crew + locations would be 100s of KB per page).

**Q3 — Eager or deferred validation on create? (Open question)** Legacy persists in `NotProcessed` even with missing pilot/aircraft (validation is an async workflow). New could reject 400 at create. Either choice is a behavior change relative to the other (preserve = ship known weakness; reject = potentially breaks OGN ingestion S-066). **Defaulting to legacy behavior (deferred)** for parity. If we reverse later, OGN ingestion path may need a `?skipValidation=true` switch.

**Q4 — Copy endpoint: server-side `GET /{id}/copy-template` vs. client-side cloning. (Open question)** Legacy is client-side. **Chose server-side**: defaults stay versioned with API; copy logic centralized; future-proof if we add server-only fields. Cost: extra round-trip on copy click; QA test placement moves to integration layer. SPA still navigates to `/flights/copy/:id` but fetches the template via API.

**Q5 — DTO shape: nested-by-discriminator vs. discriminated union. (Open question)** Picking **nested-by-discriminator** (`{glider, tow, motor}` with three optional fields, mirrors legacy). Discriminated union is more idiomatic for OpenAPI codegen (per ADR 0005) but breaks parity with the legacy JSON contract; the `FlightDtoContractIT` regression bites first. Revisit if codegen ergonomics become painful — open ticket if so, but ship the parity shape now.

## Edge cases & hidden requirements

### Edge cases (per acceptance criterion)

**AC1 — Endpoints**
- Null/empty: `AircraftId`, `PilotPersonId`, `FlightTypeId`, `StartLocationId`, `LdgLocationId`, `StartTypeId`, `FlightDate` may all be null on the wire — server defers required-field checks to async validation, not insert (parity per Q3 above). Insert succeeds in `NotProcessed` state regardless (`FlightService.cs:1073-1100`, `1249-1268`).
- Discriminator derivation: not in DTO — derived server-side from which `*DetailsData` block is populated (`FlightDetails.cs:13-17`). New API defines this explicitly via the OpenAPI `discriminator` keyword.
- Discriminator mid-edit change: legacy doesn't allow Glider → Motor (separate `*DetailsData` blocks); new API enforces — return 422.

**AC2 — Paired create**
- Glider with `StartType=SelfStart(3)` / `WinchLaunch(2)` / `ExternalStart(4)` / `MotorFlightStart(5)`: no tow flight allowed; if `tow` block present → reject 422 (`FlightService.cs:1017-1022`).
- Glider with `StartType=Towing(1)`: tow flight required by **validation** but **not** by create (parity with deferred validation). Insert with `startType=Towing` and `tow=null` succeeds; flight lands `NotProcessed`.
- Tow `AircraftId` empty (legacy empty-Guid `00000000-...`): reject 422; client must normalize on its side per `FlightsController.js:319-324`. **API rejects empty UUIDs at the wire.**

**AC3 — Validator (`Valid`/`Invalid` inline write)**
- Validator is pure: takes `Flight`, returns `List<ValidationError>`. Caller decides whether to mutate state. `FlightApplicationService` runs it inline only on the dedicated `POST /flights/{id}/validate` path (out of scope here — owned by S-083); on create/update the validator does NOT run (deferred per Q3).
- `Valid` flight with no further changes stays `Valid` — re-running validator is idempotent.

**AC4 — Copy-flight**
- Copy preserves: `FlightDate`, `StartType`, `GliderFlightDetailsData` (mostly), `TowFlightDetailsData` (mostly).
- Copy clears: `FlightId`, all `StartDateTime`/`LdgDateTime`, `FlightComment`, `CouponNumber`, engine counters (`FlightsController.js:232-254`).
- Copy of `DeliveryBooked` flight: legacy allows it (creates new `NotProcessed` copy) — terminal state only blocks edit/delete on the original.
- Copy of cross-club-owned flight: `@TenantId` filters the source lookup; cross-tenant copy → 404.

**AC5 — Cascade tow delete**
- DELETE on glider with `tow_flight_id` set → cascade delete the tow row inside the same `@Transactional` boundary. Two audit events (`flight.deleted` × 2) with the same `request_id`.
- Postgres self-FK has no DB-level cascade (we don't want one — would bypass application cascade audit). Application-layer cascade only.
- DELETE on a tow row directly: rejects 422 — tow rows are owned by their parent glider, not independently deletable. Mirrors legacy where there's no standalone tow-delete endpoint.

### Hidden requirements (legacy behavior the story doesn't mention)

- **Create endpoint inserts with `ProcessState=NotProcessed` regardless of completeness** — validation is async via `POST /flights/validate` (`FlightService.cs:917-946`, `1249-1268`). Preserve.
- **`PUT /flights/{id}` rejects only `DeliveryBooked` in legacy** — not `Locked`, not `DeliveryPrepared`. Client uses `CanUpdateRecord` to hide UI, but server doesn't re-enforce (`FlightService.cs:1276-1280`). **We close this gap** — see Security plan.
- **Cascading tow-flight delete** when deleting parent glider — manual (no SQL Server self-FK cascade) at `FlightService.cs:1314-1319`. Postgres + Hibernate self-FK behavior deliberately matches: application-layer cascade only.
- **Empty-Guid normalization on tow data** (`00000000-...-000000000000` → `""`) at `FlightsController.js:319-324`. New API rejects empty UUIDs at the wire — client is responsible for normalization.
- **Tow fields auto-synced from glider on save:** `StartDateTime`, `StartLocationId`, `OutboundRoute` copied glider→tow at `prepareForSaving` (`FlightsController.js:370-372`). **Server enforces** as a redundant safety net even though client also does it (defense in depth) — owned by `FlightApplicationService`.
- **Outbound/Inbound route validation against `InOutboundPoints` allowlist** per location (`FlightService.cs:1112-1136`) — only at validation time (the `validate` endpoint), not create. Lives in `FlightValidator`.
- **`GetFlightDetails` returns `CanUpdateRecord`/`CanDeleteRecord` flags** computed from `ProcessState` + role (`FlightService.cs:1725-1771`). New API includes them on `FlightDetailDto` so SPA can disable edit. Compute server-side from caller's roles.

### Scope clarifications

**In:** create / read / update / copy / delete (glider + tow + glider-with-tow). Single-tenant view. `NotProcessed` initial state. `CanUpdate/CanDelete` flag computation. Cascading tow delete. Validator port (pure function). Validation rules ported.

**Out:**
- State transitions other than initial create (S-059 / S-061).
- Async validate-flights workflow trigger (`POST /flights/validate`) — invoked by job S-083.
- **Motor flight form** — backend accepts `MotorFlightDetailsData` in the DTO (so S-064 can use this API surface) but the form lives in S-064.
- List filters by AirState + ProcessState dropdowns — endpoint accepts them; UI is S-062b.
- Client form mechanics, signal store, route wiring — S-062b/c.
- Audit-log emission infrastructure (C12) — S-027; this story emits events into the infrastructure S-027 stands up.

### NFR call-outs

- **Performance**: list endpoint is the hot path; p95 < 250ms server-side target (Performance plan below).
- **Security**: `[Authorize]` everywhere (per ADR 0007); `CanUpdateRecord` server-enforced on PUT/DELETE (legacy gap closed).
- **Observability**: audit log per C12 — every Insert/Update/Delete/Copy emits an audit event.
- **i18n**: `VALIDATION_ERROR_*` keys returned as message keys, translated client-side. Under C15 they exist in `next/web/`'s bundles (S-057).

## Security plan

### Threat model

- **Cross-tenant Person FK injection (high)**: tenant-A POSTs `FlightCreate` with `pilot_person_id` belonging to a Person who has no `PersonClub` row for tenant A. Legacy UI hides this (Persons dropdown is server-filtered via `PersonClubs.Any(ppc=>ppc.ClubId==current)`) but legacy `InsertFlightDetails`/`ValidateFlightBasics` (`FlightService.cs:1073-1137`, `:1249-1268`) do NOT re-validate at write time — **latent legacy vuln**. Mitigation: write-time membership check (see Authorization).
- **Cross-tenant Flight read/edit (high)**: tenant-A passes a tenant-B `flightId` to GET/PUT/DELETE/copy. Mitigation: `@TenantId` on `flight.operating_club_id` (S-022) auto-filters; lookup returns empty → controller returns 404 (NOT 403 — 403 leaks existence). Verified by S-024.
- **Cross-tenant Aircraft/FlightType/Location FK injection (high)**: same shape for `aircraft_id`, `flight_type_id`, `start_location_id`, `ldg_location_id`. Mitigation: resolve each FK via tenant-scoped repository before persisting; mismatch → 422.
- **TowFlight FK swap (high)**: tenant-A glider passes `tow_flight_id` pointing at tenant-B tow row. Same mitigation. Equally important: nested tow create payload inherits `operating_club_id` from parent, not from client.
- **Mass-assignment via DTO (med)**: client posts `operating_club_id`, `owner_id`, `process_state_id`, `validation_errors`, `version`, audit columns. Mitigation: `FlightCreate`/`FlightUpdate` DTOs do not expose these; tenant set by resolver, state always `NotProcessed` on create. Reject unknown JSON fields (`FAIL_ON_UNKNOWN_PROPERTIES=true`).
- **Process-state bypass via update (high)**: client edits a `DeliveryBooked` flight. Legacy throws `LockedFlightException` (`FlightService.cs:1276-1280`). Mitigation: PUT/PATCH/DELETE reject when `process_state == DeliveryBooked`; additionally reject `Locked`/`DeliveryPrepared` unless caller is `CLUB_ADMINISTRATOR` (mirrors `SetFlightOverviewSecurity` `:1675-1687`). **This closes the legacy gap.**
- **State-transition smuggling (high)**: client puts a new state value into Create/Update DTOs. Mitigation: state field is read-only on these DTOs; transitions only via S-059.
- **Optimistic-concurrency bypass (med)**: two admins edit same flight; second wins silently. Mitigation: S-067 `@Version` + `If-Match`; 412 on mismatch. **`If-Match` parsing is in this story; column comes from S-067.**
- **PII leak via validation error echo (med)**: server returns full Person in 422 payload. Mitigation: 422 body uses only IDs + i18n keys.
- **PII leak via audit `before_state`/`after_state` (high under FADP)**: naive serialization includes name/email. Mitigation: audit serializer redacts via S-027 PII config — store `person_id` only.
- **Stored-XSS via free-text fields (med)**: `flight_comment`, `outbound_route`, `inbound_route`, `coupon_number` echoed into Angular and Excel. Mitigation: length cap + reject control chars; SPA uses interpolation, never `[innerHTML]`.
- **Unauthenticated create (high)**: no bearer → 401. Mitigation: ADR 0007 baseline.
- **Role-elevation via JWT claims (med)**: spoofed `realm_access.roles`. Mitigation: JWKS signature validation (S-026).

### Authorization

- `POST /flights`: `@PreAuthorize("isAuthenticated()")`. Service enforces operating_club_id = current tenant via `@TenantId`. Matches legacy `[Authorize]` on `Insert` (`FlightsController.cs:228`).
- `PUT /flights/{id}`: `@PreAuthorize("isAuthenticated()")`. Service refuses when `process_state >= Locked` AND caller lacks `CLUB_ADMINISTRATOR`; refuses unconditionally when `process_state == DeliveryBooked`. Mirrors `SetFlightOverviewSecurity` (`:1675-1687`) and `:1276-1280`.
- `DELETE /flights/{id}`: `@PreAuthorize("isAuthenticated()")`. Refuses on `DeliveryBooked` (legacy `:1308-1312`); club admin required when `>= Locked`. Cascade-deletes linked TowFlight (`:1315-1318`).
- `POST /flights/copy/{sourceId}` (path-equivalent of `GET /flights/{id}/copy-template`): `@PreAuthorize("isAuthenticated()")`. Reads source via tenant-scoped repo (cross-tenant → 404); produces fresh `Flight` owned by caller's tenant.
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
- **A07 Auth Failures**: (N/A — inherited from ADR 0007 + S-026).
- **A08 Software & Data Integrity Failures**: optimistic concurrency (S-067) + audit-event chain.
- **A09 Logging & Monitoring Failures**: audit events per S-027; failed mutations logged.
- **GDPR/FADP**: Person PII handling per §PII; audit-event redaction load-bearing.

## Test plan

### Coverage contract

This story owns **happy-path parity** for create/edit/copy/delete on glider and tow flights at the HTTP layer, plus unit-level coverage of mapping, validator, copy-reset logic, and dual-row orchestration. Depth is explicitly deferred:

| Dimension | Owner |
|---|---|
| Validation rejection paths (`ValidateFlightBasics` failures) | **S-101** |
| Illegal `FlightProcessState` transitions | **S-102** |
| Time-gate boundaries (≥2d / ≥3d) | **S-103** |
| Permission matrix per endpoint × role | **S-104** |
| Glider↔Tow cascade / orphan / concurrent | **S-105** (depth) + **S-063** (impl) |
| Cross-tenant isolation per endpoint | **S-024** (CI) + **S-106** (HTTP) |
| Optimistic concurrency 412 | **S-067** |
| UI-driven parity (specs `04`/`05`) | **S-062c** |

S-062a's job: green the backend integration tests + provide the seams S-062b/c green their UI tests on top of.

### Test pyramid for this story

- **Unit**: ~22 — DTO↔Flight mapping, copy-reset rules, discriminator assignment, FlightCrew aggregation, validator rule-by-rule.
- **Integration**: ~18 — controller + service against Testcontainers Postgres, `@WithTenant`, happy-path round-trips per type + tenant smoke.
- **E2E new in this story**: 0 (e2e lives in S-062c).

### Unit tests

Server (JUnit 5, no Spring context):

- `flightCreateDtoToEntity_glider_selfLaunch_setsAircraftTypeGlider` — `FlightMapper#toEntity`.
- `flightCreateDtoToEntity_glider_towing_setsLinkedTowFlight` — two `Flight` rows linked via `towFlightId`. `FlightMapper#toEntity`.
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
- `validator_emitsErrorWhenAircraftMissing` — smoke; depth in S-101.
- `validator_emitsErrorWhenWinchLaunchHasNoWinchOperator` — smoke for start-type-specific arm.
- `validator_passesHappyPath` — full glider happy path.
- `validator_isPure_noDbCalls` — ArchUnit-style assertion: no `Repository` injection.
- `factory_newFlightTemplate_appliesClubDefaults`.

### Integration tests

Slice: `@SpringBootTest` + Testcontainers Postgres + transactional rollback per test + `@WithTenant(CLUB_A)` (per S-015).

**`FlightCreateControllerIT`**
- `postGliderFlight_selfLaunch_returns200AndPersists`.
- `postGliderFlight_towing_pairCreated` — POST with both blocks → two rows linked. Foundation for S-063 / S-105.
- `postMotorFlight_persistsWithCorrectDiscriminator` — smoke (full coverage in S-064).
- `postFlight_newFlightLandsInNotProcessedState` — verifies S-059 default wiring.
- `postFlight_responseIncludesCalculatedAirState` — verifies S-060 wiring.
- `postFlight_rejectsCrossTenantAircraftFk_422`.
- `postFlight_rejectsPersonWithoutPersonClub_422`.

**`FlightUpdateControllerIT`**
- `putFlight_roundTripsFlightComment`.
- `putFlight_preservesProcessStateWhenInValidOrLocked`.
- `putFlight_rejectsWhenFlightInDeliveryBookedState_409` — mirrors `:1276-1280`.
- `putFlight_rejectsLockedWithoutClubAdmin_403` — closes legacy gap.
- `putFlight_allowsLockedAsClubAdmin_200`.
- `putFlight_partialUpdateOfGliderPreservesTowLink` — S-063 line-10 basis.
- `putFlight_returns412OnIfMatchMismatch` — placeholder until S-067; assertion may be `@Disabled` pending column.

**`FlightDeleteControllerIT`**
- `deleteFlight_cascadesTowRow_inSameTx`.
- `deleteFlight_emitsTwoAuditEventsSameRequestId`.
- `deleteFlight_rejectsDeliveryBooked_409`.

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

### Parity tests

Deferred to S-062c — specs 04 + 05 are end-to-end and require the SPA.

This story's contract-IT (`FlightDtoContractIT`) is the API-level parity oracle for S-062a.

### Test data + fixtures

- **Testcontainers Postgres** per S-015, `reuse=true`. Transactional rollback per method (~10ms/test).
- **`FlightFixtures.gliderFlightInValidState()`** — class-scope; builds full Valid Flight in CLUB_A. Modeled after `ensureGliderFlight` (`e2e/test-data.ts`).
- **`FlightFixtures.gliderTowPair()`** — linked-pair shape.
- **`FlightFixtures.gliderFlightInDeliveryBookedState()`** — for rejected-update test.
- **`MasterDataFixtures.minimalClubSetup()`** — one Club, glider Aircraft (2-seater no-engine per `04:96-98`), tow Aircraft, pilot Person, tow pilot Person, 2 Locations (no in/outbound route reqs), Self-launch + Towing StartTypes, glider FlightType, tow FlightType. Loaded once per class, rolled back per method.
- **JSON contract fixtures** for `FlightDtoContractIT`: `src/test/resources/parity/flight-details-glider-self-launch.json`, captured against legacy.

### Risks

- **Validator parity drift** — `ValidateFlightBasics` has tendrils into many private helpers (`FlightService.cs:985-1136` is ~150 lines, plus called methods). Risk of subtly different rejection conditions. Mitigation: unit-test the validator rule-by-rule against fixtures captured from legacy. Full depth in S-101.
- **DTO shape drift** between Web API and JPA — `FlightDetails` is hand-mapped legacy-side. Mitigation: `FlightDtoContractIT` catches drift.
- **Server-side copy endpoint is an open ADR question** — picked server-side per Q4 in Alternatives. If reversed, push the template construction back into `FlightStore` (S-062c).
- **Calculated `FlightAirState` reads "now"** — must be pure on timestamps to avoid test flakiness. S-060 verifies.
- **Transaction rollback hides constraint violations** that fire on commit — S-015 provides a `@Commit` helper for these edge cases.

## Performance plan

### Hot paths

- **`POST /api/v1/flights/search`** (list — new equivalent of legacy `gliderflights/page`): dominant read. **Bursty 5–15 rps per club during ops hours**; top-5 route per S-108. UI performance owned by S-062b; server-side budget owned here.
- **`GET /api/v1/flights/{id}`** (edit fetch): low rate (<1 rps) but every form open. Eager graph required.
- **`POST /api/v1/flights`** + **`PUT /api/v1/flights/{id}`**: low rate, bursty around busy Saturday. Concurrent with OGN ingestion.
- **`POST /api/v1/flights/copy/{id}` equivalent (GET copy-template)**: rare; reads full graph.

### Required indexes

Story frontmatter for S-013 names only three. The legacy filter set (`DBUpdate_v1.9.30.sql`) is broader. Required:

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
- **Postgres-specific**: legacy `Immatriculation LIKE '%X%'` and `Lastname LIKE '%X%'` need either prefix-match restriction (semantics break) or `pg_trgm` GIN indexes. Defer until first tenant feedback indicates the parity gap matters.

**Action**: open S-013, diff against `DBUpdate_v1.9.30.sql`, add missing FK indexes. Do not ship S-062a until S-013 carries equivalents.

### N+1 risks

The list query in legacy EF is one round-trip. The Hibernate risk is **dropping into default lazy-load** and per-row queries (50 rows × 5 refs = 250 extra). Specific risks:

- **`Flight.aircraft → Aircraft.immatriculation`**: project to DTO in JPQL (`SELECT new FlightListItem(f.id, f.aircraft.immatriculation, ...)`) or use `@EntityGraph(attributePaths={"aircraft"})`. **Prefer DTO projection** — list never needs full Aircraft entity.
- **`Flight.flightType.flightCode`** — DTO-project.
- **`Flight.startLocation.locationName` / `landingLocation.locationName`** — DTO-project.
- **`Flight.gliderPilotPerson` / `flightInstructorPerson` / `towPilotPerson`** — if S-058 lifted to direct FKs, DTO-project the name fields.
- **`Flight.flightCrews` collection** — if pilot/second-crew derived from `FlightCrew`, legacy computes per-flight pilot+second-crew with grouped subquery (`FlightService.cs:464-479`). Port as CTE / window-function projection, **not** `@OneToMany(fetch=LAZY)` iterated in Java. Highest-risk N+1.
- **`Flight.towFlight`** — only for detail. On list, do **not** fetch-join.

**Mitigation rule**: list endpoint executes **exactly one SQL statement** per page (plus pagination count). Add a Hibernate `Statistics.queryExecutionCount() == 1` (+1 for pagination count) assertion in tests.

For **edit endpoint**, eagerly fetch full graph in **one query** via `@EntityGraph(attributePaths = {"aircraft", "flightType", "startType", "startLocation", "landingLocation", "flightCrews", "flightCrews.person", "towFlight", "towFlight.aircraft", "towFlight.flightType", "towFlight.flightCrews", "towFlight.flightCrews.person", "towFlight.startLocation", "towFlight.landingLocation"})` — mirror legacy `ValidateFlight()` graph at `:959-976`. See Cartesian risks.

### Cartesian / explosion risks

- **Detail-fetch graph dangerous as single join**: Flight × FlightCrews (1–3) × StartLocation.InOutboundPoints (5–20) × TowFlight.FlightCrews (1–2) × TowFlight.StartLocation.InOutboundPoints (5–20) = ~2400 rows for one flight. Mitigations (pick one):
  1. **Two separate queries** in the controller: scalars + FKs in query 1, lazy collections IN-batched via `@BatchSize` in query 2.
  2. **Hibernate `default_batch_fetch_size`** set globally (e.g. 20). JPQL projects scalars; lazy collections resolve in two batched IN queries. Simpler, same SQL count.
- **List query has no Cartesian risk** if it stays a flat DTO projection. Forbid `JOIN FETCH` on `flightCrews` in the list query.

### Caching strategy

**Server-side:**
- **List endpoint**: no server-side cache. Invalidates on every create/edit/OGN-ingest.
- **Form-load reference data** (FlightType, FlightCostBalanceType, StartType, AircraftType, CounterUnitType, locations): Caffeine, **TTL 10 min**, keyed by `(clubId, dataType)`. Invalidate on master-data mutation via `ApplicationEvent`. Reference-data caches are read by S-062c on form load; declared here because the cache infrastructure is server-side.
- **Aircraft / Person listitems**: same. Hot — every form open hits them.
- **No HTTP cache headers** on list — too volatile.
- **L2 cache on Flight entity**: do **not**. High write rate creates invalidation churn.

Client-side caching is S-062b/c.

### Latency budget

Anchored to NFR (read p95 < 500ms, page-load p95 < 3s) and S-108 baseline:

- **`POST /flights/search`**: p95 < **250ms** server-side at page=50, typical filter.
- **`GET /flights/{id}`**: p95 < **150ms**. PK lookup + bounded batch fetch.
- **`POST /flights`**: p95 < **300ms**. One INSERT + audit + version + serialize.
- **`PUT /flights/{id}`**: p95 < **300ms**. Validation + UPDATE + audit + version check. 412 path < 50ms.
- **`GET /flights/{id}/copy-template`**: p95 < **400ms**. Reads full graph.

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

2. **Postgres `EXPLAIN (ANALYZE, BUFFERS)`** on list against 50k-flight tenant. Look for: `Seq Scan on flight` (fail), `Sort` step (fail — wrong index order).

3. **HikariCP sizing**: 10 readers + 2 writers + OGN + jobs need pool ≥ 25. Revisit per S-111.

k6 load tests + cold-cache LCP belong to S-062b (list) and S-062c (forms).

## Open design questions (carried from original S-062)

- **Eager or deferred validation on create?** (Q3 above). Defaulted to deferred for parity. Confirm with stakeholder; reversal is in-scope here, not S-062b/c.
- **Server-side `CanUpdateRecord` re-check on PUT/DELETE.** Security plan closes the legacy gap. Confirm or ship the legacy weakness intact.
- **Copy endpoint: server-side vs. client-side.** Q4 chose server-side. Confirm or push back into S-062c.
- **DTO discriminator: nested vs. tagged union.** Q5 chose nested for parity. Revisit if OpenAPI codegen ergonomics demand it.
- **Server-required-but-no-client-`required` fields** (`flightTypeId`, `pilotPersonId` on both blocks): if we switch to eager validation (Q3), client gets `Validators.required` parity in S-062c.

<!-- modernize-refine: end -->

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

The 2026-05-15b vision-doc amendment lands one **new backend endpoint** in S-062a (everything else is client-side and handled by S-062b / S-062c / S-008 / S-007). See [`02-vision-and-constraints.md`](../02-vision-and-constraints.md) §F6.

**Layered acceptance criterion (additive):**

- **AC-DIR-1 (`/api/v1/flights/last-context` endpoint).** A new `GET /api/v1/flights/last-context?aircraftId={id}&date={yyyy-MM-dd}` endpoint returns the field-combo of the **last saved flight** for the same `(operating_club_id, aircraft_id, flight_date)`, intended to seed the flight-edit form's empty state. Response is a thin `FlightLastContextDto` (NOT the full `FlightDetailDto`):
  - `flightTypeId` (last used for this aircraft on this date)
  - `pilotPersonId`
  - `startLocationId` / `ldgLocationId`
  - `outboundRoute` / `inboundRoute`
  - `flightCostBalanceType`
  - `invoiceRecipientPersonId`
  - `startType`
  - For tow aircraft path: `tow.aircraftId`, `tow.pilotPersonId`, `tow.flightTypeId`, `tow.ldgLocationId`
  - **Times are NOT returned** (the user wants pre-fill on what to fly with, not when).
  - **404 if no match** — client falls back to per-club defaults.
- **Tenant scope.** Subject to the same `@TenantId` filter as every other read (C3). Cross-tenant 404 — never leak which aircraft other clubs flew.
- **Authorization.** Same as `GET /flights/{id}` — any authenticated user with `FLIGHT_READ` for the tenant.
- **Caching.** No server-side cache — query is cheap (single indexed read). Client (S-062c) consumes it once on form open; no re-fetch.
- **Test.** Integration test covers: (a) returns last-flight context when present; (b) returns 404 when no prior flight; (c) cross-tenant aircraft returns 404; (d) tow context populated when last flight was towing-start, omitted otherwise.

**Refinement status flag:** Story was refined on 2026-05-14 *before* the directive. The new endpoint is small and additive — does NOT require a full re-refine, but the implementer should fold AC-DIR-1 into the endpoint inventory, DTO list, and IT plan as a one-pass amendment. Mark the existing "Tasks" list with `/api/v1/flights/last-context` as a final task.

<!-- amendment-2026-05-15b: end -->
