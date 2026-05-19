---
id: S-026
title: Authorization model — roles → @PreAuthorize mapping
epic: E-03
status: in_progress
started_at: 2026-05-19
depends_on: [S-020]
acceptance:
  - Three roles are mapped end-to-end: `system_administrator`, `club_administrator`, `flight_operator` (matching `RoleApplicationKeyStrings.cs`).
  - `@PreAuthorize` patterns are documented: `@PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")`, `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR') or hasRole('CLUB_ADMINISTRATOR')")`, etc.
  - A reference controller has `@PreAuthorize` on each method; tests assert each role is required.
  - The mapping from Keycloak `realm_access.roles` claims to Spring authorities is correct.
estimate: M
adr_refs: [0007]
parity_test: none
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer]
github_issue: 73
github_pr: 76
---

## Context
ADR 0007 follow-up. Every endpoint in E-06..E-09 needs to opt into the right authority level; this story establishes the convention and the documentation hook (`CONVENTIONS.md §Authorization patterns`) that every future controller cites.

S-020 already wired `ClubAwareJwtAuthenticationConverter` to map `realm_access.roles` → `ROLE_*` authorities; S-019 added the three roles to the realm export; S-048 placed the first `@PreAuthorize` predicates on `ClubsController`. This story closes the loop: documents the convention, expands `ClubsController` to exercise all three roles, expands the role-gate test matrix, and executes the backend mock-auth rip-out the prior auth-chain stories deferred here.

<!-- modernize-refine: start -->

## Design notes

- **Authority shape stays `ROLE_<UPPER_SNAKE>`.** The `realm_access.roles[]` claim carries `SYSTEM_ADMINISTRATOR` / `CLUB_ADMINISTRATOR` / `FLIGHT_OPERATOR` (mirroring the legacy `RoleApplicationKeyStrings` C# constants); `ClubAwareJwtAuthenticationConverter` prefixes each with `ROLE_` so `hasRole('X')` matches. No `SCOPE_*` authority shape — scope-derived authority is a future story (machine clients, finer-grained permissions).
- **Reference controller stays `ClubsController`.** S-048 already put `@PreAuthorize` on every Clubs method against a SYSTEM_ADMINISTRATOR seam; S-026 widens the read paths (`GET list`, `GET by-id`) to also accept FLIGHT_OPERATOR for the operator's own club, exercising the three-role disjunction in code. Write paths (`POST` / `PUT` / `DELETE`) stay sysadmin-or-clubadmin per the existing shape — flight-operators may *view* their club but not mutate it.
- **Backend mock-auth rip-out lands in this story** per the deferral chain documented in `MockSecurityConfig.java` Javadoc + S-022's rip-out section. Delete `ch.alpenflight.auth.*` package (3 files), `application-mock-auth.yml`, and the related `@Profile("!mock-auth")` guards on `UserTenantLookup` / `MockSecurityConfigAbsenceIT`. `ClubAwareJwtAuthenticationConverter`, the `@PreAuthorize` predicates, and the production `SecurityConfig` all stay unchanged — only the principal *source* flips.
- **SPA mock-auth seam — amended plan: keep, do not delete.** `MockSecurityConfig` Javadoc step 3 (delete `src/app/app.config.mock.ts` + the `mock-auth` angular.json configuration) is rescinded. Reason: the Playwright SPA suite (`next/web/e2e/playwright.config.ts:30`) boots `ng serve --configuration=mock-auth` because the suite stubs the backend via `page.route(...)` and must not depend on a running Keycloak. The seam is now a documented Playwright-CI / no-Keycloak dev convenience; the SPA mock-auth interceptor sends `Bearer mock-sysadmin`, which the production backend now rejects (mock filter is gone) — that's intentional: any path that hits the real backend with this header gets a clean 401, surfacing accidental misconfiguration loudly rather than silently authenticating. The SPA seam re-rips when a real-OIDC Playwright project lands (S-021 follow-up).
- **No new SpEL constructs.** Re-use the already-shipped `principal.claims['clubId']` predicate pattern from S-048's `ClubsController` — typed-ID SpEL discipline (`#id.value().toString()` vs prefixed external form) is already captured in `CONVENTIONS.md §Typed entity IDs`.

## Edge cases & hidden requirements

- **Token without `realm_access` claim** (e.g. a Proffix client-credentials token that goes through future S-029 wiring): converter returns an empty authority list; every `@PreAuthorize("hasRole(...)")` evaluates to false → 403. Acceptable: machine-client paths will declare their own predicate (`hasAuthority('SCOPE_proffix-sync')`) at S-029.
- **Token with `realm_access.roles` containing a role not in our catalog** (`OFFICE_USER`, `PILOT`, `GUEST`, `default-roles-alpenflight`, ...): converter promotes them all to `ROLE_*` authorities verbatim — they just don't grant access to any S-026 predicate because no `@PreAuthorize` references them. No filter needed.
- **CLUB_ADMINISTRATOR + missing `clubId` claim** (federated / not-yet-imported user): `principal.claims['clubId']` is null; `#id.value().toString() == null` is false → 403. The per-club paths fail closed. Sysadmin disjunct remains the escape hatch for the operator.
- **`MockSecurityConfigAbsenceIT` rationale is gone.** The IT exists to assert mock beans don't leak outside the `mock-auth` profile. When the package is deleted the IT can't reference the type — delete the IT in the same commit.
- **`UserTenantLookup` had `@Profile("!mock-auth")`.** When mock-auth disappears the guard is meaningless; remove the annotation so the bean is always present.

## Security plan

- **Real auth chain is the only chain post-rip.** The production `SecurityConfig` is no longer profile-gated on `!mock-auth`; it becomes the default chain unconditionally. `EnableMethodSecurity` stays on it.
- **CLUB_ADMINISTRATOR own-club check is SpEL-driven.** `#id.value().toString() == principal.claims['clubId']` compares the path-variable typed ID to the validated JWT claim. `JwtIssuerValidator` (S-020) already attests claim origin; tenant-isolation invariants are not relaxed by this story (a CLUB_ADMINISTRATOR for tenant A still cannot read tenant B's children — `@TenantId` plumbing handles that downstream; the predicate is a defense-in-depth gate on the Clubs aggregate root specifically).
- **FLIGHT_OPERATOR read-only on own club.** Mutation paths on Clubs do NOT grant FLIGHT_OPERATOR; the role is a viewer on the Clubs aggregate. This is the legacy `RoleApplicationKeyStrings.FlightOperator` semantic ("can run flight ops for this club but cannot administer it").
- **The mock-auth rip-out closes a config-pivot risk:** until now, `SPRING_PROFILES_ACTIVE=mock-auth` on any environment downgrades the entire app to "every request is sysadmin". The `forbidInProd()` PostConstruct guard catches the worst case (mock + prod co-active) but not "mock-auth on a staging or shared-dev box". Deleting the profile structurally removes the footgun.

## Test plan

- `ClubsAuthorizationTest` (already exists, expand): one `@Test` per (role × Clubs method × outcome) combination that's load-bearing:
  - anonymous → 401 on every method (single test covers the chain; already present)
  - SYSTEM_ADMINISTRATOR → 200/201/204 on every method (one per method)
  - CLUB_ADMINISTRATOR (own club) → 200 on `GET /{id}`, `PUT /{id}`; 403 on `GET /` (list), `POST /`, `DELETE /{id}`
  - CLUB_ADMINISTRATOR (other club) → 403 on `GET /{id}`, `PUT /{id}` (the per-club SpEL gate)
  - FLIGHT_OPERATOR (own club) → 200 on `GET /` (list — viewer can see catalog), `GET /{id}` (own club read); 403 on `POST`, `PUT`, `DELETE`
  - FLIGHT_OPERATOR (other club) → 403 on `GET /{id}`
- `SecurityFilterChainIT` already covers the real `JwtDecoder` path against a synthesised RSA-signed token. No additional cases needed — converter behavior is exercised by the role-gate matrix above against the same authority shape.
- Test seed: re-use the existing canonical seed `clb-019e30c3-2c00-7001-8000-000000000001`. "Other club" tests mint a JWT with a different UUID in the `clubId` claim — no second seeded club row needed because the SpEL gate runs before any DB read on the `GET /{id}` happy path.
- Negative regression: post-rip, a request with `Authorization: Bearer mock-sysadmin` against the running backend (the SPA mock-auth header) returns 401. One IT case proves this so the SPA seam's intentional rejection is locked in.

## Performance plan

(N/A — pure authorization wiring on already-running endpoints; no DB queries added, no new caches.)

<!-- modernize-refine: end -->
