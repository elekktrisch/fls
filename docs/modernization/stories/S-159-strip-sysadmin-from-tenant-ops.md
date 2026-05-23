---
id: S-159
title: Strip SYSTEM_ADMINISTRATOR from tenant-scoped ops; make Aircraft tenant-scoped
epic: E-06
status: todo
estimate: M
depends_on: [S-049c, S-050]
origin: rework
origin_story: S-050
adr_refs: [0008]
parity_test: none
refined: false
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

## Decisions to capture during refine

- (a) vs (b) above for charter + ownership transfer.
- Whether the SPA Locations list page should redirect or 404 when a user with no `clubId` (i.e. sysadmin) lands on it. Default lean: redirect to `/clubs` with a one-line notice; sysadmin has nothing to see here.
