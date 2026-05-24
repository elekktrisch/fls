---
id: S-058
title: Flight entity + FlightAircraftType discriminator
epic: E-07
status: in_progress
started_at: 2026-05-24
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
Sacred-cow shape — single Flight entity for glider/tow/motor, discriminated by FlightAircraftType. Don't split into multiple tables.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] JPA entity.
- [ ] Repository.
- [ ] Basic controller + DTOs (`FlightListItem`, `FlightDetail`, `FlightCreate`).
- [ ] Smoke test creating each of the three types.

## Notes
Crew references live in `FlightCrew` (M:N to Person). Add basic FlightCrew handling here even though it's referenced by E-07's later stories.

<!-- modernize-refine: start -->

## Design notes

### Module + cross-module wiring
New module `ch.alpenflight.flights.{domain,application,web,infra}`. Modulith deps: `aircraft.domain` (typed `AircraftId`), `persons.domain` (`PersonId`), `flighttypes.domain` (`FlightTypeId` + future FCBT picker), `locations.domain` (`LocationId`), `audit.application`, `platform.*`. Ship typed `FlightId` (prefix `fl-`) in `platform.id`.

### FlightAircraftType discriminator
Java enum `GLIDER, TOW, MOTOR` + `@Converter(autoApply=false)` `AttributeConverter<FlightAircraftType, Short>` with explicit `{GLIDER→1, TOW→2, MOTOR→4}`. **Do not** use `EnumType.ORDINAL` (gap at 3); **do not** add a dummy `UNUSED_3` constant. Applied via `@Convert` on the field. Immutable post-create — rejected from the update DTO.

### Aggregate + factories
`Flight` is aggregate root. `FlightCrew` is `@OneToMany(cascade=ALL, orphanRemoval=true, fetch=LAZY)` aggregate-internal, **replaced wholesale on PUT** (no `/flights/{id}/crew` sub-resource; matches ADR 0018). Three named factories `Flight.createGlider(...)` / `createTow(...)` / `createMotor(...)` — per-type column sets differ enough that one mega-ctor would just branch at runtime. Tow link via `@ManyToOne(fetch=LAZY)` `tow_flight_id`, **no cascade**, managed only through `Flight.linkTow(other)` / `unlinkTow()`. `linkTow` enforces aggregate invariants (per ADR 0022 directive 2 — V3 stripped the CHECKs): `this.type==GLIDER`, `other.type==TOW`, `this.id != other.id`, `this.operatingClubId == other.operatingClubId`. `flight.tow_flight_id` schema FK is `ON DELETE SET NULL` (V3:421-422, confirmed); soft-delete does not trigger it — a soft-deleted tow leaves the glider's link intact (intentional, audit-preservation).

### Repositories + finders
Drop `findByOperatingClub` — `@TenantId` auto-filter collapses it into `findAll()`. Keep `findByTowFlightId(FlightId)` (S-059 consumer) and `findByProcessStateId(UUID)`. List endpoint ships **keyset-cursor pagination** (FE is infinite-scroll, not random-access) + `flightDateFrom` / `flightDateTo` filters from day one. Discovery is filter-first; scroll is the "load more from this filtered set" affordance, not the primary nav.

### API surface
Standard 5 routes under `/api/v1/flights`. `PUT` (not `PATCH`) for update — crew list is replaced wholesale, which matches PUT semantics. List: `GET /api/v1/flights?after=<opaque>&limit=50&from=…&to=…` → `{ items: [...], nextCursor: "..." | null }`. Order `flight_date DESC, id DESC` (tiebreak on id; matches `ix_flight_club_date` + adds id as secondary). Cursor is opaque base64 of `(flight_date, id)`. No total count. Default limit 50, max 200. No `SYSTEM_ADMINISTRATOR` gate anywhere (per S-159 amendment of ADR 0008).

### DTOs + mass-assignment defense
`FlightListItem` (slim — id, date, aircraft immat, pilot display name, type, process_state, durations), `FlightDetail` (full read + crew), `FlightCreate` / `FlightUpdate` (write payload). Write DTOs **exclude**: `processStateId`, `airStateId`, `validatedOn`, `deliveryCreatedOn`, `flightReportSentOn`, `validationErrors`, `createdByUserId`, `modifiedByUserId`, `operatingClubId`, `id`. `isSoloFlight` accepted as input (legacy parity — server-derive is S-059). MapStruct / explicit mapper, never entity binding.

### Initial state (reference-data lookup)
**Don't port** `flight_process_state` / `flight_air_state` / `flight_crew_type` / `start_type` as full JPA reference-data entities here. Tiny `id+code` projection entity + repository in `flights.infra/` exposing `findIdByCode(String)`; cache the two seed UUIDs the controller needs (`not_processed`, `new`) at `@PostConstruct`. New Flight stamped server-side with those UUIDs. `flight_crew_type_id` + `start_type_id` ride as raw `UUID` FKs on the entity; full reference-data port belongs to whichever story first needs to validate them.

### Aircraft FK is cross-tenant
S-058 reverts S-159 (see ADR 0008 amendment 2026-05-24). Aircraft is cross-tenant; `Flight.aircraft_id` may reference any active aircraft regardless of its `managing_club_id`. The charter case (Club B's Flight references Club A's tow plane) is first-class — no service-layer use-rights pre-check at S-058 scope. Unknown / soft-deleted aircraftIds trip the DB FK and surface as `400 Bad Request` via the `DataIntegrityViolationException` handler in `FlightsExceptionHandler`.

### FlightCrew cross-tenant ride-through
`flight_crew.person_id` loads via `personRepository.findById(uuid)` — PK-load across tenants is the **legitimate** sacred-cow shape (Person has no `@TenantId`). Any `findAll` / `findByX` against Person from Flight code is banned. PersonClub-membership validation (is this Person a member of the operating club?) is **NOT** enforced here; defer to S-026.

### Audit + tenant + PII
`@TenantId` on `Flight.operatingClubId`. `AuditTrailService` called from `FlightsService` on every mutation; resource type `flight`. Flight stays under **`audit.redaction.deny-all`** (default). PII columns per S-013 (`comment`, `incidentComment`, `validationErrors`, `outboundRoute`, `inboundRoute`) already redact structurally; no allow-list entry shipped.

### Out of scope (S-059 owns)
`process_state` + `air_state` transitions, the validator, `validatedOn` / `deliveryCreatedOn` / `flightReportSentOn` derivation, `isSoloFlight` server-derive, FlightType×FlightAircraftType compatibility, `is_coupon_number_required` enforcement, the range/calculation invariants previously held by the 14 stripped V3 CHECKs. S-058 only guarantees: discriminator correctness, tow-link invariants, FlightCrew aggregate cohesion, basic NOT-NULL/FK satisfaction, structural temporal/counter ordering (already pinned by surviving V3 pairwise CHECKs).

### ADR 0022 directive 2 conformance
No CHECK constraints, generated columns, or triggers reintroduced. V3's stripped CHECKs stay stripped.

## Edge cases & hidden requirements

- **Initial state on POST.** `process_state_id` ← canonical UUID for `code='not_processed'` (legacy `Flight.cs:20` ctor). `air_state_id` ← canonical UUID for `code='new'`. Both server-set; both excluded from write DTOs. Legacy `GetCalculatedFlightAirStateId()` is S-059 scope.
- **`flightAircraftType` is client-supplied, REQUIRED, immutable post-create.** Aircraft category does not dictate flight purpose (a MotorAircraft can fly tow duty; a GliderWithMotor can be glider or motor purpose). Aircraft × purpose compatibility check is S-059 scope.
- **Tow-flight pairing: two-POST + PUT-to-link.** Each row is independent; cascade-create in a single POST conflates with S-059's start-type orchestration. 1:N (a tow row may serve multiple gliders) matches legacy `TowedFlights` collection.
- **DELETE is soft-delete** (`deleted_on = now()`, `deleted_by_user_id`). FlightCrew rows cascade-soft-delete in the same transaction via aggregate code, not DB CASCADE (DB CASCADE only fires on hard-delete). `findByTowFlightId` excludes soft-deleted rows.
- **Keyset cursor pagination is mandatory at S-058** (FE is infinite-scroll; users are expected to filter, not scroll the whole set). Cross-tenant `GET /api/v1/flights/{id}` returns **404, not 403** — Hibernate `@TenantId` on `Flight.operating_club_id` hides the row.
- **Cross-tenant aircraft_id is allowed.** Aircraft is cross-tenant (S-058 reverts S-159). A Club B Flight may reference a Club A aircraft (charter case); the FE picker surfaces other clubs' aircraft on the Flight form. The `FlightsTenantIsolationIT.createFlight_referencing_other_tenants_aircraft_succeeds` test pins the contract.
- **Composite-unique crew rows.** `(flight_id, person_id, flight_crew_type_id) WHERE deleted_on IS NULL` — duplicate crew on PUT surfaces as DB constraint violation → 400.
- **Aggregate-level invariants** (Java, not DB): `block_start ≤ start ≤ ldg ≤ block_end` (nullable-aware pairwise); `engine_start ≤ engine_end` (DB CHECK keeps redundant safety net); `nr_of_ldgs_on_start_location ≤ nr_of_ldgs` (DB CHECK kept). Runway / coupon-format VOs already pinned by V3 regex CHECKs — VO mirrors. `nr_of_ldgs ≥ 1 when air_state=landed` is state-coupled → S-059.
- **PII columns** (`comment`, `incidentComment`, `validationErrors`, `outboundRoute`, `inboundRoute`) already catalogued in `tenant-rules.yaml`; no new allow-list entry for Flight.

## Security plan

- **Authz (per S-159 — no `SYSTEM_ADMINISTRATOR` on tenant-scoped endpoints).**
  - `GET /flights`, `GET /flights/{id}`, `POST /flights`, `PUT /flights/{id}`: `hasAnyRole('CLUB_ADMINISTRATOR','FLIGHT_OPERATOR')`.
  - `DELETE /flights/{id}`: `hasRole('CLUB_ADMINISTRATOR')` (destructive; higher bar than create/edit).
- **Tenant isolation.** `@TenantId` on `Flight.operating_club_id`; cross-tenant access → 404 (avoids existence oracle). Aircraft is cross-tenant (S-058 reverts S-159): `aircraftRepository.findById(...)` is not tenant-filtered, so any active aircraft may be referenced; unknown / soft-deleted aircraftIds trip the DB FK and surface as 400 via `DataIntegrityViolationException`. No native SQL on Flight; ArchUnit guards.
- **Intentional cross-tenant ride-throughs.** `FlightCrew.person_id` (Person untenanted, PK-load) and `Flight.flight_cost_balance_type_id` (FCBT system-global) — no enumeration surface, no cross-tenant list endpoints. Document in module javadoc.
- **Mass-assignment.** Write DTOs explicitly exclude the state-machine + audit-metadata + tenant columns enumerated under §Design notes/DTOs. Discriminator `flightAircraftType` immutable post-create.
- **Audit.** Every mutating service call emits `MutationAuditEvent`; `RequestAuditFilter` covers 4xx/5xx with actor+tenant. Flight stays in `audit.redaction.deny-all` — PII redacts structurally.
- **OWASP touchpoints (one line each).** A01: `@TenantId` + role gate + 404 pattern. A04 mass assignment: explicit write surface. A09: audit on every write + filter for failed requests.

## Test plan

- **Pyramid.** Domain unit (Flight aggregate + FlightAircraftType converter + FlightCrew handling): ~12. Integration (`@SpringBootTest` + Testcontainers + `JwtTestFixture`): ~8. No application-layer unit suite (orchestration is thin CRUD). No FE / Playwright — `parity_test: none`.
- **Domain unit — load-bearing.**
  - `FlightAircraftType` converter: round-trips `{1, 2, 4}`; throws on DB-read of `{0, 3, 5, null-NOT-NULL}` (parametric — pin **3** explicitly, that's the sparse-skip bug surface).
  - Tow-link invariants on the aggregate (CHECKs stripped per ADR 0022): glider-only sets link; tow target must be `TOW`; self-link rejected; same-tenant required; clear-link allowed. One test per rule.
  - FlightCrew add/remove/replace via aggregate root only — direct repository mutation banned.
- **Integration — load-bearing.**
  - Cross-tenant `GET /api/v1/flights/{id}` → 404 (mirror `PersonsCrossTenantRideThroughIT`).
  - Person ride-through: Club B Flight with pilot whose only `PersonClub` is Club A → PK-load returns the Person; no tenant filter leak on `person_id`.
  - Cross-tenant Aircraft FK block on create (the regression S-159 enables): Club B POST referencing Club A's aircraft → 404, NOT a successful FK write to an unreadable row.
  - Soft-delete: `deleted_on` filters list; soft-deleting a tow leaves glider's `tow_flight_id` intact.
  - AC4 smoke: create + read one Flight per `{GLIDER, TOW, MOTOR}` end-to-end.
  - Repository finders honor `@TenantId` (single assertion — framework guarantee, not a suite).
- **Deferred** — state-machine transitions, `validated_on` derivation, locking, delivery flow. All S-059+.
- **Parity.** None. `parity_test: none`; the IT suite IS the contract.
- **Iteration loop** — `./gradlew test --tests 'ch.alpenflight.flights.*'` (~30-60s) per S-051 pickup-notes directive; rely on remote CI for the full `check`.

## Performance plan

- **Keyset cursor pagination.** `GET /api/v1/flights?after=<opaque>&limit=50&from=…&to=…` → `WHERE operating_club_id = ? AND flight_date BETWEEN ? AND ? AND (flight_date, id) < (?, ?) ORDER BY flight_date DESC, id DESC LIMIT 51` (limit+1 sentinel to compute `nextCursor`). No `OFFSET`, no `count(*)`. Default limit 50, max 200, default window last 90 days. Hits `ix_flight_club_date`; if EXPLAIN shows the (flight_date, id) tiebreak forcing a Sort node, promote the index to `(operating_club_id, flight_date DESC, id DESC)` in a V-bump migration.
- **List N+1 → DTO projection.** `findAllForList` is a JPQL `select new FlightListItem(...)` joining `aircraft`, `startLocation`, `landingLocation`, and the PIC `flight_crew` row + `person`. Single query, no entity hydration. Precedent: `AircraftRepository.findAllForList`.
- **PIC join.** `flight_crew fc ON fc.flight_id = f.id AND fc.flight_crew_type_id = :pic AND fc.deleted_on IS NULL` → `ix_flight_crew_flight` + the partial unique. If multiple crew types end up in the list later, switch to `@BatchSize(50)` on `Flight.flightCrews` rather than fan-out joins.
- **State / work-list finders.** `findOpenForValidation`: `WHERE validatedOn IS NULL` (verbatim — no `= null`, no `COALESCE`) to hit the partial `ix_flight_validated_on`. Same for tow-pairing + coupon finders.
- **Per-aircraft / per-type finders.** Confirm JPQL `ORDER BY flight_date DESC` matches the index DESC ordering on `ix_flight_aircraft_date` + `ix_flight_club_aircraft_type`.
- **Detail GET.** `findById` with `@EntityGraph(attributePaths = {"flightCrews", "flightCrews.person", "aircraft", "startLocation", "landingLocation"})`. Crew always rendered on detail view; lazy load here is pure N+1.
- **Per-person crew query.** JPQL on `FlightCrew` with `where fc.person.id = :pid` — drop redundant `operatingClubId =` predicate (handled by `@TenantId` on Flight; extra clause can disable index-only scan on `ix_flight_crew_person_type`).
- **Bar (not a measurement gate).** Hibernate SQL log shows ONE query for list, ONE for detail. `EXPLAIN` of each new finder shows Index Scan / Index-Only Scan on the V3 index it targets, never Seq Scan on `flight`. p95 budget → S-108.

## Open design questions

1. **Tow-flight cardinality (1:1 vs 1:N).** Legacy `TowedFlights` is a collection (1:N); design notes adopt that. Operator confirm OK before implementer pins the column as non-unique.
2. **Reference-data port shape.** Design notes recommend lightweight `id+code` projection entities for the 4 reference tables (`flight_process_state`, `flight_air_state`, `flight_crew_type`, `start_type`) rather than full ports with admin CRUD. Confirm vs deferring even the read-projection until the first downstream story needs it (in which case S-058 hard-codes the two seed UUIDs as constants).
3. **`flight_date` derivation.** Legacy stores `flight_date` (DATE) and `start_date_time` (TIMESTAMPTZ) independently. Server-derive `flight_date` from `start_date_time` in Europe/Zurich on PUT, or keep them independent + client-supplied (legacy parity)? Deferring keeps S-058 free of TZ-handling sprawl.
4. **DELETE role.** Design notes pin `CLUB_ADMINISTRATOR`-only; `FLIGHT_OPERATOR` may need same-day mistake deletion in practice. Confirm or relax to `hasAnyRole('CLUB_ADMINISTRATOR','FLIGHT_OPERATOR')`.

<!-- modernize-refine: end -->

## Pickup notes

Implementation paused mid-session (2026-05-24) after a grilling pass on Aircraft / Flight use-cases surfaced a structural scope shift. Captures session state so a future iteration can resume cold.

### Status on `story/S-058-flight-entity-discriminator` (draft PR #113)

Five commits land on top of `main`:

1. `#112: start` — frontmatter flip + GitHub issue stamp.
2. `#112: Flight aggregate + module skeleton` — typed `FlightId`, full `flights/{domain,application,web,infra}` module (`Flight` aggregate root, `FlightCrew` aggregate-internal, `FlightAircraftType` sparse converter `{GLIDER=1, TOW=2, MOTOR=4}`, three named factories, tow-link invariants, runway/coupon/temporal VOs, 5-route REST surface with role gates per S-159, keyset cursor pagination, ref-data projections, audit hookup, domain unit tests + cursor unit test).
3. `#112: fix layering — application depends on domain ports` — moved `AircraftReferenceChecker` + `FlightInitialState` behind domain ports per ADR 0023.
4. `#112: integration tests + FlightInitialState transactional init` — `FlightsControllerIT` + `FlightsTenantIsolationIT` + fixtures + sweep factory; `@PostConstruct` lookup wrapped in `TransactionTemplate` to keep the EntityManager bound.
5. `#112: revert S-159 tenant-scoping of Aircraft` — the structural pivot (see below).

### What the grilling pass changed

The day-1 charter case — small glider clubs flying tow planes owned by other clubs (or by external owners not in the system) — was structurally closed by S-159's `@TenantId` on `Aircraft.managing_club_id`. The pivot baked into commit 5 reverts that:

- `Aircraft` drops `@TenantId`; the `managing_club_id` column survives as plain metadata. Three columns drive semantics: `managing_club_id` (NOT NULL — the operational manager / write gate), `owner_club_id` (NULL OK — physical-owner metadata, no edit power), `aircraft_owner_person_id` (NULL OK — private-person metadata, person-edit predicate deferred to S-052).
- Read endpoints (list / picker / detail / state-history) open to any authenticated user. Counter-history is manager-only (foreign-club counter views aren't operationally useful — most of that aircraft's flights live elsewhere).
- New `AircraftAccess` SpEL bean: `canRegister` (CLUB_ADMIN only — sysadmin lacks a clubId claim), `canEdit` (CLUB_ADMIN of managing_club_id OR SYSTEM_ADMIN), `canOperate` (same, FLIGHT_OPERATOR also admitted).
- `Flight.aircraft_id` is unrestricted; `AircraftReferenceChecker` deleted. `DataIntegrityViolationException` → 400 in the advice handles unknown aircraftIds.
- ADR 0008 amendment (2026-05-24, reverts the 2026-05-23 S-159 amendment) landed in `docs/modernization/adrs/0008-multi-tenancy-mechanism.md`.
- `tenant-rules.yaml`, `application.yml` audit redaction allow-list, `TenantCatalogYamlTest`, `TenantCatalogConsistencyTest`, `AircraftsTenantIsolationIT`, `AircraftsAuthorizationIT`, sweep factories + `TenantScopedRowBuilders`, `AircraftTenantScopingGuardTest` (deleted), `AircraftSweepFactory` (deleted) — all consistent with the revert.

### What's left to land on this branch

1. **Verify full local test suite.** Targeted runs after the revert (domain + arch) passed; broader IT suite (`aircraft.web.*`, `flights.web.*`, `multitenancy.leakage.*`, `server.migration.*`, `OpenApiSnapshotIT`) was queued + stopped — needs a clean rerun. Expect 1-2 patch commits if anything trips.
2. **Push the pivot + watch CI.** Last pushed head is `4e2f30ee` (pre-revert); commit 5 `43b0539f` is local-only. CI is still red from the pre-revert push.
3. **Story-body updates:**
   - `docs/modernization/stories/implemented/S-050-aircraft-crud.md` — strip `@TenantId` / `managing_club_id @TenantId` language; cite the S-058 revert.
   - This file (`docs/modernization/stories/S-058-flight-entity-discriminator.md`) — design-notes section on "Aircraft FK + cross-tenant" needs to flip (the AircraftReferenceChecker paragraph is now wrong).
4. **Self-review reviewer panel** (`/modernize-implement` Step 7 — maintainability + security + tech-writer; usability N/A; parity N/A since `parity_test: none`).
5. **Prune + status:done + archive move** (Step 8) — one commit, includes `git mv` to `implemented/`.
6. **`gh pr ready` + done report** (Step 9).

### Open follow-up stories surfaced by this pass

- **Cross-club aircraft usage visibility.** When Club A charters Club B's aircraft, should Club B *see* Club A's flights on that aircraft? Three shapes considered: (a) loosen Flight `@TenantId` — breaks ADR 0008's structural multi-tenancy; (b) copy-on-flight-close — emit a derived `AircraftUsageRecord` in the manager's books; (c) defer — Club B reads their own books, reconciliation is out-of-band. Day-1 recommendation: (c). When the feature is needed, refine into a new story. Probably the right home is the delivery / accounting epic, not flights.
- **Sysadmin admin variant for Aircraft register.** `AircraftAccess.canRegister` deliberately excludes SYSTEM_ADMIN today (no clubId claim → service can't infer managing tenant). Future story: add `/api/v1/admin/aircraft` with explicit `managingClubId` field for cutover / cross-cutting register.
- **Person-owner edit predicate on Aircraft.** When S-052 (Users CRUD) wires `User → Person`, extend `AircraftAccess.canEdit` to admit the person who matches `aircraft_owner_person_id`. Schema unchanged.
- **Non-manager counter redaction on Aircraft detail GET.** Today the detail response surfaces `latestCounter` inline regardless of caller. The dedicated counter-list endpoint is gated to the manager; the inline field on detail isn't. A caller-aware mapper (or a separate non-manager detail projection) closes the gap when the policy bar rises.

### Resume recipe

```bash
git checkout story/S-058-flight-entity-discriminator
# Working tree should be clean and commit 5 (43b0539f-ish) on top.
cd alpenflight/server
./gradlew test --tests 'ch.alpenflight.aircraft.web.*' \
  --tests 'ch.alpenflight.flights.web.*' \
  --tests 'ch.alpenflight.multitenancy.leakage.*' \
  --tests 'ch.alpenflight.server.migration.*' \
  --tests 'ch.alpenflight.platform.openapi.OpenApiSnapshotIT' \
  --tests 'ch.alpenflight.arch.*'
# Iterate to green, push, watch CI, then story-body updates + reviewer panel + prune + done.
```

Or invoke `/modernize-implement` with no arg while on the branch — it picks up the story from the branch name.
