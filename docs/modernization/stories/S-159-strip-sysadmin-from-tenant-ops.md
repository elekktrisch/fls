---
id: S-159
title: Strip SYSTEM_ADMINISTRATOR from tenant-scoped ops; make Aircraft tenant-scoped
epic: E-06
status: todo
estimate: M
depends_on: [S-049c, S-050]
origin: rework
origin_story: S-050
adr_refs: [0007, 0008, 0022]
parity_test: none
refined: true
refined_at: 2026-05-23
refined_specialists: [requirements, solution, qa, security]
---

## Context

S-049c gave SYSTEM_ADMINISTRATOR a per-club impersonation path
(`/api/v1/admin/locations/{clubId}` + `Tenants.runAs`) and co-allowed sysadmin
on the regular tenant-scoped controllers (Locations, Aircraft). In practice
this is **confusing UX without value**: the regular endpoints filter by
`@TenantId` against the JWT's `clubId` claim, sysadmin has no such claim
(realm-export: only `clubadmin1` / `pilot1` carry `clubId`), so reads return
an empty list and writes fail at the FK. The "Locations admin" surface
(`/admin/locations` + a club picker) exists only because of this gap.

Operator decision (2026-05-22): remove the impersonation pattern entirely.
Sysadmin should have **only** cross-cutting rights (Clubs catalog, future
sysadmin user mgmt, future cutover/bulk-import per
[[project-legacy-bulk-import]]). Anything tenant-scoped is CLUB_ADMIN-only.

Aircraft today is *not* `@TenantId`-scoped (Hibernate-level) — it filters by
`owner_club_id` convention via `AircraftAccess`. As part of this rework,
make it structural: add `@TenantId` so the discriminator filter does the
right thing for free, like Locations / Persons / Flights.

## Acceptance criteria

### Server

- [ ] **Strip `SYSTEM_ADMINISTRATOR` from tenant-scoped `@PreAuthorize`** on:
  - `LocationsController` POST/PUT/DELETE (currently `hasAnyRole('CLUB_ADMINISTRATOR', 'SYSTEM_ADMINISTRATOR')` → `hasRole('CLUB_ADMINISTRATOR')`).
  - `AircraftsController` POST/PUT (likewise) + the create-flow co-allow in `AircraftAccess` for null-owner aircraft.
- [ ] **Delete `LocationsAdminController` + its IT.** No replacement; sysadmin doesn't act on tenant data.
- [ ] **Aircraft `@TenantId` discriminator.** Add `@TenantId` to `Aircraft.ownerClubId`. Update repo doc-comments (`AircraftRepository`, `Aircraft.java:42`, `AircraftAccess`, the two `package-info.java` mentions of "cross-tenant"). Drop `AircraftAccess` checks that the discriminator now enforces structurally.
- [ ] **Charter (null-owner) aircraft + ownership transfer — design decision.** Two flows currently sysadmin-only and structurally cross-club. Pick one and capture in this story body before implement:
  - **(a)** Require `ownerClubId` on every aircraft (drop the charter concept; cutover migration assigns a synthetic "CHARTER" club per legacy host if needed).
  - **(b)** Keep them sysadmin-only via dedicated cross-club endpoints (e.g. `POST /api/v1/aircraft/charter`, `PUT /api/v1/aircraft/{id}/owner`) that **don't** filter by `@TenantId` — explicitly cross-tenant, not impersonation.
  Default lean: **(a)** — keeps the model uniform; ownership transfer becomes a one-off sysadmin endpoint that doesn't pretend to be tenant-scoped.
- [ ] **ADR 0008 amendment.** Strike "sysadmin acts within whichever club its JWT `clubId` asserts; cross-club operations need explicit impersonation today, with a future `Tenants.runAs(...)` escape hatch". Replace with: sysadmin has rights only on cross-cutting resources (Clubs catalog, sysadmin user mgmt, cutover import). `Tenants.runAs` stays as a production seam for cutover / scheduled jobs, but the HTTP-exposed `/admin/*` impersonation path is gone.
- [ ] **Realm seed unchanged.** `sysadmin` keeps SYSTEM_ADMINISTRATOR; no clubId needed (correct now — was correct before too).
- [ ] **Cross-tenant leakage test (S-024) extended to Aircraft.** Now that aircraft is `@TenantId`-scoped, the parity test should cover it.

### SPA

- [ ] **Delete `/admin/locations` route + `AdminLocationsListPage` + `AdminLocationsEditPage`** under `features/admin/locations/`.
- [ ] **Drop the "Locations admin" sysadmin-only nav entry** in `app.component.ts`. ("Locations" stays — visible to any authenticated user; mutations gated by `isAnyAdmin`. Added in S-050 boyscout.)
- [ ] **Aircraft-edit ownerClub picker** — review `showOwnerSelect = session.isSystemAdmin` (`aircraft-edit.page.ts:444`). If (a): sysadmin doesn't create aircraft anymore (CLUB_ADMIN always picks own club implicitly) → remove the gate. If (b): keep the gate but route through the dedicated cross-club endpoint.
- [ ] **Aircraft store `canMutate`** — drop sysadmin from any role checks; CLUB_ADMINISTRATOR only.
- [ ] **Verify pilot1 can browse `/locations`** (read-only — no New/Edit/Delete affordances). Already gated; just confirm.

### Tests

- [ ] `LocationsControllerIT` — remove the SYSTEM_ADMINISTRATOR cases for POST/PUT/DELETE; add 403 cases.
- [ ] `LocationsAdminControllerIT` — deleted.
- [ ] `AircraftsControllerIT` — same shape. Add `@TenantId` cross-tenant test (CLUB_ADMIN of A cannot see/edit B's aircraft).
- [ ] SPA: remove `admin-locations.spec.ts` (if exists); add Playwright spec for clubadmin1 creating a location + assigning it as homebase on an aircraft (the original UX flow that surfaced this story).

## Out of scope

- Cutover / bulk-import endpoints. Tracked separately; still sysadmin-only and explicitly cross-club. `Tenants.runAs` stays as the in-process seam.
- Sysadmin user-management UI. Not yet built.
- Scheduled-job tenant context (`UnscopedTenantContext`, S-023). Orthogonal — that's about *no* tenant context, not impersonation.

<!-- modernize-refine: start -->

## Design notes

**Working assumption: option (a) — charter dropped, `ownerClubId` mandatory.** Deferring `@TenantId` leaves Aircraft as the only structurally-unscoped tenant aggregate for another sprint and forces a second pass on `AircraftAccess`. Implementer proceeds on (a); if operator later picks (b), a follow-up adds a synthetic CHARTER club or dedicated cross-club endpoints.

**Module impact (one line each):**
- `aircraft/domain/Aircraft.java` — `@TenantId` on `ownerClubId`; field becomes `@NotNull` in Java.
- `aircraft/application/AircraftAccess.java` — delete `canMutateAircraft` + `canRegisterAircraft` + `canSeeOwnerOnlyFields` (now structural via discriminator); keep `canRecordCounter` (genuinely cross-role); trim `canOperateAircraft` to role-only (owner-club equality is structural).
- `aircraft/web/AircraftsController.java` — POST/PUT/DELETE drop SYSADMIN co-allow; PUT/DELETE collapse to `hasRole('CLUB_ADMINISTRATOR')`. `transferOwnership` keeps `hasRole('SYSTEM_ADMINISTRATOR')` and runs **inside `Tenants.runAs(currentOwnerClubId, …)`** to load the source row before re-issuing under the new owner (Hibernate forbids mutating `@TenantId` columns on managed entities).
- New Flyway `V9__aircraft_tenant_id.sql` — backfill `owner_club_id = '<seed-club-1>'` for NULLs, `ALTER COLUMN SET NOT NULL`, drop `ix_aircraft_owner_club` partial predicate (column is non-null), drop legacy `ck_aircraft_owner_xor` invariant (no charter case under (a)).
- `locations/web/LocationsAdminController.java` + `LocationsAdminControllerIT.java` — delete.
- `locations/web/LocationsController.java` POST/PUT/DELETE — drop SYSADMIN from `@PreAuthorize`.
- `database/tenant-rules.yaml` — flip Aircraft + aircraft-state-history + aircraft-operating-counter from `cross-tenant` to `tenant-scoped`. **Load-bearing for S-024** — must ship in same commit as the `@TenantId` annotation or the leakage CI asserts the old contract.
- `arch/AircraftCrossTenantGuardTest.java` — invert (assert `@TenantId` IS present) or delete.
- `Tenants.java` Javadoc + ArchUnit — `Tenants.runAs` keeps the seam (cutover / OGN / scheduled jobs) but only `transferOwnership` still uses it from `main/`. Add a `TenantBypassGuardTest` rule: "no production class outside `platform.tenancy` may call `Tenants.runAs` except `transferOwnership`" — makes the carve-out structural.
- `web/src/app/features/admin/locations/**` — delete folder. SPA + server delete + OpenAPI regen + generated `api/generated/locations-admin/` cleanup must land in **one commit** (partial state breaks SPA build or 404s silently).
- `web/src/app/core/session/session.store.ts` — add `isClubAdmin` computed. `locations-edit.page.ts:341` + `aircraft-edit.page.ts:441` flip from `isAnyAdmin` → `isClubAdmin` so sysadmin doesn't see Save buttons that 403 on submit.
- `web/src/app/app.component.ts` — drop `/admin/locations` nav entry; hide `/locations` + `/aircraft` entries when `isSystemAdmin` (sysadmin has no tenant context, list-pages would render empty — see open question 3).
- `V8__dev_user_seed.sql` — update comment block (drop the "S-159 design pivot, sysadmin operates only on cross-cutting resources" phrasing — the JWT-minimal pivot was rejected; the seed itself stays correct).

**ADR 0008 amendment (2026-05-23, S-159):**
> SYSTEM_ADMINISTRATOR is no longer co-allowed on tenant-scoped HTTP endpoints. Sysadmin's HTTP rights are limited to cross-cutting resources (Clubs catalog, sysadmin user mgmt, cutover / bulk import). The `/api/v1/admin/locations/{clubId}` impersonation pattern introduced in S-049c is withdrawn; `Tenants.runAs(...)` survives only as an in-process seam for scheduled jobs, OGN ingestion, cutover import, and the cross-tenant `transferOwnership` endpoint — never wired through to an HTTP path that exists to "act as a club from the outside." Tenant data is acted on by members of that tenant; nothing else. Reclassify the `tenant-rules.yaml` 2026-05-16 Aircraft-cross-tenant amendment as superseded.

**Per ADR 0022 directive 2:** the V9 migration adds only NOT NULL + FK + index (all structural). No CHECK constraints, no triggers, no generated columns; the dropped `ck_aircraft_owner_xor` was a charter-era guard that the operator-chosen schema invalidates anyway.

## Edge cases & hidden requirements

- **`Flight` / `AircraftReservation` cross-club FK survival under `@TenantId`.** ADR 0008 §A says "FK loads by id are not tenant-filtered" — but that only holds for `find`/`getReference`. `@ManyToOne` traversal from a Flight in club B to a club-A-owned aircraft WILL append the discriminator and return null. `V4__reservations_planning_accounting.sql:628` currently promises cross-tenant survival; that promise breaks under (a) unless the operator confirms charter is gone and operator-club always equals owner-club at the per-flight level. **Reverse the V4:628 + V3:672 + tenant-rules.yaml:343-347 rationale paragraphs** in the same migration commit. S-058 (Flight) + S-068 (Reservation) inherit the new contract.
- **`canRecordCounter` cross-club regression.** `AircraftsController.java:170-173` documents "a tow pilot at club B records hours for a club-A charter aircraft." Under (a) + `@TenantId`, club-B FLIGHT_OPS can no longer load the aircraft. Either delete the comment + accept the regression (charter is gone), or load via `Tenants.runAs(ownerClubId, …)` after a one-shot unscoped lookup. **Surfaced as open question 2.**
- **Cross-tenant mutation behavior flips 403 → 404.** Per the established IDOR pattern (`LocationsController.java:43-47`): a CLUB_ADMIN of A asking for B's aircraft no longer gets 403; the row is invisible → 404. `AircraftsAuthorizationIT.clubAdminOfOtherClub_cannotMutate_ownedAircraft` flips assertion from FORBIDDEN to NOT_FOUND.
- **`AircraftCrossTenantGuardTest` contradicts the new direction.** Invert or delete; story AC didn't call it out and the implementer will discover the failing build mid-flight.
- **SPA `LocationsStore` + `AircraftStore` bootstrap behavior for sysadmin.** Both stores `loadAll()` on `onInit`. Sysadmin's `NO_TENANT` context → server returns `[]`, no error. Lists render empty. Either hide the nav entries for sysadmin (recommended) or render the empty state — see open question 3.
- **`AircraftCreateRequest.ownerClubId` becomes a mass-assignment vector.** Under (a), `ownerClubId` should be derived from the principal's `clubId` claim at the controller, not read from the request body. Drop the field from the DTO; the discriminator + JWT-derived tenant write it for free.
- **Test fixtures mint sysadmin WITH `clubId` today.** `AircraftsControllerIT:63` + `LocationsControllerIT:57` give sysadmin a `clubId` claim the production realm doesn't carry. After S-159, mint sysadmin **without** `clubId` so the IT matches production token shape and catches any latent resolver code that reads the claim unconditionally.
- **Proffix machine client unaffected.** `realm-export.json:2406` — service account has empty role list (`"alpenflight-proffix": []`); Proffix uses scope-gated reads, not role-gated writes. No regression risk on the integration path.

## Security plan

**Authz delta** (the actual change):
- `LocationsController` POST/PUT/DELETE: `hasAnyRole('CLUB_ADMINISTRATOR', 'SYSTEM_ADMINISTRATOR')` → `hasRole('CLUB_ADMINISTRATOR')`. Intentional 403 for sysadmin.
- `AircraftsController` POST: drop the sysadmin co-allow at line 105. POST/PUT/DELETE collapse to `hasRole('CLUB_ADMINISTRATOR')`. `transferOwnership` (line 139) keeps `hasRole('SYSTEM_ADMINISTRATOR')` and is the **only** production caller of `Tenants.runAs` after S-159.
- `AircraftAccess.canSeeOwnerOnlyFields` collapses to `isAuthenticated()` (every visible aircraft now belongs to the caller's club; DTO field-hiding is moot).

**Residual checks `@TenantId` does NOT subsume:**
- Role-within-tenant (CLUB_ADMIN required to delete; FLIGHT_OPS required for counters) — keep `@PreAuthorize("hasRole(...)")` on every mutation.
- `canRecordCounter` — see open question 2.
- Audit-trail `userLookup.resolveUserIdFor(jwt)` — still needed for CLUB_ADMIN soft-delete actor recording (Locations + Aircraft).

**Operational consequences:**
- 404 not 403 on cross-tenant — document in IT + `AircraftsController` class Javadoc; this is the IDOR contract.
- `Tenants.runAs` ArchUnit guard prevents the seam from being re-introduced ad-hoc post-`LocationsAdminController` deletion.
- Generated TS `locations-admin/*` client + e2e spec + SPA pages — coordinated delete in one commit (sequencing surfaced in design notes).

## Test plan

**Pyramid:**
- **Unit (Vitest, alpenflight/web):** 1–2 — `aircraft.store.canMutate` shrinks to CLUB_ADMIN-only; `aircraft-edit.page.ts:444` `showOwnerSelect` collapses to `false`. No DOM specs.
- **Integration (Spring `@SpringBootTest`):** new `AircraftsTenantIsolationIT` mirroring `LocationsTenantIsolationIT.java:65` (4 cases: filter-isolates, no-tenant-empty, no-tenant-FK-fail, global-immat-uniqueness-across-tenants — immatriculation stays regulator-global unlike ICAO). `LocationsAuthorizationIT` matrix flip (SYSADMIN row → 403). `LocationsAdminControllerIT` **deleted**. `AircraftsControllerIT` re-baselined from `sysadminToken` → `clubAdminToken` (CLUB_ADMIN of seed-club-1). `AircraftsAuthorizationIT` rewrite (sysadmin rows → 403 or DELETE; cross-club mutation FORBIDDEN → NOT_FOUND). `AircraftCrossTenantGuardTest` inverted or deleted.
- **E2E (Playwright):** 1 deleted (`admin/locations-admin.spec.ts`), 1 added (`masterdata/aircraft-homebase-assignment.spec.ts` — clubadmin1 creates location → assigns as homebase → reload → persists). Existing `masterdata/aircraft-crud.spec.ts:611` drops `aircraft-owner-select` from visible-field inventory.

**Non-obvious cases:**
- **Behavior removal:** `anyAuthenticatedUser_canReadAnyAircraft` (`AircraftsAuthorizationIT.java:157`) gets **deleted** — full-fleet visibility is gone by construction once `@TenantId` lands. Document the contract change in the test deletion commit.
- **Charter test sweep:** under (a), 5 tests reference null-owner / SYSADMIN-only mutation paths (`clubAdmin_cannotRegisterAircraft_withNullOwner`, etc.). Drop with the schema change.
- **Pilot1 read-only browse:** extend existing locations-crud spec (don't add a new file) — switch principal to FLIGHT_OPERATOR, assert `locations-new-button` count(0) and per-row edit/delete icons absent.

**Fixture notes:** mock-auth (per FE CLAUDE.md §8); V8 seeds clubadmin1/pilot1 ready to use. `JwtTestFixture` shape change is load-bearing — mint sysadmin **without** `clubId` (matches production). S-024's parameterised property-based gate is still todo; the new `AircraftsTenantIsolationIT` becomes one row in that matrix when S-024 lands.

## Performance plan

(N/A — story removes role co-allows + adds one Hibernate discriminator filter. The discriminator runs on an already-indexed column (`ix_aircraft_owner_club_active` from V3, now non-partial post-V9). No hot paths added; no new caches; no new queries.)

## Open design questions

1. **Charter / null-owner aircraft + ownership transfer.** Working assumption is (a) — drop charter, `ownerClubId` mandatory, transfer-ownership becomes a sysadmin-only cross-tenant endpoint wrapped in `Tenants.runAs`. Alternative (b): keep null-owner + add dedicated `POST /aircraft/charter` + `PUT /aircraft/{id}/owner` endpoints that don't filter by `@TenantId`. **Recommend (a)** (uniform model, smaller seam; (b) introduces a second authz regime). Operator confirms before implement starts — affects schema migration shape.
2. **Counter recording cross-club case.** `canRecordCounter` today allows club-B FLIGHT_OPS to record hours for a club-A charter aircraft. Under (a) + `@TenantId`, club-B can't load the aircraft. Two options: **(i)** delete the comment + accept the regression (charter is gone under (a) anyway); **(ii)** unscoped lookup wrapped in `Tenants.runAs(ownerClubId)`. **Recommend (i)** — the charter case dies with (a); cross-club counter recording was a charter-era affordance with no analog in a uniform-ownership world.
3. **SPA chrome for sysadmin** (no `clubId` → tenant-scoped list-pages render empty). Three options: **redirect** to `/clubs` with a toast, **404**, or **render empty-state**. **Recommend hide the nav entries entirely for sysadmin** (combining solution-architect's "redirect" with requirements-engineer's surfacing of the same problem on `/aircraft`): sysadmin's nav becomes `Clubs` + future sysadmin user mgmt only; no broken-looking empty pages. Direct URL navigation by sysadmin still works — page shows the empty state. Operator confirms.

<!-- modernize-refine: end -->
