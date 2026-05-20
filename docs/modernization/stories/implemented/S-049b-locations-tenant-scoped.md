---
id: S-049b
title: Locations become tenant-scoped masterdata
epic: E-06
status: done
started_at: 2026-05-20
done_at: 2026-05-20
github_issue: 90
github_pr: 91
depends_on: [S-049]
acceptance:
  - `location` reclassified from CROSS_TENANT reference data to TENANT_SCOPED masterdata — adds `club_id UUID NOT NULL` with `@TenantId` on the entity. `in_outbound_point` inherits tenancy via its parent `Location` (no separate column).
  - `LocationType` stays shared reference data (categorical code: AIRPORT / GLIDER_STRIP / …) — unchanged from S-049.
  - The same physical airport may exist multiple times across clubs but only once per club: global ICAO uniqueness is dropped; replaced with per-club partial unique index `UNIQUE (club_id, icao_code) WHERE icao_code IS NOT NULL AND deleted_on IS NULL`.
  - `LocationsController` authz changes from `SYSTEM_ADMINISTRATOR`-only mutation to: `CLUB_ADMINISTRATOR` can CRUD their own club's Locations (auto-scoped via `@TenantId`); `SYSTEM_ADMINISTRATOR` can CRUD whichever club its JWT `clubId` claim asserts (cross-club operation today requires impersonation; an unscoped escape hatch is an ADR 0008 follow-up).
  - `GET /api/v1/locations` returns only the caller's own club's Locations (Hibernate `@TenantId` filter does this automatically). The list is therefore already what the Flight-edit / Person-edit dropdowns need — no separate "filtered" endpoint required.
  - `tenant-rules.yaml`: `Locations.kind` flips `reference → tenant-scoped`; `InOutboundPoints.kind` flips `reference → tenant-scoped`. Rationale comment updated.
  - Cutover contract documented for **S-028** (legacy bulk import): each legacy shared `Location` row fans out into N rows — one per club that references it — keyed by the new `(club_id, location_id)` pair. The legacy `Location.Id` mapping becomes `(legacy_id, club_id) → new_id`.
estimate: M
adr_refs: [0005, 0008, 0018, 0022]
parity_test: none
origin: split-from-S-049
refined: true
refined_at: 2026-05-20
refined_specialists: [solution-architect, requirements-engineer, security-engineer, qa-engineer, performance-engineer]
---

## Context

S-049 shipped `location` as CROSS_TENANT reference data on a "shared airports" rationale. Operator retracted that framing on 2026-05-20: each club maintains its own catalog (its own home base, departure points, descriptions). The same physical airport (e.g. LSZH) may appear in multiple clubs' catalogs as independent rows — clubs do not share Location identity. This story reclassifies the table accordingly and amends S-049's schema + authz.

## Cross-story contracts

- **Amends S-049:** schema (add `club_id` + `@TenantId`), authz (open CRUD to CLUB_ADMIN-own-club), classification (`tenant-rules.yaml`).
- **Consumes S-022 / S-026:** the `CLUB_ADMINISTRATOR` + `SYSTEM_ADMINISTRATOR` roles and the `@TenantId` resolver chain (`ClubTenantIdentifierResolver` + `UserTenantLookup`).
- **Produces for S-028 (legacy bulk import):** Location fan-out contract — each referencing legacy club gets its own row, keyed `(legacy_id, club_id) → new_id`.
- **Produces for S-051+ / S-062a-c (Flight/Person edit):** `GET /api/v1/locations` is the dropdown source; already implicitly tenant-filtered.

## Open design questions

1. **Sysadmin cross-tenant Locations management UX.** A `SYSTEM_ADMINISTRATOR` must occasionally fix data in another club's Locations catalog. Two options: (a) a "switch club" UI (if/once one exists project-wide) — reuses the per-tenant Locations page as-is; (b) a dedicated `/admin/locations` page that picks a club from a dropdown and operates on it via the future `Tenants.runAs(...)` escape hatch (ADR 0008 follow-up). Recommend (a) if the switch-UI is planned; (b) otherwise. Deferred — not blocking this story.

## Follow-ups surfaced by the reviewer panel

- **`Tenants.runAs(...)` escape hatch** referenced in design notes but not implemented; today SYSTEM_ADMIN cross-club work requires JWT impersonation. File under ADR 0008 platform follow-ups.
- **`deletedByUserId` is `null` for federated OIDC users** whose `sub` claim is not a UUID. S-022's `UserTenantLookup` already maps `sub → user.id`; reuse there when S-027 ships the audit emitter.
