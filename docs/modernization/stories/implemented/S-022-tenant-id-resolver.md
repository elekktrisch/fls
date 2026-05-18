---
id: S-022
title: ClubTenantIdentifierResolver + @TenantId plumbing on first entity
epic: E-03
status: done
started_at: 2026-05-18
done_at: 2026-05-18
github_issue: 66
github_pr: 67
depends_on: [S-012, S-015, S-020]
acceptance:
  - `ClubTenantIdentifierResolver` (Hibernate `CurrentTenantIdentifierResolver<UUID>`) follows the precedence chain: `TenantTestContextAccess` → JWT `clubId` claim → `UserTenantLookup` by `keycloak_sub` → `NO_TENANT` nil-UUID sentinel.
  - Hibernate discriminator multi-tenancy is active via the `@TenantId` annotation alone — no `spring.jpa.properties.hibernate.*` keys.
  - `@TenantId` is applied to the V2 `member_state` worked-example entity.
  - `MemberStateRepository.findAll()` under `@WithTenant(A)` returns A's rows; mid-test `runAs(B)` sees B's rows.
  - Without a tenant context, reads return empty (sentinel filter matches no real row) and inserts fail at the `fk_member_state_club_id` FK constraint.
estimate: M
adr_refs: [0008, 0019, 0021, 0022]
parity_test: none
refined: true
refined_at: 2026-05-18
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
context7_last_checked: 2026-05-18
---

## Context

ADR 0008's structural multi-tenancy plumbing. Once this lands, every future tenant-scoped entity just needs `@TenantId` on its `club_id` column. CONVENTIONS.md §Multi-tenancy is the operational reference.

## Cross-story contracts

- **S-023 (UnscopedTenantContext)** consumes: `ClubTenantIdentifierResolver.NO_TENANT` as the sentinel value an explicit unscoped session writes to the resolver hook.
- **S-024 (cross-tenant leakage CI)** consumes: the fail-closed contract (reads filter to zero rows, writes fail at FK on `club_id`). The CI sweep enforces "no unmarked native SQL in tenant-scoped code"; `UserTenantLookup` is the documented exemption (cross-tenant `user` table).
- **S-025 (public-flow tenant from URL)** consumes: the resolver's "anonymous → `NO_TENANT`" branch so public-path resolution can write into `TenantTestContextAccess`-shaped seam from a URL parameter instead of the JWT.
- **Every future aggregate-root entity** consumes: `@UuidV7` (`ch.alpenflight.platform.id`) for application-side UUID v7 generation per ADR 0019.

## Deviations from refinement (operator review)

The refine (PR #63) settled four design decisions; the implementation pass simplified two of them after the operator asked for "maximum simplification without violating the important parts":

- **Trusted-issuer allowlist dropped.** Spring Security's `JwtIssuerValidator` already authenticates `iss` against the single configured `spring.security.oauth2.resourceserver.jwt.issuer-uri` before any `JwtAuthenticationToken` reaches the resolver. A future federated-IdP onboarding (multiple simultaneous issuers) revisits this.
- **Insert-poisoning `PreInsertEventListener` + integrator dropped.** The FK constraint on `member_state.club_id → club.id` already rejects nil-UUID inserts at commit time with `DataIntegrityViolationException`. No domain-flavored exception layer needed; tests assert the FK rejection directly.
- **`UserTenantLookup` simplified.** Single path: `keycloak_sub`-by-UUID. The email-verify-and-unique path is dropped until a non-UUID-sub IdP (Google numeric subjects) onboards.

Kept load-bearing: the resolver itself; `UserTenantLookup` (claim-absent DB fallback — IdP portability); `TenantTestContextAccess` (`@WithTenant("uuid")` ergonomics in test code without crafting JWTs); `@UuidV7` + generator; `MemberState` worked example; mock-auth chain stays through S-026.

## Rip-out + open seams

- **Mock-auth chain (`ch.alpenflight.auth.*`)** stays through S-026 per the refine. `MockSecurityConfig` Javadoc rip-out target updated S-019/S-020/S-022 → S-026.
- **`TenantTestContextAccess.set` is `public` on production classpath.** Test-support package boundary (S-015's `TestSupportPackageBoundaryTest`) prevents `src/main` from importing `ch.alpenflight.server.testsupport`, but does NOT prevent a same-package main class from calling `TenantTestContextAccess.set(uuid)` and bypassing the JWT path. No current production caller; a future story may add an ArchUnit rule scoping callers to `ch.alpenflight.server.testsupport`.
- **No DB-result memoization on `UserTenantLookup`.** The refinement's perf plan proposed per-`Authentication` request-scoped memoization. Dropped during simplification — claim path is `Map.get` and the DB-fallback path is the rare federated case. Revisit if metrics show repeated lookups in the hot path once federated users exist.
