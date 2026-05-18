---
id: S-020
title: Spring Security 7 OAuth2 resource server wired
epic: E-03
status: done
started_at: 2026-05-18
done_at: 2026-05-18
github_issue: 61
github_pr: 62
depends_on: [S-001, S-019]
acceptance:
  - `spring-boot-starter-oauth2-resource-server` is in the dependency graph.
  - `application.yml` references the Keycloak issuer URI (env-variable for production override).
  - The hello endpoint from S-001 requires `Authorization: Bearer <token>`; unauthenticated requests return 401.
  - A test using a valid token from the seed realm reaches the endpoint; an expired/invalid token is rejected.
  - The OpenAPI spec from S-003 includes the `bearerAuth` security scheme in its operations.
estimate: M
adr_refs: [0007]
parity_test: none
refined: true
refined_at: 2026-05-18
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer]
context7_last_checked: 2026-05-18
reviewed: true
reviewed_at: 2026-05-18
review_outcome: improvements-only
review_blockers: 0
review_improvements: 12
review_nudges: 6
review_parity_oracle: N/A — parity_test=none + no flsserver/flsweb in diff
review_reviewers: [maintainability, security, tech-writer]
merged: true
merged_at: 2026-05-18
---

## Context
ADR 0007 picks the protocol; this story wires it up on the server. S-019 stood up the Keycloak realm + dual-port topology this depends on. Audience validation is deferred until the production IdP is selected — see Load-bearing decisions.

## Load-bearing decisions (not visible from code alone)

- **Audience validation deferred.** Operator call 2026-05-18: production IdP could be Google / Ory / Auth0 instead of Keycloak (ADR 0007 open item). Locking the validator chain to a vendor-specific audience mapper forfeits portability. The validator chain is `JwtTimestampValidator(60s)` + `JwtIssuerValidator` only; user → tenant authorization goes through DB lookup in S-022 (claim-first with `sub`/`email` fallback per existing project memory), not via `aud`.
- **Programmatic `JwtDecoder` over property auto-config.** Spring's `spring.security.oauth2.resourceserver.jwt.issuer-uri` carries one URL for both JWKS discovery and `iss` validation; the compose topology needs them split (`http://keycloak:8080` for JWKS, `http://localhost:8090` for `iss`). The `JwtDecoder` bean is in `JwtDecoderConfig` (profile-agnostic) so it also suppresses auto-config under `mock-auth` — preventing an OIDC discovery call against an unreachable Keycloak during context startup.
- **Mock-auth chain NOT ripped out here.** `ch.alpenflight.auth.MockSecurityConfig` + `MockAuthenticationFilter` coexist with the production chain through this story. Rip-out belongs to S-022's mark-done commit once `@TenantId` reads the real `clubId` claim end-to-end.
- **Hello endpoint flipped from `permitAll` to `authenticated()`** — the S-001 `TODO(S-020)` marker is resolved. T3 smoke (S-110) now needs a Bearer token; token-acquisition for T3 lands at S-110, not here.

## Cross-story contracts

- **S-022** consumes `JwtAuthenticationToken` with `principal.getToken().getClaimAsString("clubId")` reachable; absent for federated / legacy-imported users, which the resolver handles via DB fallback.
- **S-026** consumes `ROLE_*` authorities populated by `ClubAwareJwtAuthenticationConverter`.
- **S-029** uses the same `JwtDecoder` to validate Proffix client-credentials tokens (no `clubId`, no audience-mapper requirement under the deferred-audience decision).
- **S-027** consumes `Authentication{Success,Failure}Event`s + the INFO-level rejection log emitted by `LoggingBearerTokenAuthenticationEntryPoint`.

## Production handoff (deferred)

- HTTPS-only JWKS in prod via env (`ALPENFLIGHT_OIDC_JWK_SET_URI` / `ALPENFLIGHT_OIDC_ISSUER_URI`). Receiving story: S-041 reverse-proxy + S-151 prod Keycloak (or hosted-IdP swap).
- Audience validation: re-evaluate after the prod IdP is chosen. If we stay on Keycloak, the realm needs a hardcoded audience mapper; if we move to Google / Ory, validation stays DB-driven.

## Review

<!-- modernize-review: start -->

### Parity
**Oracle:** N/A — `parity_test: none` + no `flsserver/` / `flsweb/` paths in the diff. S-020 is greenfield resource-server wiring; the legacy `/Token` OAuth2 password grant is being replaced wholesale.

<!-- modernize-review: end -->
