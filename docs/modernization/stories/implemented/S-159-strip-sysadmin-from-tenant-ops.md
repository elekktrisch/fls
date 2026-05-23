---
id: S-159
title: Strip SYSTEM_ADMINISTRATOR from tenant-scoped ops; make Aircraft tenant-scoped via managing club
epic: E-06
status: done
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
done_at: 2026-05-23
merged: true
merged_at: 2026-05-23
github_issue: 105
github_pr: 102
---

## Context

S-049c gave SYSTEM_ADMINISTRATOR a per-club impersonation path
(`/api/v1/admin/locations/{clubId}` + `Tenants.runAs`) and co-allowed sysadmin
on the regular tenant-scoped controllers. In practice this was confusing UX
without value: sysadmin has no `clubId` claim, so reads return empty and
writes fail at the FK. Operator decision (2026-05-22): remove the
impersonation pattern entirely. Sysadmin's HTTP rights are cross-cutting only
(Clubs catalog, future sysadmin user mgmt, future cutover / bulk import per
[[project-legacy-bulk-import]]). Tenant-scoped data is CLUB_ADMIN-only.

`Aircraft` becomes structurally tenant-scoped via a NEW `managing_club_id`
`@TenantId` column — separate from `owner_club_id` (ownership metadata,
nullable, may differ from the managing tenant). Three derived ownership
cases: own-club (`owner_club_id == managing_club_id`), other organisation
(`owner_club_id != managing_club_id`, or `NULL` when external to the Clubs
catalog), private person (`aircraft_owner_person_id`).

## Acceptance criteria

- [ ] SYSTEM_ADMINISTRATOR stripped from tenant-scoped `@PreAuthorize` on `LocationsController` + `AircraftsController`.
- [ ] `LocationsAdminController` + its IT deleted; `/admin/locations` SPA route + pages + generated client deleted.
- [ ] `Aircraft.managingClubId` `@TenantId` NOT NULL, V10 migration backfills.
- [ ] `transferOwnership` becomes CLUB_ADMIN-only (managing tenant unchanged).
- [ ] `AircraftCreateRequest.ownerClubId` dropped (A04 mass-assignment).
- [ ] `AircraftAccess` SpEL bean deleted; controllers use plain role gates.
- [ ] Sysadmin nav hides tenant-scoped entries; `tenantRequiredGuard` redirects deep links.
- [ ] `canMutate` flips `isAnyAdmin` → `isClubAdmin` on aircraft + locations pages.
- [ ] New `AircraftsTenantIsolationIT` (mirrors `LocationsTenantIsolationIT`); existing ITs rebaselined; `AircraftCrossTenantGuardTest` inverted; S-024 leakage sweep extended to Aircraft.

## Out of scope

- Cutover / bulk-import endpoints. `Tenants.runAs` stays as the in-process seam (audit listener + `RequestAuditFilter`).
- Sysadmin user-management UI.
- "External organisation owns this aircraft" picker (foreign club / external org entity). New aircraft default to own-club ownership; transfer-ownership flips it.
- "Re-register aircraft under different managing club" — deferred sysadmin-only cross-tenant op (would re-key `managing_club_id`).
- Intra-tenant PII redaction on `AircraftDetail` owner-only fields (`comment` / `flarmId` / `mtom` / `noise*` / `spotLink`). Pre-S-159 these were visible to all members of the owning club; post-S-159 the same — `@TenantId` filters cross-tenant only. A future defense-in-depth story could restore role-within-tenant redaction.

## Review

ADR 0008 amendment applied (see ADR's "Amendment — 2026-05-23 (S-159)"
section).
