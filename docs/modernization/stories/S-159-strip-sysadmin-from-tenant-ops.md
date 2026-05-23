---
id: S-159
title: Strip SYSTEM_ADMINISTRATOR from tenant-scoped ops; make Aircraft tenant-scoped via managing club
epic: E-06
status: in_progress
estimate: M
depends_on: [S-049c, S-050]
origin: rework
origin_story: S-050
adr_refs: [0007, 0008, 0022]
parity_test: none
refined: true
refined_at: 2026-05-23
refined_specialists: [requirements, solution, qa, security]
started_at: 2026-05-23
github_issue: 105
github_pr: 102
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

- [ ] **Strip `SYSTEM_ADMINISTRATOR` from tenant-scoped `@PreAuthorize`** on `LocationsController` POST/PUT/DELETE and `AircraftsController` POST/PUT/DELETE/state/counter/transferOwnership.
- [ ] **Delete `LocationsAdminController` + its IT.** No replacement; sysadmin doesn't act on tenant data.
- [ ] **Aircraft tenant scoping via new `managing_club_id`.** Add `Aircraft.managingClubId` `@TenantId`, NOT NULL, FK to club. Backfill `managing_club_id = COALESCE(owner_club_id, seed-club-1)`. `owner_club_id` + `aircraft_owner_person_id` remain nullable ownership metadata, not @TenantId.
- [ ] **transferOwnership becomes CLUB_ADMIN-only** (managing tenant unchanged → no `Tenants.runAs` needed; cross-tenant managing-club move is a deferred sysadmin op).
- [ ] **Drop `AircraftCreateRequest.ownerClubId`** (A04 mass-assignment — service defaults `owner_club_id = managing_club_id`).
- [ ] **AircraftAccess collapses.** Delete `canMutateAircraft` / `canRegisterAircraft` / `canOperateAircraft` / `canRecordCounter` / `canSeeOwnerOnlyFields` — all subsumed by `@TenantId` + role gates. Update controller `@PreAuthorize` to plain role expressions.
- [ ] **Cross-tenant leakage test (S-024) extended to Aircraft.** Aircraft becomes a row in the per-repository parity matrix.

### SPA

- [ ] **Delete `/admin/locations` route + `AdminLocationsListPage` + `AdminLocationsEditPage`** under `features/admin/locations/`; remove the OpenAPI-generated `api/generated/locations-admin/`.
- [ ] **Drop the "Locations admin" sysadmin-only nav entry** in `app.component.ts`.
- [ ] **Hide `/locations` + `/aircraft` nav entries when `isSystemAdmin`** (Q3 — sysadmin has no tenant; list pages would render empty).
- [ ] **Aircraft-edit ownerClub picker** — drop `showOwnerSelect` (managing club is implicit).
- [ ] **`canMutate` flips `isAnyAdmin` → `isClubAdmin`** on aircraft + locations list/edit pages so sysadmin doesn't see Save buttons that 403 on submit.

### Tests

- [ ] `LocationsControllerIT` — remove SYSTEM_ADMINISTRATOR cases for POST/PUT/DELETE; add 403 cases.
- [ ] `LocationsAdminControllerIT` — deleted.
- [ ] `AircraftsControllerIT` — rebaseline from `sysadminToken` → `clubAdminToken` (seed-club-1).
- [ ] `AircraftsAuthorizationIT` — drop full-fleet read + null-owner-SYSADMIN tests; flip cross-club mutation FORBIDDEN → NOT_FOUND; transferOwnership matrix → CLUB_ADMIN of managing club.
- [ ] New `AircraftsTenantIsolationIT` — mirror `LocationsTenantIsolationIT` (filter isolates, no-tenant empty + FK-fail, global immatriculation uniqueness across tenants).
- [ ] `AircraftCrossTenantGuardTest` — inverted to assert `@TenantId` is present.

## Out of scope

- Cutover / bulk-import endpoints. Tracked separately; still sysadmin-only and explicitly cross-club. `Tenants.runAs` stays as the in-process seam (audit listener + RequestAuditFilter).
- Sysadmin user-management UI. Not yet built.
- "External organisation owns this aircraft" picker (other club from catalog / external org entity). Today every new aircraft is implicitly owned by its managing club; this story doesn't add a cross-club ownership UI.
- "Re-register aircraft under different managing club" — a future sysadmin-only cross-tenant op (would re-key `managing_club_id`); not in S-159.
- Scheduled-job tenant context (`UnscopedTenantContext`, S-023). Orthogonal — that's about *no* tenant context, not impersonation.

<!-- modernize-refine: start -->

## Design notes

**Operator-chosen schema (2026-05-23): managing-club + ownership split.** Every aircraft is registered by exactly one tenant — the *managing club*. Ownership is independent metadata with three derived cases: own-club (`owner_club_id == managing_club_id`), other organisation (`owner_club_id != managing_club_id`, or `NULL` if not in the Clubs catalog), private person (`aircraft_owner_person_id != null`). `@TenantId` lives on `managing_club_id` (NEW, NOT NULL). `owner_club_id` + `aircraft_owner_person_id` remain nullable, neither is `@TenantId`. transferOwnership changes ownership only, not the managing tenant — so it collapses to a regular CLUB_ADMIN endpoint. "Re-register under a different managing club" is a separate sysadmin-only cross-tenant operation, deferred (no UI today).

**Module impact (one line each):**
- `aircraft/domain/Aircraft.java` — NEW field `managingClubId` (`@TenantId`, `@NotNull`); `register(...)` takes it as first arg. `ownerClubId` stays as ownership metadata. Add `transferOwnership(...)` overload signature unchanged.
- `aircraft/application/AircraftAccess.java` — delete `canMutateAircraft` + `canRegisterAircraft` + `canSeeOwnerOnlyFields` (now structural via `managing_club_id` discriminator); delete `canOperateAircraft` (collapses to role gate — owner-club equality is structural via @TenantId); delete `canRecordCounter` cross-club affordance (charter regression accepted per Q2 (i)).
- `aircraft/web/AircraftsController.java` — POST/PUT/DELETE/state/counter collapse to `hasRole('CLUB_ADMINISTRATOR')` (PUT/DELETE) / `hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')` (state + counter). `transferOwnership` becomes CLUB_ADMIN-only (no longer sysadmin; no `Tenants.runAs` needed — managing club stays).
- `aircraft/application/AircraftDtos.java` — drop `ownerClubId` from `AircraftCreateRequest` (derived from JWT; A04 mass-assignment defense). Keep `aircraftOwnerPersonId`; add to `AircraftTransferOwnershipRequest` as before.
- `aircraft/application/AircraftsService.java` — `registerAircraft` reads `managingClubId` from `Tenants.current()` (or the resolver's view); writes `owner_club_id = managing_club_id` by default (own-club case). DTO mappers pass through both fields.
- New Flyway `V10__aircraft_managing_club_id.sql` — `ALTER TABLE aircraft ADD COLUMN managing_club_id UUID`; backfill `managing_club_id = COALESCE(owner_club_id, '019e30c3-2c00-7001-8000-000000000001')` (seed-club-1); `SET NOT NULL`; FK to club; `CREATE INDEX ix_aircraft_managing_club ON aircraft (managing_club_id)`.
- `locations/web/LocationsAdminController.java` + `LocationsAdminControllerIT.java` — delete (story core).
- `locations/web/LocationsController.java` POST/PUT/DELETE — drop SYSADMIN from `@PreAuthorize`.
- `database/tenant-rules.yaml` — flip `Aircrafts` + `AircraftAircraftStates` + `AircraftOperatingCounters` from `cross-tenant` to `tenant-scoped`; rename `tenant_column_legacy` → `OwnerId` (the legacy managing-club column, mapped to `managing_club_id`). Load-bearing for S-024 — must ship in same commit as the `@TenantId` annotation.
- `arch/AircraftCrossTenantGuardTest.java` — invert: assert that `Aircraft` is annotated; child entities transitively scoped via FK.
- `Tenants.java` Javadoc — drop the LocationsAdminController bullet from "anticipates" since the only main/ caller of runAs becomes the audit-listener + RequestAuditFilter.
- `web/src/app/features/admin/locations/**` — delete folder + route. SPA + server delete + OpenAPI regen + generated `api/generated/locations-admin/` cleanup land in **one commit**.
- `web/src/app/core/session/session.store.ts` — `isClubAdmin` computed already exists (line 62); switch `canMutate` consumers from `isAnyAdmin` → `isClubAdmin` so sysadmin doesn't see Save buttons that 403 on submit. Keep `isAnyAdmin` for any caller that still wants the disjunction.
- `web/src/app/features/aircraft/edit/aircraft-edit.page.ts:444` — drop `showOwnerSelect` (no cross-club registration; managing club is implicit).
- `web/src/app/features/locations/{list,edit}/*.page.ts` + `features/aircraft/{list,edit}/*.page.ts` — flip `canMutate = session.isAnyAdmin` → `session.isClubAdmin`.
- `web/src/app/app.component.ts` — drop `/admin/locations` nav entry; hide `/locations` + `/aircraft` entries when `isSystemAdmin` (sysadmin has no tenant; list pages would render empty).
- E2E: delete `admin/locations-admin.spec.ts` if present; existing `masterdata/aircraft-crud.spec.ts:611` drops the `aircraft-owner-select` visibility check.

**ADR 0008 amendment (2026-05-23, S-159) — proposed (operator decides at finalize):**
> SYSTEM_ADMINISTRATOR is no longer co-allowed on tenant-scoped HTTP endpoints. Sysadmin's HTTP rights are limited to cross-cutting resources (Clubs catalog, sysadmin user mgmt, cutover / bulk import). The `/api/v1/admin/locations/{clubId}` impersonation pattern introduced in S-049c is withdrawn. Aircraft becomes tenant-scoped via a NEW `managing_club_id` `@TenantId` column; `owner_club_id` + `aircraft_owner_person_id` stay as ownership metadata (independent of tenancy). `Tenants.runAs(...)` survives only as an in-process seam for scheduled jobs, OGN ingestion, cutover import, and the audit listener — never wired through to an HTTP path that exists to "act as a club from the outside." Tenant data is acted on by members of that tenant; nothing else. Reclassify the `tenant-rules.yaml` 2026-05-16 Aircraft-cross-tenant amendment as superseded.

**Per ADR 0022 directive 2:** the V10 migration adds only NOT NULL + FK + index (all structural). No CHECK constraints / triggers / generated columns. The owner-kind discriminator (own-club / other-org / person) is a domain-layer derivation, not a schema enum.

## Edge cases & hidden requirements

- **Cross-tenant mutation behavior flips 403 → 404.** Per the established IDOR pattern (`LocationsController.java:43-47`): a CLUB_ADMIN of A asking for B's aircraft no longer gets 403; the row is invisible → 404. `AircraftsAuthorizationIT.clubAdminOfOtherClub_cannotMutate_ownedAircraft` flips assertion from FORBIDDEN to NOT_FOUND.
- **Full-fleet visibility goes away.** `anyAuthenticatedUser_canReadAnyAircraft` (`AircraftsAuthorizationIT.java:157`) gets deleted — under @TenantId on managing_club_id, club B can no longer see club A's aircraft. The "tow pilot of B sees A's charter" use case is intentionally regressed (Q2 (i) — accept the charter-era regression).
- **`AircraftCreateRequest.ownerClubId` becomes mass-assignment vector.** Drop from the DTO; the discriminator + JWT-derived tenant write `managing_club_id` for free. The service defaults `owner_club_id = managing_club_id` (own-club case) — future stories may add an "external organisation owns this aircraft" picker that points at another club in the Clubs catalog or at a private-person.
- **Test fixtures mint sysadmin WITH `clubId` today.** `AircraftsControllerIT:63` + `LocationsControllerIT:57` give sysadmin a `clubId` claim the production realm doesn't carry. After S-159, mint sysadmin **without** `clubId` so the IT matches production token shape and catches any latent resolver code that reads the claim unconditionally.
- **Realm seed unchanged.** `sysadmin` keeps SYSTEM_ADMINISTRATOR; still no clubId claim (correct now — was correct before too).
- **`AircraftCrossTenantGuardTest` contradicts the new direction.** Invert (assert `@TenantId` IS present on `Aircraft.managingClubId`) and drop the field-level "no @TenantId on aggregate internals" rule (the parent's discriminator carries them).
- **Proffix machine client unaffected.** `realm-export.json:2406` — service account has empty role list; Proffix uses scope-gated reads, not role-gated writes. No regression risk.

## Security plan

**Authz delta:**
- `LocationsController` POST/PUT/DELETE: `hasAnyRole('CLUB_ADMINISTRATOR', 'SYSTEM_ADMINISTRATOR')` → `hasRole('CLUB_ADMINISTRATOR')`. Intentional 403 for sysadmin.
- `AircraftsController` POST/PUT/DELETE collapse to `hasRole('CLUB_ADMINISTRATOR')`. State change + counter → `hasAnyRole('CLUB_ADMINISTRATOR', 'FLIGHT_OPERATOR')`. `transferOwnership` becomes `hasRole('CLUB_ADMINISTRATOR')` (no longer sysadmin; managing tenant unchanged, only ownership metadata flips).
- `LocationsAdminController` — deleted (no impersonation path).
- Service-layer SpEL helpers (`AircraftAccess`) — deleted; `@TenantId` carries the tenant-equality check structurally.

**Residual checks `@TenantId` does NOT subsume:**
- Role-within-tenant (CLUB_ADMIN to mutate; FLIGHT_OPERATOR to record counters) — keep `@PreAuthorize("hasRole(...)")` on every mutation.
- Audit-trail `userLookup.resolveUserIdFor(jwt)` — still needed for CLUB_ADMIN soft-delete actor recording.

**Operational consequences:**
- 404 not 403 on cross-tenant — IDOR contract documented in `AircraftsController` Javadoc.
- Generated TS `locations-admin/*` client + e2e spec + SPA pages — coordinated delete in one commit.

## Test plan

**Pyramid:**
- **Unit (Vitest, alpenflight/web):** flip `canMutate` consumers from `isAnyAdmin` → `isClubAdmin` and adjust the existing aircraft.store specs.
- **Integration (Spring `@SpringBootTest`):** new `AircraftsTenantIsolationIT` mirroring `LocationsTenantIsolationIT.java:65` (4 cases: filter-isolates, no-tenant-empty, no-tenant-FK-fail, global-immat-uniqueness-across-tenants — immatriculation stays regulator-global). `LocationsAuthorizationIT` matrix flip (SYSADMIN row → 403). `LocationsAdminControllerIT` **deleted**. `AircraftsControllerIT` re-baselined from `sysadminToken` → `clubAdminToken`. `AircraftsAuthorizationIT` rewrite: drop full-fleet read; cross-club mutation FORBIDDEN → NOT_FOUND; drop sysadmin-only mutation tests; transfer-ownership becomes CLUB_ADMIN.
- **E2E (Playwright):** if `admin/locations-admin.spec.ts` exists, delete; `aircraft-crud.spec.ts:611` drops the owner-select inventory check.

**Fixture notes:** mock-auth per `next/web/CLAUDE.md` §8; V8 seeds clubadmin1/pilot1. Mint sysadmin **without** `clubId` (matches production realm).

## Performance plan

(N/A — adds one `@TenantId` discriminator on an already-indexed column. No hot paths added; no new caches; no new queries.)

<!-- modernize-refine: end -->
