---
id: S-049
title: Locations CRUD (reference data)
epic: E-06
status: in_progress
started_at: 2026-05-20
depends_on: [S-047, S-022, S-026]
github_issue: 88
acceptance:
  - `Location` + `LocationType` + `InOutboundPoint` ported as shared reference data — no `@TenantId`. Mutation gated by `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")`.
  - `InOutboundPoint` lives as a child of Location's aggregate; managed via Location's edit screen only (no top-level CRUD endpoint).
  - ICAO code validated server-side against `^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$` (legacy was lax — flagged in `## Assumptions made`).
  - List/edit screens use the kit components from S-008.
  - `e2e/tests/masterdata/locations-crud.spec.ts` parity (port semantics, not legacy URL shape).
  - LocationType admin CRUD UI is **deferred** to a follow-up; S-049 ships LocationType as a Flyway-seeded read-only dropdown.
  - **Per-tenant visibility deferred to S-049b** — operator confirmed the data shape (shared `Location` + per-tenant visibility join), but the visibility join + endpoint + UI is filed as a follow-up to keep this PR reviewable.
estimate: M
adr_refs: [0005, 0008, 0018, 0019, 0022, 0023, 0026]
parity_test: e2e/tests/masterdata/locations-crud.spec.ts
refined: true
refined_at: 2026-05-20
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context
Locations are per-club master data (a club's flight points). Good early E-06 port — small surface, real tenant scoping.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] JPA entities + mappings.
- [ ] Controllers + DTOs.
- [ ] SPA store + edit/list screens.
- [ ] Spec verification.

## Notes
Some locations may be cross-club (commonly-used airports) — confirm with the tenant-scope catalog (S-011) whether Location is fully tenant-scoped or whether there's a shared catalog with per-club references. If shared, this story needs adjustment.

<!-- modernize-refine: start -->

## Design notes

**Tenant scoping — reference data, NOT `@TenantId`.** Shipped schema (`V3__flights_aircraft_locations.sql`) and `alpenflight/database/tenant-rules.yaml` both classify `Location` / `LocationType` / `InOutboundPoint` as `kind: reference` (sacred-cow shared airports). AC2's "per-club `@TenantId`" is stale; honor schema. See Open Q 1.

**Module placement.** New sliced module `alpenflight/server/src/main/java/ch/alpenflight/locations/{domain,application,infra,web}/` — same hexagonal shape as S-048 Clubs but without `@TenantId`. `LocationType` lives in `referencedata/domain/` (lookup, no own slice).

**Aggregate boundary.** `Location` is the aggregate root; `InOutboundPoint` is a child entity managed via Location's edit screen only — no top-level `/api/v1/inoutbound-points` endpoint. Persistence: `@OneToMany(cascade=ALL, orphanRemoval=true)`; PUT body carries the full IOP list, repo replaces.

**Authorization.** Writes (`POST`/`PUT`/`DELETE`) `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")` (ADR 0026; reference-data cross-tenant blast radius — a Location rename shifts every flight log in every club). Reads open to `isAuthenticated()`. UI mirrors: edit form renders read-only for non-SYSTEM_ADMIN with a "managed by system administrator" hint; no save button.

**Soft delete.** Mirror S-048 Clubs: DELETE flips `deleted_on` + `deleted_by_user_id`; list/get filter `deleted_on IS NULL`. No row removal.

**API.** REST + RFC 7807 (`application/problem+json`). `GET /api/v1/locations` sorted `sort_indicator ASC NULLS LAST, location_name ASC`. `GET /{id}` and `PUT /{id}` embed `inboundOutboundPoints[]` (single round-trip per edit). Read-only `GET /api/v1/location-types` for the dropdown.

**LocationType.** Tiny seeded reference (Airport, Outlanding, etc.). Flyway migration in S-049 seeds the rows. No admin UI in S-049 — surface as dropdown only. See Open Q 2.

**Cross-tenant blast-radius UX.** One-line warning banner on the edit form ("Reference data — changes apply to all clubs"); no modal confirm.

**ADR 0022 directive 2.** No new CHECK / generated columns / triggers. Conditional UI (`is_inbound_route_required` toggling IOP sections) lives in the Angular form + Java service.

**Cross-story contracts.**
- Consumes **S-013** (schema), **S-047** (`referencedata` module + Country FK), **S-026** (SYSTEM_ADMIN role).
- Produces for **S-051+ / S-062a-c**: `/api/v1/locations` consumers (Person home base, Flight `started/landed_location_id`).
- For **S-024** (cross-tenant leakage CI): location/location_type/inoutbound_point are classified `reference` in tenant-rules.yaml — leakage suite skips them, but verifies SYSTEM_ADMIN-only mutation.

**i18n.** New `locations.*` top-level key branch in `de.ts` (and mirrored to `fr/it/en.ts` per the compile-time `Translations` gate). Non-conflicting with in-flight S-057.

**Audit log.** Reuse the S-048 audit-emitter hook for create/update/delete (LOCATION_CREATED / _UPDATED / _DELETED with `{actor, role, target_id, before, after}`, no tenant field). If S-027 lands before this PR, refactor onto its unified emitter. See Open Q 3.

## Edge cases & hidden requirements

- **Cross-tenant blast radius on rename** — manual UAT: rename a Location, verify Flights / Reservations still resolve via FK (no string-coupling).
- **`InOutboundPoint` CASCADE DELETE** — schema FK already CASCADE, but list endpoints must use the soft-delete filter (`deleted_on IS NULL`) on both parent + child independently.
- **Country + ElevationUnitType FKs** — populate via existing reference endpoints; do NOT inline. If ElevationUnitType isn't yet ported, surface as a follow-up scope (S-047 continuation).
- **`is_fast_entry_record` toggle** — surface in list view as a boolean column SYSTEM_ADMIN can flip; no separate page.
- **Coordinates** stay as opaque `VARCHAR(10)` end-to-end. No spatial parsing.
- **ICAO uniqueness** — schema does not enforce; validate at the application layer + return 409 on conflict. Match S-048's duplicate-slug error shape.
- **Soft-delete + ICAO recreate** — recreating a Location with the same ICAO after soft-delete must succeed (uniqueness scoped to `deleted_on IS NULL`). Assert in an integration test.

## Security plan

- **Authorization (load-bearing):** writes gated `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")`; reads open to `isAuthenticated()`. UI hides save/delete affordances for non-admin. InOutboundPoint inherits parent gate (no top-level endpoint).
- **No `@TenantId`** — reference data; `tenant-rules.yaml` is authoritative.
- **Input validation** — ICAO uppercase `^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$` at the API boundary; legacy was lax; flag in `## Assumptions made` on impl. Lat/lng size-only (opaque). `description` `@Size(max=...)` — non-PII.
- **Audit log** — reuse S-048 emitter pattern; reference-data mutations are high-value forensic events (no tenant field).
- **OWASP**: A01 dominant (mitigated by SYSTEM_ADMIN gate). A05 — CI fails if `SecurityConfig` ships fresh `permitAll()` for `/locations/**`. All other rows N/A.

## Test plan

Pyramid: **~8 backend integration · 2 vitest · 5 Playwright e2e · 1 parity** (`locations-crud`).

**Parity strategy** — port `e2e/tests/masterdata/locations-crud.spec.ts` to `alpenflight/web/e2e/tests/masterdata/locations-crud.spec.ts`. Assert observable behavior (columns, form fields, save round-trip) — never legacy URL shape / response envelope (ADR 0022). Stub `**/api/v1/locations**` via `page.route` per S-048; mock-auth profile + mocked SYSTEM_ADMIN principal.

**Backend integration (Spring Boot + Testcontainers)**
- Controller: list (sorted, soft-delete filtered), get-by-id, create + read-back, update, soft-delete, 404 on missing, 409 on duplicate ICAO.
- Authorization: SYSTEM_ADMIN can write; CLUB_ADMINISTRATOR / OFFICE_USER get 403 on mutation; both GET. Cross-tenant read: Club-A user sees the same row as Club-B user (proves no `@TenantId` filter).
- InOutboundPoints: create with N children → N FK-linked rows; update swaps IOPs → orphans removed; soft-deleted Location hides child IOPs from list.
- N+1 guard: assert `GET /api/v1/locations` issues exactly 1 SQL; `GET /api/v1/locations/{id}` issues exactly 1 (join-fetch proof).

**Frontend vitest (logic-only)** — `LocationsStore` shape + DTO ↔ form mapper for nested IOP list.

**Playwright e2e**
- Happy: list renders columns + soft-delete excluded.
- Edit + save round-trip; IOP sub-form add/remove.
- 409 on duplicate ICAO inline error.
- Read-only for CLUB_ADMINISTRATOR: stub SessionStore role, assert Save/Delete hidden (not just disabled — RBAC, not validation).
- Dropdown consumer smoke (proxy for S-051 / S-062a): mount `<af-location-select>`, assert GET populates options. Keeps consumers unblocked.

**Parity exclusions** — legacy LocationType admin (deferred); legacy spatial-coord validation (never enforced server-side).

**Risks** — (1) AC2 contradiction lands wrong → integration tests need rewrite. (2) Soft-delete + ICAO unique partial index: if index isn't `WHERE deleted_on IS NULL`, recreate-after-delete flakes. Mitigation: DDL-shape assertion in a one-off test.

## Performance plan

**Hot paths**: none — list hit ~1× per session (dropdown prefetch into Signal Store); detail/edit is SYSTEM_ADMIN-only, rare.

**Required indexes**: none beyond schema PKs/FKs. `inoutbound_point.location_id` already covered by its FK index. Skip `location(location_name)` btree until an autocomplete consumer needs it.

**N+1 risks**:
- `GET /api/v1/locations/{id}`: `@OneToMany(fetch = LAZY)` + `JOIN FETCH l.inOutboundPoints` (or `@EntityGraph`). Single round-trip; IOP count ≤ ~10.
- `GET /api/v1/locations` (list): do NOT fetch IOPs.

**Caching**:
- Server-side: none. Postgres serves the full list in <10 ms.
- Client-side (Signal Store, per S-047): session-lived ≈ 1 h TTL. Invalidate on `MUTATION_BUS` `location.*` event after SYSTEM_ADMIN mutation. Optimistic update acceptable on mutate (admin-only, conflict-free).

**Latency budget** (server-side, vision §2 p95 < 500 ms read): list < 100 ms; detail < 150 ms; mutations < 300 ms.

**Pagination**: skip v1 — total Locations ≤ ~500 across all tenants; full payload < 50 KB gzipped. Revisit if any deployment crosses 5K rows.

## Open design questions

1. **AC2 contradicts shipped schema + tenant-rules.yaml.** Implementer defaults to reference-data shape (no `@TenantId`; SYSTEM_ADMIN-only mutation). Operator: update AC2 via `/modernize-decompose` to remove the "per-club" wording, OR confirm reference-data shape and the implementer drops the contradiction silently.
2. **LocationType admin UI in S-049 or a follow-up?** Tiny surface (Name + KeyName + IsActive). Default = defer (dropdown only); the override is a small bump in scope.
3. **Audit-log emission**: S-027 (audit infrastructure) hasn't shipped. Default = reuse S-048's audit hook; refactor onto S-027 when it lands. Operator confirm OR re-order.
4. **ICAO uppercase + format validation** at the API boundary. Legacy was lax. Default = tighten (`^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$`) + flag in `## Assumptions made`. Override = preserve legacy laxness.

<!-- modernize-refine: end -->

