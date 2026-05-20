---
id: S-049b
title: Locations become tenant-scoped masterdata
epic: E-06
status: in_progress
started_at: 2026-05-20
github_issue: 90
depends_on: [S-049]
acceptance:
  - `location` reclassified from CROSS_TENANT reference data to TENANT_SCOPED masterdata — adds `club_id UUID NOT NULL` with `@TenantId` on the entity. `in_outbound_point` inherits tenancy via its parent `Location` (no separate column).
  - `LocationType` stays shared reference data (categorical code: AIRPORT / GLIDER_STRIP / …) — unchanged from S-049.
  - The same physical airport may exist multiple times across clubs but only once per club: global ICAO uniqueness is dropped; replaced with per-club partial unique index `UNIQUE (club_id, icao) WHERE deleted_on IS NULL`.
  - `LocationsController` authz changes from `SYSTEM_ADMINISTRATOR`-only mutation to: `CLUB_ADMINISTRATOR` can CRUD their own club's Locations (auto-scoped via `@TenantId`); `SYSTEM_ADMINISTRATOR` can CRUD any club's Locations via an explicit cross-tenant scope.
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

**Operator pivot 2026-05-20**: Locations are *not* shared reference data after all. S-049 shipped with `location` as CROSS_TENANT on the "sacred-cow shared airports" rationale, but in practice every club maintains its own catalog (its own home base, its own departure points, its own descriptions). The same physical airport (e.g. LSZH) may appear in multiple clubs' catalogs as independent rows — clubs do not share Location identity. This story reclassifies the table accordingly.

The `tenant-rules.yaml` comment on Locations already foresaw this exact fork ("if a future schema adds club-private waypoints, split into Location (reference) + ClubLocation (tenant-scoped)"). Operator chose **full reclassification** over a split: simpler model, cleaner authz, no second aggregate to maintain. The "sacred-cow shared airports" framing in S-049's context is retracted by this story.

## Cross-story contracts

- **Amends S-049:** schema (add `club_id` + `@TenantId`), authz (open CRUD to CLUB_ADMIN-own-club), classification (`tenant-rules.yaml`). S-049's tests need updating to reflect the new role gate.
- **Consumes S-022 / S-026:** the `CLUB_ADMINISTRATOR` + `SYSTEM_ADMINISTRATOR` roles and the `@TenantId` resolver chain (`ClubTenantIdentifierResolver` + `UserTenantLookup`).
- **Produces for S-028 (legacy bulk import):** Location fan-out contract — each referencing legacy club gets its own row. The import maintains `(legacy_id, club_id) → new_id` so flight/person FKs resolve to the right per-club Location.
- **Produces for S-051+ / S-062a-c (Flight/Person edit):** `GET /api/v1/locations` is the dropdown source; it is already implicitly tenant-filtered. No separate "visible" endpoint needed.

## Notes for the implementer

- The migration is the heart of the story. Until it lands, `LocationsController` cannot accept CLUB_ADMIN writes — keep the existing SYSTEM_ADMIN gate on as a safety net until the schema is in.
- This story does NOT migrate legacy data — that's S-028. This story only ensures the schema and code accept tenant-scoped Locations; S-028 fills the data.

<!-- modernize-refine: start -->

## Design notes

- **Schema migration (V7):** `ALTER TABLE location ADD COLUMN club_id UUID`. Backfill: there is no production data yet (alpenflight is pre-cutover); seeded/test rows owned by the existing dev club. Then `SET NOT NULL`, add FK to `club(id)`, drop `ux_location_icao` (the global uniqueness index from S-049), create partial unique `UNIQUE (club_id, icao) WHERE deleted_on IS NULL`. Add index `(club_id)` for the discriminator filter (Hibernate emits `WHERE club_id = ?` on every JPA query). InOutboundPoint inherits via `location_id` — no `club_id` on the child table, just confirmation that all reads go through the parent.
- **Domain shape (per ADR 0018):** `Location` remains a single aggregate root with `InOutboundPoint` as its child entity. `@TenantId club_id` annotation goes on the `Location` entity; Hibernate auto-appends the predicate. The "no row = visible" sparse-table machinery from the prior refine is **deleted entirely** — visibility is now implicit: a club's Locations *are* the visible set.
- **Authz:** `LocationsController.@PreAuthorize` becomes `hasAnyRole('CLUB_ADMINISTRATOR', 'SYSTEM_ADMINISTRATOR')` on writes (was SYSTEM_ADMIN-only). `@TenantId` is the tenant gate on CLUB_ADMIN writes — they can only write rows where `club_id = principal.clubId` because Hibernate scopes both the load-before-merge SELECT and the WHERE of the UPDATE. SYSTEM_ADMIN writes either run within a sysadmin's currently-impersonated club, or use the cross-tenant escape hatch (`Tenants.runAs(clubId, ...)` per ADR 0008 follow-ups).
- **No new endpoints, no new DTOs.** The S-049 endpoints (`POST/GET/PUT/DELETE /api/v1/locations`) stay shape-identical. The `LocationListItem` / `LocationDetail` DTOs are unchanged. The only observable change to consumers is the implicit per-tenant filtering.
- **Sysadmin cross-tenant management:** see `## Open design questions` — UX path for "manage another club's Locations" is the one unresolved fork.
- **Schema-vs-domain deviation check:** none. The per-club ICAO uniqueness is a structural invariant (identity-bearing partial UNIQUE), which the schema is the right place to enforce. No CHECK / trigger / generated column proposed.

## Edge cases & hidden requirements

- **Per-club ICAO duplicate within same club:** server returns the same `409 Conflict` shape S-049 already produces. The new error message wording should clarify "within this club" — keep it generic enough that the SPA error mapper doesn't need a new branch.
- **Cross-club ICAO duplicate:** explicitly allowed (LSZH-for-A and LSZH-for-B coexist). No global uniqueness check anywhere.
- **InOutboundPoint identity stays scoped through parent.** No risk of an IOP being attached to a Location from a different tenant — Hibernate's parent load is tenant-filtered, so the IOP edit endpoint cannot reach across clubs even if a caller guesses the parent UUID.
- **Soft-deleted Location, then a new Location with the same ICAO in the same club:** allowed (partial unique index excludes `deleted_on IS NOT NULL`). Same behavior as S-049, but per-club.
- **SYSTEM_ADMIN GET without an impersonated club:** `GET /api/v1/locations` returns empty list rather than 400 — Hibernate's discriminator yields zero rows when tenant context is null. Sysadmin must impersonate a club to see Locations (consistent with how S-049's authz tests already model sysadmin reads). Cross-tenant aggregate view (if needed) is a sysadmin-only endpoint and falls into the open design question.
- **S-049 tests need amending:** authorization IT currently asserts CLUB_ADMIN gets 403 on create/update/delete. After this story, CLUB_ADMIN gets 200 / 201 / 204 for their own club and 403 (or empty result) cross-tenant. This is in-scope for S-049b's PR (boyscout-fold per the project memory rule).

## Security plan

- **Authz model:** `@PreAuthorize("hasAnyRole('CLUB_ADMINISTRATOR', 'SYSTEM_ADMINISTRATOR')")` on writes; `isAuthenticated()` on reads. The tenant gate is structural — Hibernate's `@TenantId` discriminator on every query (ADR 0008). The controller does NOT need an explicit `#clubId == principal.clubId` SpEL anywhere because the tenant context resolved from the principal *is* the predicate Hibernate appends.
- **Tenant safety:** writes guessing another club's `location.id` cannot succeed — Hibernate's load-before-merge will return null (the row is invisible to the current tenant), and the UPDATE's WHERE includes `club_id = :currentTenant`. Native SQL forbidden in the location repository.
- **Cross-tenant for SYSTEM_ADMIN:** explicit `Tenants.runAs(clubId, ...)` block at the service boundary — never silently bypass tenancy. The escape hatch is itself an audit point (S-027).
- **PII:** `Location.description` is flagged in `tenant-rules.yaml` as a PII column. Behavior unchanged from S-049 — soft-delete preserves the row, redaction policy lands with S-027.
- **OWASP relevant:** A01 IDOR — the entire point of the reclassification is to make it structurally impossible for CLUB_ADMIN-of-A to mutate Location of B; covered by `@TenantId`. A04 mass-assignment — `LocationCreateRequest` / `LocationUpdateRequest` DTOs from S-049 don't accept `clubId` in the body; it's derived from principal. (Verify: they already don't.)

## Test plan

- **E2E means real stack.** Playwright runs against `next/ops/dev-up-full.sh`'s `alpenflight-dev` (real Spring server + Postgres + Keycloak). No MSW, no in-memory H2, no `app.config.mock` for these specs.
- **Pyramid:**
  - Domain unit (1-2): partial-unique constraint reasoning if any lands in domain; otherwise N/A — the migration is the meat.
  - Integration (~10, Spring slice + Testcontainers Postgres): two-tenants isolation (Club A's LSZH and Club B's LSZH coexist; CLUB_ADMIN-of-A only sees A's); CLUB_ADMIN CRUD on own club (200 / 201 / 204); CLUB_ADMIN forbidden cross-tenant (effectively 404 — row invisible); SYSTEM_ADMIN cross-tenant via `Tenants.runAs(...)`; per-club ICAO uniqueness rejection; cross-club ICAO duplicate accepted; SYSTEM_ADMIN with no tenant context returns empty list (not 400); soft-delete + recreate same ICAO same club allowed; InOutboundPoint reads/writes tenant-scoped via parent.
  - E2E (1-2, Playwright against `alpenflight-dev`): CLUB_ADMIN login → Locations page → create/edit/delete a Location → reload reflects state, only for their club.
  - **Migration-correctness IT**: a dedicated test asserts the V7 migration is idempotent and that an existing seeded Location is reachable post-migration under the seed club's tenant context.
- **Parity strategy:** no legacy oracle for tenant-scoped Locations (legacy is shared). Existing S-049 contract tests must still pass except the two that asserted SYSTEM_ADMIN-only mutation — those flip to assert CLUB_ADMIN-own-club mutation + cross-tenant denial.
- **Fixtures:** `LocationsTestFixtures` gains a "create in club X" variant. Existing fixtures default to the seeded dev club.

## Performance plan

- **Hot path:** `GET /api/v1/locations` (Locations admin + Flight-edit / Person-edit dropdowns). With `@TenantId`, the query becomes a single `SELECT … WHERE club_id = ? AND deleted_on IS NULL` — simpler than the prior LEFT-JOIN-with-coalesce design. The N+1 guard from S-049 still applies for IOPs.
- **Index:** add `idx_location_club_id` (single-column, supports the discriminator predicate). The new partial unique `UNIQUE (club_id, icao) WHERE deleted_on IS NULL` doubles as a covering index for ICAO lookups within a club.
- **InOutboundPoint reads:** unchanged from S-049's `@OneToMany` with `@BatchSize` strategy — tenancy rides on the parent.
- **Cache:** `MUTATION_BUS` `location.*` events from S-049 already trigger SignalStore refetches. With per-tenant Locations, cache invalidation is intrinsically per-tenant — no cross-tenant fan-out needed. SPA tenant-switch wipe (existing `LocationsStore.onInit` hook) remains correct.
- **No new perf risks** introduced by the reclassification. Removing the visibility join actually *simplifies* the query plan compared to the prior refinement.

## Open design questions

1. **Sysadmin cross-tenant Locations management UX.** A `SYSTEM_ADMINISTRATOR` must occasionally fix data in another club's Locations catalog. Two options:
   - (a) Sysadmin uses an explicit "switch club" UI (already used elsewhere in the SPA?) → the existing per-tenant Locations page works as-is.
   - (b) A dedicated `/admin/locations` page that lets sysadmin pick a club from a dropdown, then operates on that club via `Tenants.runAs(...)`.
   - Recommend (a) if a tenant-switch UI exists or is planned (cheapest); otherwise (b). Defer to operator.
2. **Cutover identity for legacy shared Locations.** S-028 needs to know whether to fan out one legacy Location into N tenant-scoped rows (one per referencing club) keyed by `(legacy_id, club_id)`, OR to dedupe per-club by ICAO if multiple legacy Locations share an ICAO within the same club's reference graph. Recommend fan-out keyed by `(legacy_id, club_id)` for traceability — dedupe is a downstream cleanup. Surfacing here so S-028's refine doesn't relitigate.

<!-- modernize-refine: end -->
