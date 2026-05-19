---
id: S-047
title: Reference-data domain — slim slice (Country + ClubState) + S-048 Clubs-form retrofit
epic: E-06
status: done
started_at: 2026-05-19
done_at: 2026-05-19
depends_on: [S-006, S-007, S-008, S-022, S-048]
acceptance:
  - New `referencedata` Spring Modulith module (`ch.alpenflight.referencedata.{domain,application,web,infra}`) per ADR 0023. Country + ClubState entities ported (cross-tenant; no `@TenantId`; read-only).
  - `GET /api/v1/countries` and `GET /api/v1/club-states` return listitem-projection DTOs (`{ id, code, name }` plus `iso2Code` for Country). Sorted by `name` (Postgres ICU `de-CH-x-icu`). `@PreAuthorize("isAuthenticated()")`. No POST/PUT/DELETE.
  - V2 seed is the data source; no new Flyway migration.
  - `ClubsService` drops the `DEFAULT_COUNTRY_ID` + `DEFAULT_CLUB_STATE_ID` constants. `ClubCreateRequest` / `ClubUpdateRequest` / `ClubResponse` carry `countryId` + `clubStateId`. Orval regenerated.
  - SPA: combined `ReferenceDataStore` wired into `SessionStore.bootstrapPrefetch()` (forkJoin, per-stream `catchError`); 24 h TTL; subscribes to `MUTATION_BUS` `session.logout` / `session.tenantSwitch` and clears. Clubs edit form gains Country + ClubState `<af-select>` controls (required).
  - Cross-tenant read assertion: an IT under two distinct tenant claims returns the identical row set (proves the `@TenantId` carve-out for Country + ClubState).
  - `next/web/e2e/tests/clubs/clubs-crud.spec.ts` extended to assert the country picker is populated and a non-default country selection persists.
estimate: M
adr_refs: [0005, 0008, 0019, 0022, 0023]
parity_test: next/web/e2e/tests/clubs/clubs-crud.spec.ts (extended)
parity_excluded:
  - Standalone read endpoints (`GET /api/v1/countries`, `/api/v1/club-states`) — greenfield read paths; the demoable parity is the extended Clubs form picker.
  - Server-side `ORDER BY name COLLATE "de-CH-x-icu"` upgrades the country sort from legacy default SQL Server collation (accented Latin names now sort inside their letter group). Deliberate divergence — legacy ordering was a bug.
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
github_issue: 79
github_pr: 80
---

## Context

First domain port; establishes the per-domain pattern for E-06+. Slim slice (Country + ClubState only) closes the walking skeleton by retrofitting S-048's hard-coded FK defaults into real pickers. Remaining cross-tenant lookups + tenant-scoped MemberState / PersonCategory deferred to Phase E.

## Cross-story contracts

- **Produces for downstream:** `ReferenceDataStore` shape (single store, multiple resource slices, 24 h TTL, `bootstrapPrefetch` participant). Typed `CountryId` / `ClubStateId` records with raw-UUID wire form (ADR 0019 carve-out: reference rows are not aggregate roots, so no prefix).
- **Produces for S-048:** new fields `countryId` + `clubStateId` on the Clubs DTOs (`@Schema(requiredMode = REQUIRED)`), populated FK pickers in the form.
- **Consumes from S-006:** the `bootstrapPrefetch` seam and the `MUTATION_BUS` discipline (clear on `session.logout` + `session.tenantSwitch`).
- **`referencedata` Spring Modulith module is declared OPEN** — mirrors `platform/` — so business modules (`clubs`, future `locations` / `flights` / …) import `referencedata.domain.{Country,ClubState}Repository` directly for FK validation without a named-interface dance.

## Phase-E continuation

Remaining six cross-tenant lookups (Language, StartType, units ×3, ExtensionType — Role is already touched by S-026 + S-052) plus tenant-scoped MemberState (lands with first per-club admin UI) + PersonCategory (lands with S-051 Persons).

## Open design questions

- **Phase-E shape (post-S-047 follow-up).** One continuation story (`S-047b`) covering all remaining cross-tenant lookups, or per-entity stories. Plus: do MemberState + PersonCategory land as standalone CRUD stories, or fold into the consumer stories (S-051 Persons for PersonCategory; the first per-club member-state admin UI for MemberState)? Defer to operator post-merge.
