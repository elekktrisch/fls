# 0008 — Multi-tenancy enforcement mechanism

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): structural multi-tenancy supported · preserves sacred cows · mature ecosystem · solo-operator operability

## Context

Multi-tenancy in the current system is enforced by convention: every service calls `CurrentAuthenticatedFLSUserClubId` on every query ([current-state §3](../01-current-state.md#3-architecture-digest), [R1](../01-current-state.md#r1--multi-tenancy-enforced-by-convention)). Forgetting one query is the largest correctness risk in the codebase. The seed marks structural enforcement as **non-negotiable**; [C3](../02-vision-and-constraints.md#3-hard-constraints) selects the **query-layer guard** family — the ORM or repository abstraction must make it impossible to read tenant-scoped data without going through the tenant filter.

With [ADR 0001](0001-backend-language-and-framework.md) (Spring Boot) + [ADR 0002](0002-database-engine.md) (Postgres) + Hibernate as the JPA provider, this ADR picks the specific mechanism within the query-layer family. A complication worth recording: per [current-state §5](../01-current-state.md#5-data-model-summary), `Flight` crew references can point at a `Person` whose primary `Club` is **different from the flight's operating club** (cross-tenant referenced through `PersonClub`). The tenancy mechanism must allow this — the tenant scope is the **operating club of the flight**, not "all data referenced by the flight."

## Options considered

### Option A — Hibernate `@TenantId` (discriminator) + `CurrentTenantIdentifierResolver`
- **Capabilities:** Hibernate 6+ provides first-class discriminator-based multi-tenancy. Mark tenant-scoped entities with `@TenantId`; implement `CurrentTenantIdentifierResolver` that reads the authenticated principal's `clubId` from Spring Security context. Hibernate automatically appends `WHERE club_id = :currentTenant` to every query — by SQL, not by application code. Forgetting the filter is structurally impossible from within JPA queries.
- **Fit to criteria:** structural multi-tenancy ✓ (best in family). Mature ecosystem ✓ (Hibernate-native, documented, used widely). Solo-operator operability ✓ (a few annotations + one resolver class).
- **Migration cost:** medium — every entity that's tenant-scoped gets a `@TenantId` column + annotation; the resolver is ~20 lines; explicit "unscoped" sessions need to be plumbed for the legitimate cross-tenant cases (system-admin reports, OGN ingestion writing on behalf of multiple clubs).
- **Ecosystem risk:** low.
- **Escape hatch:** Postgres RLS can be layered on top later for defense-in-depth without changing application code (RLS reads the same `club_id` column).
- **Cross-tenant references handled cleanly:** `@TenantId` only affects queries against tenant-scoped entities. A `Flight` belongs to its operating `Club`; its `Person` references are foreign keys that load by ID, not by querying the `Person` table tenant-filtered. The crew-from-different-club case continues to work.

### Option B — Hibernate `@Filter` (opt-in per session)
- **Capabilities:** annotation-based filters that must be enabled on each Hibernate `Session` with `session.enableFilter("tenantFilter").setParameter(...)`.
- **Fit to criteria:** structural ✗ — forgetting to enable the filter on a request silently un-filters every query. This is R1 recreated.
- **Why not chosen:** the failure mode is identical to today's "forgot to call `CurrentAuthenticatedFLSUserClubId`."

### Option C — Manual repository-level filter
- **Capabilities:** every `Repository` method takes a `clubId` argument; every query uses it.
- **Fit to criteria:** structural ✗ — same discipline-only model as today's service-layer approach, just relocated.
- **Why not chosen:** doesn't satisfy [C3](../02-vision-and-constraints.md#3-hard-constraints).

### Option D — Postgres Row-Level Security only
- **Capabilities:** DB-level filtering via `CREATE POLICY` on each tenant-scoped table; application sets `SET app.current_club_id = ?` at the start of each request.
- **Fit to criteria:** structural ✓ (DB enforces regardless of ORM mistakes). Mature ecosystem ✓. Operability ~ (debugging "why does this query return zero rows" is harder; connection-pool lifecycle vs. SET behavior requires care; transactions and `RESET` discipline matter).
- **Why not chosen as primary:** [C3](../02-vision-and-constraints.md#3-hard-constraints) selected the query-layer family. RLS-only sits outside that family. Worth keeping on the table as **defense-in-depth on top of** Option A — see follow-ups.

## Decision

Chosen: **Option A — Hibernate `@TenantId` discriminator multi-tenancy + Spring-Security-integrated `CurrentTenantIdentifierResolver`**. Strongest option within the query-layer family pinned in [C3](../02-vision-and-constraints.md#3-hard-constraints). Hibernate appends the tenant filter at SQL generation, so forgetting the filter is impossible from within JPA. Mature, documented, native — the canonical Hibernate-6+ approach for this exact problem.

## Consequences

- **Positive:**
  - Forgetting a tenant filter is structurally impossible from JPA queries (closes [R1](../01-current-state.md#r1--multi-tenancy-enforced-by-convention) at the ORM layer).
  - Resolver integrates with Spring Security's authenticated principal — the JWT subject's `clubId` claim becomes the tenant identifier.
  - Audit-log + tenancy ride together — every mutating operation runs inside a tenant-scoped session.
  - The `@TenantId` column is just a normal indexed UUID column ([ADR 0019](0019-entity-id-strategy.md) pinned UUID v7 as the system-wide ID type); query plans look like normal multi-tenant queries, easy to reason about.

- **Negative:**
  - Native SQL queries (when used) bypass the filter — discipline + code review required, or restrict native SQL to repository methods that explicitly handle tenancy.
  - Cross-tenant operations (system-admin reports, OGN ingestion writing flights for many clubs, scheduled jobs running across all tenants) need explicit "unscoped" sessions — a `@SystemTenantAware` or similar marker plus an injected unscoped resolver. Must be implemented carefully or it becomes the new R1.
  - Test fixtures need to set a tenant context before running queries; without it, queries return empty results. Mitigation: a Spring test rule that sets a default tenant; helper to switch tenants per test.
  - Schema cost: every tenant-scoped table gets a `club_id` column + index. Already true today, but now formally required.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** define the list of tenant-scoped entities (Flight, Aircraft, AircraftReservation, PlanningDay, Delivery, AccountingRuleFilter, …) vs. cross-tenant entities (Person, User, Country, reference data). Capture in `alpenflight/database/` schema design.
  - **Story:** implement `ClubTenantIdentifierResolver` reading from Spring Security principal; implement an `UnscopedTenantContext` mechanism for legitimate cross-tenant operations.
  - **Story:** wire a test rule that defaults to a known tenant + helpers for "run this test as a different tenant" and "run this test cross-tenant."
  - **Story:** add a CI test (smoke / property-based / both) that asserts tenant leakage is impossible — create data in tenant A, attempt to read it while tenant context is B, expect empty result. Run against every repository.
  - **Story:** evaluate Postgres RLS as a *defense-in-depth* layer on the same `club_id` column — RLS catches any query that escapes Hibernate (raw JDBC, dev-time mistakes, future ORM swaps). Implement after core Hibernate path is proven; or keep as a hardening story tracked but not committed.
  - **Story:** decide tenancy strategy for the public flows (trial-flight, passenger-flight registration) — these run without an authenticated principal but target a specific club. Likely a "tenant from URL path / form field, validated against an allowlist" pattern.
  - **Story:** OGN ingestion endpoint ([C8](../02-vision-and-constraints.md#3-hard-constraints)) — runs as a service principal that writes for many clubs; needs an explicit per-write tenant scope.

## Amendment — 2026-05-23 (S-159)

SYSTEM_ADMINISTRATOR is no longer co-allowed on tenant-scoped HTTP endpoints. Sysadmin's HTTP rights are limited to **cross-cutting resources** (Clubs catalog, sysadmin user management, cutover / bulk import). The `/api/v1/admin/locations/{clubId}` impersonation pattern introduced in S-049c is **withdrawn**.

`Aircraft` becomes tenant-scoped via a NEW `managing_club_id` `@TenantId` column — separate from `owner_club_id` (which stays as ownership metadata, nullable, may differ from the managing tenant for other-organisation / private-person ownership). The owner-kind discriminator (own-club / other-organisation / private-person) lives in the domain layer, not the schema (per [ADR 0022](0022-modernization-primary-directives.md) directive 2).

`Tenants.runAs(...)` survives only as an in-process seam for: the audit listener / `RequestAuditFilter`, OGN ingestion, scheduled jobs, cutover import. It is never wired through to an HTTP path that exists to "act as a club from the outside." Tenant data is acted on by members of that tenant; nothing else.

Reclassifies the `alpenflight/database/tenant-rules.yaml` 2026-05-16 Aircraft-cross-tenant amendment as **superseded**: Aircraft is now tenant-scoped via `managing_club_id`. The aircraft_id FK on `aircraft_reservation` / `flight` becomes a same-tenant FK by construction; S-058 (Flight) and S-068 (AircraftReservation) inherit the new contract.

## Amendment — 2026-05-24 (S-058, reverts the S-159 Aircraft scoping above)

The S-058 grilling pass on Flight semantics surfaced the day-1 use case S-159's Aircraft tenant-scoping had structurally closed: small glider clubs charter tow planes from other clubs (or from external owners not in the Clubs catalog at all). A Club B user must be able to pick Club A's tow plane on a Flight; equally, Club A must be able to operate aircraft whose physical owner isn't a registered Club.

**Aircraft reverts to cross-tenant** (mirrors Person + Location). The `@TenantId` discriminator on `Aircraft.managingClubId` is removed; the `managing_club_id` column survives as plain metadata — the operational manager — and gates writes via the `AircraftAccess` SpEL bean at the controller layer:

- `managing_club_id` (NOT NULL): the operational manager — required even for external-owner aircraft (the manager IS the entity that runs the row's lifecycle).
- `owner_club_id` (NULL OK): physical owner club. Metadata only; does NOT gate edits. NULL when owned by an external organisation or by a private person.
- `aircraft_owner_person_id` (NULL OK): private-person owner metadata. Person-edit predicate deferred until S-052 (Users CRUD) wires User→Person.

**Read endpoints are open** to any authenticated user — except counter-history. Counter snapshots reflect the managing club's bookkeeping (most of a foreign club's flights aren't in the system, so the totals are misleading to non-managers): `GET /api/v1/aircraft`, `/picker`, `/{id}`, `/{id}/states` are open; `GET /api/v1/aircraft/{id}/counters` is manager-only (same predicate as masterdata writes). The detail GET still surfaces the latest counter inline today; a follow-up story can redact it for non-managers when the policy bar rises.

**Write endpoints are gated** by:

- `@aircraftAccess.canRegister(jwt)` — CLUB_ADMIN only (managing_club_id sourced from JWT clubId; sysadmin has no clubId and must use a future admin variant).
- `@aircraftAccess.canEdit(id, jwt)` — CLUB_ADMINISTRATOR of managing_club_id, OR SYSTEM_ADMINISTRATOR (universal fallback for cross-cutting maintenance).
- `@aircraftAccess.canOperate(id, jwt)` — same predicate, FLIGHT_OPERATOR also admitted.

**Flight contract** (S-058): `aircraft_id` is unrestricted (any active aircraft). The charter pair (glider + tow flights) sits under a single `operating_club_id` (the operating club's books); the tow's `aircraft_id` may reference a different club's aircraft. The `tow_flight_id` self-FK still pairs two flights inside the same operating club. The previous "same-tenant aircraft check" service-layer guard is withdrawn.

**S-068 (AircraftReservation)** inherits the unrestricted contract: any club may reserve any aircraft (subject to a future "may this club use this aircraft" gate if needed).

**Schema delta:** none. The `managing_club_id` column from V10 stays in place; only the `@TenantId` annotation in Java is dropped. No data migration needed.
