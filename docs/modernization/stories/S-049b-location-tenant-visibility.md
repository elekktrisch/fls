---
id: S-049b
title: Per-tenant Location visibility
epic: E-06
status: todo
depends_on: [S-049]
acceptance:
  - New `club_location_visibility` join table (`PK(club_id, location_id)`, `@TenantId` on `club_id`) with `is_visible BOOLEAN NOT NULL DEFAULT true`.
  - Visibility endpoints — `CLUB_ADMINISTRATOR` can flip visibility for own club only (resolved from `@TenantId`); `SYSTEM_ADMINISTRATOR` can flip for any club (path-param `clubId`).
  - `GET /api/v1/locations` (the S-049 endpoint) enriches each row with `isVisibleHere: boolean` computed from the join for the caller's tenant (default `true` when no row exists).
  - A "for-flight-edit" filtered list endpoint that returns only visible Locations for the caller's tenant — consumed by Flight-edit / Person-edit dropdowns.
  - SPA: bulk-toggle table on the Locations list page (CLUB_ADMIN scope) showing visibility per row + a "make all visible / make all hidden" header action.
  - Parity port verifies visibility-toggle round-trips persistence + cache invalidation via `MUTATION_BUS`.
estimate: M
adr_refs: [0005, 0008, 0026]
parity_test: none
origin: split-from-S-049
---

## Context

Operator confirmed during S-049 refine (2026-05-20) that Locations are shared reference data (sacred-cow shared airports) BUT each tenant should be able to toggle visibility per Location — so a small Swiss club doesn't see every glider strip in Africa cluttering their Flight-edit dropdown. S-049 ships the reference-data CRUD; this story adds the per-tenant visibility layer on top.

## Cross-story contracts

- **Consumes S-049:** the `Location` aggregate + its `/api/v1/locations` endpoint.
- **Consumes S-026:** the `CLUB_ADMINISTRATOR` + `SYSTEM_ADMINISTRATOR` roles.
- **Produces for S-051+ / S-062a-c:** a filtered Locations list endpoint that respects per-tenant visibility (dropdown UX in Flight-edit, Person home base, etc.).
- **Produces for S-027:** new audit event types (`LOCATION_VISIBILITY_TOGGLED`).

## Notes for the implementer

- Default behavior when no `club_location_visibility` row exists: visible (so fresh deployments don't have to seed). The row materializes only on explicit toggle.
- Cache: visibility writes invalidate the client-side Locations Signal Store via the existing `MUTATION_BUS` `location.*` event channel.
- Cross-tenant guard: a CLUB_ADMIN's PUT to `/api/v1/club-location-visibility/{clubId}/{locationId}` with a `clubId` other than their own returns 403. SYSTEM_ADMIN can address any club.
