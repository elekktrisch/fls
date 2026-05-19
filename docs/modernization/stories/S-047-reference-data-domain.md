---
id: S-047
title: Reference-data domain — slim slice (Country + ClubState) + S-048 Clubs-form retrofit
epic: E-06
status: in_progress
started_at: 2026-05-19
depends_on: [S-006, S-007, S-008, S-022, S-048]
acceptance:
  - New `referencedata` Spring Modulith module (`ch.alpenflight.referencedata.{domain,application,web,infra}`) per ADR 0023. Country + ClubState entities ported (cross-tenant; no `@TenantId`; read-only).
  - `GET /api/v1/countries` and `GET /api/v1/club-states` return listitem-projection DTOs (`{ id, code, name }` plus `iso2Code` for Country). Sorted by `name`. `@PreAuthorize("isAuthenticated()")`. No POST/PUT/DELETE.
  - V2 seed (already shipped) is the data source; no new Flyway migration.
  - `ClubsService` drops the `DEFAULT_COUNTRY_ID` + `DEFAULT_CLUB_STATE_ID` constants. `ClubCreateRequest` / `ClubUpdateRequest` / `ClubResponse` carry `countryId` + `clubStateId`. Orval regenerates cleanly.
  - SPA: combined `ReferenceDataStore` (`withEntities` per resource kind) wired into `SessionStore.bootstrapPrefetch()` `forkJoin`; 24h TTL; subscribes to `MUTATION_BUS` `session.logout` + `session.tenantSwitch` and `clear()`s. Clubs edit form gains Country + ClubState `<af-select>` controls (required).
  - Cross-tenant read assertion: an IT runs the Country / ClubState reads under two distinct tenant contexts and gets the identical row set (proves `@TenantId` does not apply).
  - `e2e/tests/masterdata/clubs-crud.spec.ts` extended to assert the Country picker is populated and a non-default country selection persists across reload.
  - Remaining cross-tenant lookups (Language, StartType, LengthUnitType, ElevationUnitType, CounterUnitType, ExtensionType, Role) deferred to a Phase-E continuation; tenant-scoped MemberState + PersonCategory deferred to their own per-tenant stories.
estimate: M
adr_refs: [0005, 0008, 0019, 0022, 0023]
parity_test: next/web/e2e/tests/clubs/clubs-crud.spec.ts (extended)
parity_excluded:
  - Standalone read endpoints (`GET /api/v1/countries`, `/api/v1/club-states`) — greenfield read paths with no legacy oracle worth recording; the demoable parity is the extended Clubs form picker.
  - Server-side `ORDER BY name COLLATE "de-CH-x-icu"` upgrades the country sort from legacy default SQL Server collation; accented Latin names (Côte d'Ivoire, Réunion) now sort inside their letter group instead of at the end. Deliberate divergence — legacy ordering was a bug.
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
github_issue: 79
github_pr: 80
---

## Context

First domain port. Establishes the per-domain pattern that all later E-06+ stories follow. The original story title scoped 8 entities; the `_ORDER.md` walking-skeleton plan and the refine narrow this to **Country + ClubState only** — the two FKs that S-048's `ClubsService` currently hard-codes. Slim slice is what makes Phase D end-to-end demoable; everything else is a mechanical copy of the same pattern under Phase E.

The original `## Notes` claim "these tables are not tenant-scoped" is wrong for MemberState + PersonCategory (V2 schema carries `club_id` FK + `fk_*_club_id ON DELETE CASCADE` on both). Both are explicitly out of this story's scope — they are per-club CRUD aggregates and need full mutation surfaces, audit columns, and tenant-scoped tests.

<!-- modernize-refine: start -->

## Design notes

**Module.** New `ch.alpenflight.referencedata.{domain,application,web,infra}` (mirrors S-155 / S-048 template per ADR 0023). Country + ClubState live here. MemberState stays in `clubs.domain` (Club aggregate-internal per ADR 0018). PersonCategory belongs in a future `persons` module when first consumed.

**Domain.** Plain JPA entities, immutable from the API's POV (system-seeded in V2). No `@TenantId`. No soft-delete columns. No audit columns. Read-only repository ports in `domain/`; Spring Data adapters in `infra/`. No aggregate methods — rows are loaded as-is from V2.

**API.** `GET /api/v1/countries` + `GET /api/v1/club-states` only. Listitem-shape DTOs (`id`, `code`, `name`, plus `iso2Code` on Country). Sort by `name` ICU-collated (`de-CH-x-icu`). Plain REST per ADR 0005 — no `/listitems` URL segment.

**Typed IDs.** New `CountryId(UUID value)` + `ClubStateId(UUID value)` records in `platform.id`, registered with `TypedIdJacksonModule` — raw-UUID wire form, no `xxx-` prefix (ADR 0019 reserves prefixes for aggregate roots; these are system lookups). Compile-time prevention of swapping a `CountryId` for a `ClubStateId`.

**S-048 ripple (required for "demoable" Phase D).** Drop both `DEFAULT_*` constants from `ClubsService`. Add `countryId` + `clubStateId` to `ClubCreateRequest` / `ClubUpdateRequest` / `ClubResponse` + `ClubMapper`. Re-run orval. Extend `clubs-edit.page.ts` with two `<af-select>` controls bound to `ReferenceDataStore.countries()` / `.clubStates()`, both required. The existing V5 walking-skeleton seed row continues to satisfy the FK NOT-NULL.

**FE store.** ONE combined `ReferenceDataStore` (`withEntities` slice per resource kind), `providedIn: 'root'`. Loaded once from `SessionStore.bootstrapPrefetch()` via `forkJoin([countries(), clubStates()])` with per-stream `catchError(() => of(null))`. TTL 24h (vs. S-006 masterdata 1h — reference rows mutate only via Flyway migration). Subscribes to `MUTATION_BUS` `session.logout` / `session.tenantSwitch` and `clear()`s (S-006 discipline; semantically a no-op for cross-tenant data but the convention must hold). The `TODO(S-047)` comment in `session.store.ts:121-126` becomes the real wiring.

**Phase-E continuation.** Remaining 6 cross-tenant lookups (Language, StartType, units ×3, ExtensionType — Role is already touched by S-026 + S-052) plus tenant-scoped MemberState (lands with first per-club admin UI) + PersonCategory (lands with S-051 Persons). One follow-up story (rename S-047b) or per-entity stories is the operator's call (Open Q.4).

## Edge cases & hidden requirements

- **`Club.country_id` / `Club.club_state_id` are FK ON DELETE RESTRICT** (V2:201-202). No DELETE endpoint in slim scope, so this is informational — but a Phase-E delete UI must translate `DataIntegrityViolationException` to 409 with "in use by N clubs".
- **Country `iso2_code` / `iso3_code` uppercase + ISO-3166 invariants:** V2's CHECK constraint was removed per ADR 0022 directive 2. Seed data is already uppercase; for the read-only slice the Country domain class does NOT need a constructor guard — defer to the story that introduces mutation.
- **No empty-result handling needed.** V2 seeds 248 countries + 3 club states; an empty response means a botched migration, not a runtime case to handle. Stores render an empty `<af-select>`; no error toast.
- **Reference tables stay seed-only in tests.** No `@Sql` overrides; reference data is fixture-by-V2-seed across the whole suite. Document in the PR description, not as an automated guard.
- **Public-route prefetch skip:** the new store is consumed only on authenticated routes. `data: { skipPrefetch: true }` already on landing / login routes per S-006 — no new route flags needed.
- **`withEntities` typing.** Listitem DTOs MUST expose `id` (not `countryId` / `clubStateId`) as the canonical field — orval already emits the OpenAPI-derived `id` name; verify it doesn't get pluralized.
- **`StartType.applicable_categories` is `TEXT[]` (V2:114)** with ADR 0020's SET-MEMBERSHIP convention. Mapping non-trivial (Hibernate `@JdbcTypeCode(SqlTypes.ARRAY)` + enum-set value object). **Out of slim scope; the Phase-E continuation owns it.**
- **MemberState boyscout debt.** The existing `MemberStateRepository extends JpaRepository<…>` in `clubs.infra` lacks a `domain/` port interface — an ADR-0023 layering nit. Not in this story's scope; logged in `pending-boyscout-followups` memory if needed.

## Security plan

- **Authn / authz.** Both controllers: `@PreAuthorize("isAuthenticated()")`. All three S-026 roles need country / club-state lookups in every form; role-restricting would over-gate.
- **Tenant isolation.** `Country` + `ClubState` have no `@TenantId`. ADR 0008 explicitly carves out reference data. A cross-tenant read IT (sibling to S-024) proves the resolver does not append `WHERE club_id = ?`.
- **No write surface, no audit, no PII.** Reference rows are Flyway-managed; controller javadoc documents "immutable from the API; updates ship via Flyway migration."
- **OWASP.** A01 — predicate must be `isAuthenticated()`, not `permitAll()`. If a later public flow (S-098/S-099 trial-flight registration) needs anonymous Country reads, that story owns the carve-out — do not pre-emptively widen here.

## Test plan

- **Backend ITs (~3).** Pattern: S-048 `ClubControllerIT` (mock-auth, MockMvc, Testcontainers).
  - `CountryControllerIT` + `ClubStateControllerIT` happy: 200, full seed returned, sorted by `name`, Schweiz / Deutschland / Österreich visible.
  - Anonymous → 401.
  - `POST /api/v1/countries` → 405 (guards against an accidental future write endpoint).
  - One **cross-tenant read assertion**: run the read under two distinct `@WithTenant` contexts (S-022 seam) and assert identical row sets — robust to dialect changes; semantic equivalence over SQL-log sniffing.
- **Vitest (FE logic, ~3).** Pattern: S-006 `HelloStore` patterns §1–14.
  - `ReferenceDataStore` state machine (load happy / error / offline).
  - Selector signals (`countries()`, `clubStates()`).
  - `MutationBus` cleanup on `session.tenantSwitch` (real `Subject`, assert `clear()` ran).
- **Playwright e2e.** Extend `e2e/tests/masterdata/clubs-crud.spec.ts` (or sibling spec to avoid cross-test coupling):
  - Country `<af-select>` populated with seeded countries by name.
  - Create club selecting Deutschland; save; reload detail; assert persisted.
  - Skip ICU-sort assertion in Playwright — server-side `ORDER BY` is the source of truth, asserted at the IT.
- **Parity oracle.** The extended `clubs-crud.spec.ts` IS the parity oracle (`parity_test` updated in frontmatter). Standalone read endpoints have no legacy oracle worth recording; the user-visible parity is "can pick a country in the Clubs form."
- **Phase-E hand-off.** Deferred MemberState + PersonCategory follow-ups inherit `e2e/tests/masterdata/member-states-crud.spec.ts` + `person-categories-crud.spec.ts` as their parity oracles.

## Performance plan

- **Hot path.** `ReferenceDataStore.loadAll()` from `SessionStore.bootstrapPrefetch()` `forkJoin` — 2 small endpoints (< 250 + < 10 rows). Comfortably inside S-006's < 1.5s envelope.
- **Indexes.** None added. V2's `ux_country_iso2` / `ux_country_iso3` / `ux_club_state_code` already cover lookup; `ORDER BY name` on < 250 rows is sub-ms.
- **Cache.** Signal Store in-memory only, TTL 24h. No server cache, no HTTP `Cache-Control`.
- **No pagination, no N+1, no Cartesian.** Flat tables, no joins.
- **Risk.** Bootstrap stalls on slowest stream → per-stream `catchError(() => of(null))` (S-006 canonical pattern).

## Open design questions

- **Phase-E shape (post-S-047 follow-up).** One continuation story `S-047b` covering all remaining cross-tenant lookups vs. per-entity stories. Plus: do MemberState + PersonCategory land as standalone per-tenant CRUD stories, or fold into the consumer stories that need them (S-051 Persons for PersonCategory; first per-club member-state admin UI for MemberState)? Defer to operator post-merge.

<!-- modernize-refine: end -->
