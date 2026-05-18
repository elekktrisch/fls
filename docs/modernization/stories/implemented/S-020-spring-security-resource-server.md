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

**Reviewed:** 2026-05-18 · **PR:** #62 (OPEN, READY_FOR_REVIEW) · **Outcome:** improvements-only (0 blockers, 12 improvements, 6 nudges)

### Maintainability
- **[improvement]** `MockSecurityConfig` Javadoc + `package-info.java` + `application-mock-auth.yml` all describe the mock-auth rip-out as triggered by "S-019/S-020 land commit" — `next/server/src/main/java/ch/alpenflight/auth/MockSecurityConfig.java:28-43`, `next/server/src/main/java/ch/alpenflight/platform/security/package-info.java:2-11`, `next/server/src/main/resources/application-mock-auth.yml:7`. Post-S-020 these mislead: the story's load-bearing decision explicitly moves the rip-out to S-022. **Fix:** update all three doc-blocks to cite S-022 (or drop the rip-out plan entirely — the issue tracker is fine).
- **[improvement]** Dead local + redundant bean-presence assertion in IT — `next/server/src/test/java/ch/alpenflight/platform/security/SecurityFilterChainIT.java:121-123`. `BearerTokenAuthenticationToken bogus` is constructed, asserted non-null, then unused; the `@Autowired JwtDecoder` field already proves the bean exists. **Fix:** drop the `bogus` line + `getBeansOfType` line, keep `assertThatThrownBy(jwtDecoder.decode(...))`.
- **[improvement]** `mintWithoutSignature` hand-rolls base64 string concat — `next/server/src/test/java/ch/alpenflight/platform/security/JwtTestFixture.java:73-86`. Nimbus ships `PlainJWT` for exactly this; the hand-rolled version duplicates encoding logic the rest of the fixture delegates to Nimbus. **Fix:** `new PlainJWT(claims).serialize()`.
- **[improvement]** Fully-qualified `MockMvcResultMatchers.header()` + `Matchers.startsWith` inline in test bodies — `next/server/src/test/java/ch/alpenflight/clubs/ClubsAuthorizationTest.java:73-75`, `SecurityFilterChainIT.java:72-73,124-126`. Inconsistent with the existing static-import block at the top of each file. **Fix:** hoist to static imports.
- **[nudge]** `SecurityFilterChainIT` duplicates the `@DynamicPropertySource` testcontainer wiring from `ClubsAuthorizationTest` (≈ 8 lines). Tolerable at 2 call sites; pays off as a shared base class at 3.
- **[nudge]** `JwtDecoderConfig` Javadoc says auto-config is "suppressed under every profile" without naming the auto-config class — `next/server/src/main/java/ch/alpenflight/platform/security/JwtDecoderConfig.java:18-22`. A reader hunting "why is auto-config silent here?" would benefit from `OAuth2ResourceServerJwtConfiguration` cited explicitly.

### Parity
**Oracle:** N/A — `parity_test: none` + no `flsserver/` / `flsweb/` paths in the diff. S-020 is greenfield resource-server wiring; the legacy `/Token` OAuth2 password grant is being replaced wholesale.

### Security
- **[improvement]** `/error` permit-listed with `server.error.include-message: always` in the dev profile — `next/server/src/main/java/ch/alpenflight/platform/security/SecurityConfig.java:62`, `next/server/src/main/resources/application-dev.yml:11`. Spring's ErrorController dispatch needs the permit-list entry, but direct `GET /error` discloses a JSON body in dev. **Fix:** add a regression IT asserting anonymous `GET /error` returns a body free of stack / env data on every profile.
- **[improvement]** `alg=none` rejection is proven only against the test decoder, which uses `withPublicKey(...)` and is RSA-pinned — `next/server/src/test/java/ch/alpenflight/platform/security/JwtTestFixture.java:49-56`. The production `JwtDecoderConfig` uses `withJwkSetUri(...)`; Nimbus restricts to the JWKS-advertised algs, but no test exercises the production decoder directly. **Fix:** add a focused JWKS-backed unit test on `JwtDecoderConfig` asserting `alg=none` rejection.
- **[improvement]** No architecture-test guard pinning the validator chain to exactly `{JwtTimestampValidator, JwtIssuerValidator}` — `next/server/src/main/java/ch/alpenflight/platform/security/JwtDecoderConfig.java:24-29`. A future change adding a vendor-specific validator (or silently dropping one) wouldn't be flagged. **Fix:** assert the delegate-validator set in an architecture / unit test.
- **[improvement]** `mintWithoutSignature` relies on `JWTClaimsSet.toString()` being JSON — `JwtTestFixture.java:84`. Nimbus documents `toString()` as best-effort; works today because the alg=none token is rejected before payload parsing, so the gap is latent. **Fix:** `claims.toJSONObject().toJSONString()`.
- **[nudge]** INFO-level rejection log will be noisy under credential-stuffing — `LoggingBearerTokenAuthenticationEntryPoint.java:34`. Acceptable for now (S-027 aggregates); consider rate-limited WARN once auth scanners hit prod.

### Code quality (tech-writer)
- **[improvement]** `package-info.java` describes a pre-S-020 state — `next/server/src/main/java/ch/alpenflight/platform/security/package-info.java:2-11`. Opening sentences still say "no-auth-yet permissive" and "kept past the S-019/S-020 rip-out"; both were true before S-020. A reader entering the package sees what it used to be, not what it is. **Fix:** rewrite to describe the current surface (production `SecurityFilterChain`, `JwtDecoderConfig`, converter, entry point); note mock-auth deletion moves to S-022.
- **[improvement]** `ClubAwareJwtAuthenticationConverter` Javadoc says "this converter is what stays after the S-019/S-020 rip-out" — `next/server/src/main/java/ch/alpenflight/platform/security/ClubAwareJwtAuthenticationConverter.java:23-25`. Post-S-020, the future-tense framing is confusing. **Fix:** trim to "shared between the production chain and mock-auth chain until S-022."
- **[improvement]** `LoggingBearerTokenAuthenticationEntryPoint` Javadoc lists what's logged but omits `request.getRequestURI()` — `next/server/src/main/java/ch/alpenflight/platform/security/LoggingBearerTokenAuthenticationEntryPoint.java:19-22`. The Javadoc says "exception class only — no token bytes, no claims map, no principal" but the log line emits the URI. The URI isn't PII, but the enumeration is incomplete and matters for the PII-free claim a future auditor will read. **Fix:** add "request URI" to the list.
- **[improvement]** `JwtDecoderConfig` Javadoc leads with "Keycloak topology" — `next/server/src/main/java/ch/alpenflight/platform/security/JwtDecoderConfig.java:16`. The bean is explicitly IdP-portable (that's the point of the class); leading with Keycloak misframes it. **Fix:** lead with "split-port OIDC topology"; cite Keycloak as the local-dev example.
- **[nudge]** `next/auth/README.md` §"Gotcha for S-020" — header reads as a live gotcha when S-020 has now resolved it. **Fix (when next/auth is next touched):** rename to "Split-port JwtDecoder (resolved in S-020)".

### Cross-reviewer agreements
- **Mock-auth rip-out timing drift** — flagged by **maintainability** + **tech-writer** across three doc anchors (`MockSecurityConfig` Javadoc, `package-info.java`, `application-mock-auth.yml`). All three still reference S-019/S-020 as the trigger; the story body now pins S-022. Strongest signal — multiple anchors carrying the same stale claim.
- **`mintWithoutSignature` rough edges** — flagged by **maintainability** (use `PlainJWT`) + **security** (`JWTClaimsSet.toString()` is best-effort). Same fix closes both.

<!-- modernize-review: end -->
