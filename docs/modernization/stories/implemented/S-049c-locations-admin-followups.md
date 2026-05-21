---
id: S-049c
title: Locations admin route + platform follow-ups from S-049b
epic: E-06
status: done
started_at: 2026-05-21
github_issue: 92
github_pr: 93
merged: true
merged_at: 2026-05-21
depends_on: [S-049b]
origin: follow-up-from-S-049b
acceptance:
  - **F1 — `Tenants.runAs(...)` escape hatch.** New `ch.alpenflight.platform.tenancy.Tenants` class exposes `runAs(UUID clubId, Runnable body)` + `runAs(UUID clubId, Supplier<T> body)` for production code that legitimately needs to operate outside the JWT-driven tenant context. Pushes/restores the existing tenancy carrier; tests cover push, restore, nested calls, and that mid-run code reads the override.
  - **F2 — Principal user-id resolution.** `UserPrincipalLookup` (renamed from `UserTenantLookup` mid-story — the class is dual-purpose now: tenant + user-id) gains `resolveUserIdFor(Jwt)` returning `user.id` (not `keycloak_sub`). `LocationsController.principalUserId(jwt)` wires through the new lookup; the soft-delete audit trail now records the internal user id consistently across realm-token + federated-token paths (federated subs still resolve via the `keycloak_sub → user` mapping S-052 ships).
  - **F3 — `/admin/locations/{clubId}/*` admin surface.** New `LocationsAdminController` (`@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")`) wraps `LocationsService` calls in `Tenants.runAs(clubId, ...)`; SYSTEM_ADMIN can list / read / create / update / delete any club's Locations without depending on their JWT `clubId` claim. SPA gains an `/admin/locations` route with a club picker, full per-row list / edit / create / delete (round 2 added the edit + create UI via a new `AdminLocationsEditPage` that calls the admin endpoints directly, reusing the shared form mapper), and a sysadmin-only top-bar nav entry.
  - **SPA boyscout — `canMutate` regression.** Post-S-049b the server opened mutation to CLUB_ADMIN but the SPA `LocationsListPage` + `LocationsEditPage` still gate UI affordances on `isSystemAdmin()`. Open to `isSystemAdmin() || isClubAdmin()`. Update the misleading "Reference data — changes apply to all clubs" banner; Locations are per-club now. Round 2 also wired transloco for both Locations banners and the new admin page.
  - **Round-2 boyscout renames.** `TenantTestContextAccess` → `TenantContextCarrier` (the carrier now serves production `Tenants.runAs` in addition to the test seam, so "test" in the name no longer matches reality). `UserTenantLookup` → `UserPrincipalLookup` (the class is dual-purpose: tenant + internal user-id lookup since F2).
  - **Dev-server proxy boyscout — deep-path proxying.** `alpenflight/web/proxy.conf.json` flipped from `"/api/v1/*"` (single-segment glob under `@angular/build:dev-server` / Vite) to `"/api/v1/**"`. The old pattern matched `/api/v1/clubs` but silently let `/api/v1/admin/locations/clb-<uuid>` (and any other 2+ segment path like `/api/v1/locations/{id}`) fall through to `index.html`, surfacing as `200 OK` with HTML body — discovered while exercising F3 against the live dev stack. Inline comment in the file spells out the trap so it can't regress quietly.
estimate: M
adr_refs: [0008]
parity_test: none
refined: false
---

## Context

Three follow-ups surfaced by S-049b's reviewer panel + one SPA regression introduced by the S-049b reclassification:

- **F1** was a deferred ADR 0008 follow-up (`Tenants.runAs(...)` named in the design notes of multiple prior stories but never implemented). S-049b's security-reviewer flagged that the references were "promising a feature that isn't there."
- **F2** was a security-reviewer finding on the audit trail — `deletedByUserId` recorded `keycloak_sub` instead of `user.id`, and silently null for federated OIDC subjects.
- **F3** was the open design question at the bottom of S-049b's archived body — operator picked option (b) "build /admin/locations now" on 2026-05-21.
- The SPA regression is a boyscout fix: the `canMutate = isSystemAdmin()` gate on the Locations pages is wrong post-S-049b (CLUB_ADMIN now mutates own-club). Server tests pass; UI invisibly hides the affordances.

Bundled into one PR per operator directive (2026-05-21).
