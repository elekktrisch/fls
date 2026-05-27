---
id: S-169
title: Users — JIT projection on first authenticated login
epic: E-06
status: todo
estimate: S
parity_test: none
depends_on: [S-052]
integration_base: integration/users-suite
adr_refs: [0007, 0022, 0023]
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
github_issue: 139
origin: scope-split
origin_story: S-052
---

## Context

Split off from [S-052](implemented/S-052-users-crud.md). S-052 shipped a
`UsersService.materializeFromJwt(...)` method that creates a local `user`
row from JWT claims; this story wires the call into the
JWT-to-tenant-resolution flow so the projection actually fires.

The two flows that depend on this:
- **S-028 bulk-provision tenant users** writes Keycloak users with `clubId`
  attributes; the local `user` row is created on each user's first login.
- **Federated IdP** (Google etc., S-134) carries roles+clubId in the JWT
  but never round-trips through the invite flow.

## Acceptance criteria

- On any authenticated request, if the JWT carries `sub` (UUID) +
  non-empty `realm_access.roles[]` + a parseable `clubId` claim, and no
  active `user` row matches `keycloak_sub`, a row is materialised before
  the controller dispatches.
- The materialise path uses `UsersService.materializeFromJwt` (already in
  place from S-052) so the aggregate invariants + audit trail fire.
- The transaction model avoids recursing through Hibernate's session-open
  hook (`UserPrincipalLookup` is currently called from
  `ClubTenantIdentifierResolver.resolveCurrentTenantIdentifier`). Cleanest
  is a Spring Security filter or `OncePerRequest` filter that runs after
  JWT decode but before controller dispatch, wrapping the materialise in
  `@Transactional(propagation = REQUIRES_NEW)`.
- Concurrent first-login requests for the same `sub` resolve to a single
  row (DB partial-unique on `keycloak_sub` is the structural net; the
  service-layer `findActiveByKeycloakSub` + `save` race-loses gracefully).
- IT: `UsersJitFirstLoginIT` — first request with a fresh sub creates one
  row; second request reuses it; two concurrent first-login requests with
  the same sub produce exactly one row.

## Notes

- The legacy seed `V8__dev_user_seed.sql` stays in place until this story
  has shaken out one full dev bring-up cycle without it (carried over
  from S-052's rip-out plan).
- This is a backend-only story. SPA UI (S-168) does not depend on it.

<!-- modernize-refine: start -->

## Design notes

**Filter shape + placement.** New `JitUserMaterializationFilter extends OncePerRequestFilter` in `platform/security/` (cross-cutting; not aggregate-bound per ADR 0023). Registered via `HttpSecurity.addFilterAfter(filter, BearerTokenAuthenticationFilter.class)` so it runs after JWT decode + `SecurityContextHolder` population, before `AuthorizationFilter` evaluates `@PreAuthorize`.

**Skip conditions (in order, all short-circuit to `chain.doFilter`).**
1. `SecurityContextHolder` authentication is not a `JwtAuthenticationToken` — permitted endpoints (springdoc, actuator, `/error`) fall through here. Match by principal-shape, not URI pattern.
2. JWT lacks parseable `clubId` claim — sysadmin tokens carry none (S-022); federated baseline tokens (S-134) carry none. Both skip materialise and proceed.
3. `UserPrincipalLookup.resolveUserIdFor(jwt)` returns non-empty — idempotency check; reuses the existing JDBC path (indexed UNIQUE on `keycloak_sub`, no JPA session, no recursion through `ClubTenantIdentifierResolver`).

**Soft-delete gate (load-bearing).** If `findAnyByKeycloakSub(sub)` (including `deleted_on IS NOT NULL`) returns a soft-deleted row, throw `UserDeactivatedException` → 403 with terse `ProblemDetail`. Closes the in-window stale-token gap (≤ 15 min per ADR 0007) that ADR 0007's "stale JWT" residual leaves open. Without this gate, a CLUB_ADMIN's Deactivate has no real effect for the token's residual lifetime.

**Re-entry path (couples to S-052).** Modify `UsersService.softDelete` to also set `keycloak_sub = NULL` on the soft-deleted row (one extra column in the UPDATE). This frees the KC identity from the global partial UNIQUE (`ux_user_keycloak_sub ON t_user (keycloak_sub) WHERE keycloak_sub IS NOT NULL`) so any CLUB_ADMIN can re-invite the same KC user — same club or different. New invite creates a fresh `t_user` row with its own `id`; the old row keeps its audit history as a tombstone. Linkage between old + new rows is via username + KC user_id, not a FK chain.

**Transaction boundary.** Filter delegates to a thin `JitUserMaterializer` `@Component` (separate bean for proxy-based AOP) whose method is `@Transactional(propagation = REQUIRES_NEW)` and wraps `usersService.materializeFromJwt(jwt, languageId)`. The request's own transaction (opened later by the controller's `@Transactional`) stays untainted.

**Race-loser handling.** Catch `DataIntegrityViolationException` from `users.flush()` (structural net: the partial UNIQUE), re-run `findActiveByKeycloakSub`, return the winner's id. No retry loop — partial UNIQUE guarantees exactly one survivor.

**Language mapping.** New `LanguageCodeLookup` JDBC helper alongside `UserPrincipalLookup` (keeps the JPA-free aisle). Maps JWT `locale` claim (BCP-47, lowercased) against `language.code`. **Unknown / missing locale → `en`** (`019e2e15-2c00-77d3-8000-0000000007d3`) — matches Keycloak's out-of-the-box default and federated-IdP common case.

**Malformed-token claims.** Missing `preferred_username` / `given_name` / `email`: filter logs WARN + skips materialise (does NOT 401/500). Token signature is valid; the local projection just can't be built. Downstream `@PreAuthorize` produces the expected 403 for tenant-scoped reads (no `t_user` row → no `personId` etc.). Federated onboarding edge cases handled silently.

**Observability.** Emit ONE INFO log on materialise (`sub`, resolved `clubId`, resolved `languageId`, generated `user.id`). Emit DEBUG (or skip) on steady-state hits. Add Micrometer counters: `users.jit.outcome{created|already-present|skipped-no-clubid|skipped-malformed|skipped-deactivated}` and timer `users.jit.lookup` for the cheap-path budget tracking.

**V8 dev seed.** Out of scope. Defer deletion to a follow-up after this story has soaked one dev bring-up cycle (per S-052 rip-out plan).

## Edge cases & hidden requirements

- **Permitted endpoints** (`/v3/api-docs/**`, `/swagger-ui/**`, `/actuator/health`, `/error`): filter must be a no-op when `SecurityContextHolder` has no `JwtAuthenticationToken` — guarded by an instance-of check, identical to `ClubTenantIdentifierResolver`.
- **Sysadmin first-login**: skip materialise (no `clubId`); sysadmin operates without a `t_user` row by design.
- **Soft-deleted re-login**: 403 via `UserDeactivatedException` (see Design § soft-delete gate). Forensics get a `skipped-deactivated` counter increment.
- **Race-loser**: filter catches `DataIntegrityViolationException`, re-reads via `UserPrincipalLookup`, proceeds. Request returns 2xx; loser doesn't fail.
- **Audit emission**: `materializeFromJwt` already records `CREATE` via `AuditTrailService` — `ActorResolver` handles "actor == target" for JIT rows (verified in `AuditTrailService` post-S-027).
- **Double JDBC query elimination**: filter + `ClubTenantIdentifierResolver` both query `t_user.keycloak_sub` on the realm-token path. Stash the resolved `userId` (or sentinel `ABSENT`) as a `ServletRequest` attribute so `UserPrincipalLookup` short-circuits on the second call within the same request. ~10 LOC; halves per-request DB touches.

## Security plan

- **Soft-delete gate** (load-bearing): see Design notes. Refuses 403; counter `users.jit.outcome{skipped-deactivated}` tagged with `clubId` for ops visibility.
- **Tenant-binding integrity**: `clubId` sourced from `jwt.getClaimAsString("clubId")` only — never `TenantContext` (circular). The `materializeFromJwt` signature taking `Jwt` (not a DTO) keeps this structural.
- **REQUIRES_NEW boundary**: materialise failure rolls back independently; inbound request's tx stays clean.
- **PII in logs**: log only `keycloak_sub` (UUID, not PII) + materialised `user.id` on success; only `sub` + exception class on failure. Never `email` / `preferred_username` / `given_name`.
- **Audit row inherits S-027 PII policy** — same `toResponse(saved)` snapshot S-052 emits.
- **DoS via unbounded materialise** — accepted residual. A compromised IdP can mint distinct subs at request rate; FLS has no per-issuer rate limit. Same posture as S-051 lookup-rate-limit deferral. Follow-up: per-issuer Prometheus alert on `users.jit.outcome{created}` rate spikes.
- **Identity-claim trust**: IdP is OIDC root of trust per ADR 0007; `realm_access.roles[]` empty → `@PreAuthorize` denies. Intentional.

## Test plan

**Layer.** IT only (`UsersJitFirstLoginIT`); unit test on the filter's `shouldMaterialise(jwt)` claim-predicate (pure, branchy). Skip unit-mocking the filter wiring — IT covers it.

**Concurrency assertion.** `ExecutorService` (pool 2) + `CountDownLatch(1)` start gate; submit two `MockMvc` calls with the same `Bearer` token through the filter; post-join assert `findAllByKeycloakSub(sub).size() == 1` AND both requests returned 2xx. `@RepeatedTest` flake-amplifies; `parallelStream` skips the filter+REQUIRES_NEW boundary.

**JWT fixture extension.** Add `JwtTestFixture.mintJitReady(UUID sub, UUID clubId, Consumer<Builder> extra)`: sub = bare UUID string, `clubId` claim set, `preferred_username` + `given_name` + `email` defaulted. Don't change existing `mint(...)` (cascades).

**Pin these IT cases (don't over-enumerate):**
- Happy-path: fresh sub → row created; second request → reused.
- Soft-deleted re-login → 403 (witnesses the soft-delete gate).
- Sysadmin token (no `clubId`) → 200, no row.
- Token with sub but missing `preferred_username` → 200, no row, WARN logged.
- `/actuator/health` anonymous → filter no-op, 200.
- Re-invite after softDelete with `keycloak_sub = NULL` detach → new `t_user` row created via invite flow; sub re-linked. (Pins the re-entry contract.)

**Cleanup.** REQUIRES_NEW commits independently of `@Transactional` rollback. `@AfterEach` `DELETE FROM t_user WHERE keycloak_sub = ?` for the test's fresh UUID — don't assume rollback.

**Index-predicate verification.** `EXPLAIN` in one IT to assert `ux_user_keycloak_sub` is picked for the cheap-path query (catches accidental `deleted_on = NULL` typo that would force seq-scan).

## Performance plan

- **Per-request hot-path cost**: one indexed lookup on `t_user(keycloak_sub) WHERE keycloak_sub IS NOT NULL` via `ux_user_keycloak_sub`. Sub-ms on a warm pool connection.
- **Double-query elimination**: stash `userId` (or `ABSENT` sentinel) as `ServletRequest` attribute; `UserPrincipalLookup` consults it before JDBC. 10-line change; halves per-request DB touches on the realm-token path.
- **REQUIRES_NEW on first login only**: ~1–2ms once per principal. Acceptable; only the materialise path opens a new tx.
- **No in-memory cache**: premature. Indexed point-lookup is already cheap; cache adds invalidation surface on soft-delete / sub-detach.
- **Concurrent first-login race**: loser pays one wasted tx. Rare; acceptable.
- **Measure**: Micrometer timer `users.jit.lookup` (cheap-path); counter `users.jit.outcome{...}`. Pass threshold: cheap-path p95 < 2ms — no regression vs current `UserPrincipalLookup.resolveTenantFor`.

## Follow-up stories implied

- **S-172 (proposed) — Federated IdP multi-club model.** Schema change: drop the global `ux_user_keycloak_sub` constraint, add `(keycloak_sub, club_id) WHERE deleted_on IS NULL`. One KC identity can be alive in N clubs concurrently. Cross-cuts `ClubTenantIdentifierResolver` (one sub → multiple tenants → need a club-picker on login). File post-S-169.
- **(unnumbered) — V8 dev seed deletion.** Follow-up Flyway migration to drop `V8__dev_user_seed.sql`. Land after one full dev bring-up with S-169 active.
- **(unnumbered) — KC disable on softDelete.** Out-of-window gate: invalidate refresh tokens at the IdP so the user is fully logged out within ≤ 15 min. Defense-in-depth on top of S-169's in-window gate. Couples to S-052; file when reactivate-UI lands.

<!-- modernize-refine: end -->
