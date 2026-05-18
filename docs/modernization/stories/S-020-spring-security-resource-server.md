---
id: S-020
title: Spring Security 7 OAuth2 resource server wired
epic: E-03
status: in_progress
started_at: 2026-05-18
github_issue: 61
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
---

## Context
ADR 0007 picks the protocol; this story wires it up on the server.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add dependency.
- [ ] Configure `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
- [ ] Build `SecurityConfig` with `http.oauth2ResourceServer(jwt -> jwt.jwkSetUri(...))`.
- [ ] Default route security: `authenticated()` on `/api/v1/**`, `permitAll()` on `/actuator/health/**` and public paths.
- [ ] Map JWT claims to `Authentication.authorities` — start with `realm_access.roles` from Keycloak.
- [ ] Update springdoc-openapi config so each operation declares `bearerAuth`.

## Notes
S-026 layers role-based `@PreAuthorize` on top. This story is just "the gate is closed and JWT is required."

<!-- modernize-refine: start -->

## Design notes

### What's already in the tree (skeleton baked dormant by S-048)

- `build.gradle.kts` already pulls `spring-boot-starter-security`, `spring-security-oauth2-resource-server`, `spring-security-oauth2-jose`. **AC1 (dependency present) is structurally satisfied — story task is config + bean, not gradle.**
- `next/server/src/main/java/ch/alpenflight/platform/security/SecurityConfig.java` — `@Profile("!mock-auth")` chain: CSRF off, STATELESS, 401 entry point, enumerated `permitAll` allowlist, `anyRequest().authenticated()`. Missing: `.oauth2ResourceServer(...)` configurer + `JwtDecoder` bean.
- `ClubAwareJwtAuthenticationConverter.java` — maps `realm_access.roles[]` → `ROLE_*`. **Stays unchanged**; S-020 wires it via `jwt(j -> j.jwtAuthenticationConverter(...))`.
- `OpenApiConfig.java` already registers the `bearerAuth` `SecurityScheme` in `Components`. Missing: the top-level `addSecurityItem(new SecurityRequirement().addList("bearerAuth"))` so every operation inherits it (AC5 gap).

### Module shape (delta only)

- `SecurityConfig.java`: + `.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(clubAwareConverter)))`; + package-private `@Bean JwtDecoder jwtDecoder(...)` (programmatic — see below); remove `/api/v1/hello` from the `permitAll` list per AC3.
- `application.yml`: + `spring.security.oauth2.resourceserver.jwt.issuer-uri` (host URL — canonical `iss`, readable by tooling) + `jwk-set-uri` (network URL — actually used by decoder). Both env-overridable (`ALPENFLIGHT_OIDC_ISSUER_URI`, `ALPENFLIGHT_OIDC_JWK_SET_URI`) for prod hosted-IdP swap. Audience validation deferred (see Open question 2).
- `OpenApiConfig.java`: + `.addSecurityItem(new SecurityRequirement().addList("bearerAuth"))` on the existing `OpenAPI` bean.
- `application-test.yml`: pin `issuer-uri: http://test-issuer` so `@SpringBootTest` doesn't attempt OIDC discovery at startup; `JwtTestFixture` mints tokens matching that `iss`.

### Decision: programmatic `JwtDecoder`, NOT property `issuer-uri` auto-config

WHY: dual-port `iss` mismatch (`next/auth/README.md` §Gotcha for S-020). Auto-config forces one URL for both JWKS discovery and `iss` validation; we need them split — Spring reaches Keycloak at `http://keycloak:8080` but every token's `iss` is `http://localhost:8090/realms/alpenflight`.

HOW: `NimbusJwtDecoder.withJwkSetUri(<jwk-set-uri>).build()` + `setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(60)), new JwtIssuerValidator(<issuer-uri>)))`. Auto-config backs off once a `JwtDecoder` bean exists; property values stay for tooling.

### Cross-story contracts

- **S-022** consumes `JwtAuthenticationToken` with `principal.getToken().getClaimAsString("clubId")` reachable. **The claim may be absent** (federated/Google OIDC, Proffix client, legacy-imported users) — S-022 owns the DB-fallback resolver; S-020 MUST NOT reject "no clubId" tokens at decode time. The current `ClubAwareJwtAuthenticationConverter` only reads `realm_access.roles`, so this holds.
- **S-026** consumes `ROLE_*` authorities populated by the converter — already in shape.
- **S-029** Proffix client-credentials token validates through the same `JwtDecoder`; no `clubId` claim is fine, but the same `aud=alpenflight-backend` requirement applies.
- **S-110** T3 smoke now needs `Authorization: Bearer …` to reach hello (per AC3 flip). Token acquisition is S-110's problem — realm has no direct grant on `alpenflight-web`; T3 either mints via admin REST against a dedicated test client OR a `test` profile enables direct grants. Not pinned here.

### Rip-out gate (this story does NOT delete mock-auth)

The `ch.alpenflight.auth` package (`MockSecurityConfig` + `MockAuthenticationFilter`) **stays** through S-020. Both chains coexist, profile-gated. The whole package + `application-mock-auth.yml` + SPA `mock-auth.interceptor.ts` deletes in S-022's mark-done commit once `@TenantId` reads the real `clubId` claim end-to-end. Documented in `MockSecurityConfig.java` Javadoc.

## Edge cases & hidden requirements

### Per AC

- **AC1** — dependency already on classpath (S-048 baked it). Story is config + bean, not gradle.
- **AC2** — split-port config (`issuer-uri` ≠ `jwk-set-uri`); both env-overridable. Single `ISSUER_URI` env var breaks inside compose.
- **AC3** — hello flips from `permitAll` to `authenticated()`. `HelloControllerIT` uses `addFilters=false` slice — unaffected; `OpenApiOffByDefaultIT` and actuator ITs are also unaffected (their paths stay permit-all). No other test touches `/api/v1/hello`.
- **AC4** — `alpenflight-web` has no direct grant. The test pattern is synthesized JWT via Nimbus through a test-only `JwtDecoder` override; live Keycloak round-trip deferred to S-029/S-110.
- **AC5** — global `SecurityRequirement` added to existing `OpenAPI` bean (one line). `OpenApiSnapshotIT` will detect the spec change — regenerate snapshot in the implementation commit, don't leave for review.

### Hidden requirements

- **`sysadmin` carries no `clubId` claim** (S-019 seed table). Converter handles missing `clubId` already; document the explicit no-reject behavior.
- **Clock-skew tolerance** is NOT inherited from auto-config when an explicit `JwtDecoder` bean is registered — must be pinned in the validator chain (`JwtTimestampValidator(Duration.ofSeconds(60))`). Silence = zero skew = NTP drift between containers fails valid tokens.
- **JWKS cache default** (Nimbus, 5 min + `kid`-miss refresh) — do NOT override. The cache is the brown-out defense.
- **`@WebMvcTest` slice + `JwtDecoder`** — any new slice test in S-022+/S-026 needs `@MockBean JwtDecoder` (or `addFilters=false`); without it the slice fails to start once `oauth2ResourceServer` is wired. Hand-off note for S-022/S-026.
- **`@EnableMethodSecurity` is on BOTH `SecurityConfig` and `MockSecurityConfig`** — benign duplicate (only one active per profile context) but generates a Spring log warning. Leave alone; verify the warning is not suppressing real issues.
- **Failure observability** — `OAuth2AuthenticationException` rejections surface only as a 401 via `BearerTokenAuthenticationEntryPoint` (no application log). Add a `BearerTokenAuthenticationEntryPoint` subclass that logs the rejection cause class-name at INFO with no token content. Keeps S-027 audit signal usable; current 401 is silent.

### Scope

**In:** `.oauth2ResourceServer(...)` configurer, programmatic `JwtDecoder` (split-config + timestamp + issuer + audience validators), `application.yml` properties (env-overridable), global `bearerAuth` SecurityRequirement, hello flipped to authenticated, full-chain IT with synthesized JWT, INFO-level rejection logging.

**Out:** role-gate tests (S-026), `@TenantId` resolver (S-022), audit log (S-027), Proffix client-credentials test (S-029), browser OIDC E2E (S-021/S-110), CORS bean (deferred per S-001), live-Keycloak IT (deferred to S-029).

## Security plan

### Threat model (S-020 deltas)

- **[HIGH] Token forgery via dual-port iss misconfig.** Mitigation: programmatic `JwtDecoder` + explicit `JwtIssuerValidator(<host>)` + `JwtTimestampValidator(60s)`. Unit test asserts both validators present on the chain; load-bearing — without `JwtIssuerValidator`, S-020 silently weakens to "JWKS-signed = accepted." (Audience validation deferred per Open question 2.)
- **[HIGH] Token replay / refresh leak.** Mitigation lives in Keycloak realm (`revokeRefreshToken=true`, 15-min access TTL) — inherited from S-019. Not S-020 code.
- **[MED] Algorithm confusion (`alg=none`, HS256 with public key).** Mitigation: `NimbusJwtDecoder.withJwkSetUri(...)` accepts only the JWS algs the JWKS advertises (RS256 in our realm). Test asserts `alg=none` token → 401.
- **[MED] Clock-skew abuse.** Pin 60s explicitly in the validator chain. Document why not zero in SecurityConfig javadoc.
- **[MED] CSRF re-enable temptation.** Javadoc on `SecurityConfig` pins "Bearer-only → CSRF disabled" + ADR 0007 link, blocking a future cookie-flip drive-by.
- **[LOW] `WWW-Authenticate: error_description` info disclosure.** Accept Spring default (verbose only when token present-but-invalid, not for missing token). Verify in test.
- **[LOW] Mock-auth in prod.** Existing `@PostConstruct` profile-collision guard stays. S-020 does NOT delete mock-auth.

### Cross-cutting rules

- **PII:** no logger in `SecurityConfig` / `ClubAwareJwtAuthenticationConverter` / the rejection-log subclass may emit `jwt.getClaims()` at any level — claims map is PII-bearing. Rejection log logs validator-class only.
- **Spring Security auth events** (`AuthenticationSuccessEvent`, `AuthenticationFailureBadJwtEvent`) must NOT be suppressed — S-027 consumes them.
- **Allowlist enumerated, not pattern-based.** No `/api/v1/**` path may slip into `permitAll`. Verify in code review.
- **springdoc allowlist** stays scoped to `/v3/api-docs*` + `/swagger-ui*`; prod still has `springdoc.api-docs.enabled=false` (regression-locked by `OpenApiOffByDefaultIT`).

### Production handoff (deferred)

- HTTPS-only JWKS in prod via env (`JWK_SET_URI=https://idp.example.com/.../certs`, `JWT_ISSUER_URI=https://idp.example.com/realms/alpenflight`). Receiving story: S-041 reverse-proxy + S-151 prod Keycloak.
- Production IdP TBD (ADR 0007 open item). Env-driven config so swap is config-only.
- Audience validation deferred until prod IdP selection (ADR 0007 open item). Once the prod IdP is chosen, the design choice is "code-side validator + DB-resolved authorization" vs "issuer-emitted `aud`" — keep IdP-portable.

## Test plan

### Coverage contract

S-020 owns: 401-on-unauth, 200-on-valid-token, 401-on-expired/invalid-token, `JwtIssuerValidator` + `JwtTimestampValidator` chain presence, `alg=none` rejection, OpenAPI global `bearerAuth` requirement, mock-auth profile regression.

Defers: role-gate paths (S-026), tenant scoping (S-022), audit emission (S-027), Proffix grant (S-029), browser OIDC (S-021/S-110), live-Keycloak end-to-end (S-029/S-110).

### Pyramid

- **Unit:** 1 — `ClubAwareJwtAuthenticationConverter` mapping (already implicitly exercised by mock-auth; promote to explicit unit covering null `realm_access`, empty roles, non-String entries).
- **Slice (`@SpringBootTest` + `MockMvc.with(jwt())`):** extend existing `ClubsAuthorizationTest` — add anonymous → 401 + `WWW-Authenticate` header assertion. Hello-anonymous → 401 (new, after the flip).
- **Full-chain (real `JwtDecoder`, synthesized JWT):** new `SecurityFilterChainIT` in `ch.alpenflight.platform.security`. Cases: valid token → 200; expired token (`exp=-1s`) → 401; `iss` mismatch → 401; `alg=none` → 401.
- **OpenAPI:** extend existing `OpenApiIT` — assert at least one operation (e.g. `GET /api/v1/clubs`) lists `"security":[{"bearerAuth":[]}]`. Existing component-level test (`specContainsBearerAuthScheme`) stays.
- **Mock-auth regression:** existing `ClubsControllerIT` under `@ActiveProfiles("mock-auth")` stays green — CI gate only, no new test.

### Fixture

`JwtTestFixture` (`@TestConfiguration`, ~45 LOC): RSA-2048 keypair at class-load; `mint(claims…)` builder; per-test `@TestConfiguration` registers a `JwtDecoder` backed by that public key. `application-test.yml` pins `issuer-uri: http://test-issuer` matching the fixture's `iss`. Reusable for S-022/S-026/S-029 ITs.

### Risks

- `.with(jwt())` bypasses the `JwtDecoder` entirely — slice ITs do NOT cover validator misconfig. The full-chain `SecurityFilterChainIT` is the only guard; omitting it ships a broken validator chain unnoticed.
- `@SpringBootTest` context-cache: the new `SecurityFilterChainIT` and the existing `ClubsAuthorizationTest` may end up in separate cache buckets if their `@TestConfiguration` differs. Centralize `JwtTestFixture` in a single `@TestConfiguration` annotated `@Import`-friendly.
- `application-test.yml` issuer-uri must match `JwtTestFixture`'s `iss` exactly — otherwise full-chain ITs fail at startup with OIDC discovery timeout.

## Performance plan

(N/A — JWT validation hot path is dominated by Nimbus's built-in JWKS cache; no measurable workload at this stage. Re-audit in S-108 perf baseline.)

## Open design questions

These surfaced specialist disagreement; operator input.

1. **Hello endpoint: flip to `authenticated()` (AC3 literal) or keep `permitAll` (placeholder reading)?**
   - **Solution-architect + security-engineer (flip):** AC3 is explicit; closing the gate IS the story's headline. The S-001 `TODO(S-020)` marker is the contract.
   - **Requirements-engineer (keep):** TODO says "before cutover" not "in S-020"; no S-110 coupling; flipping perturbs `HelloControllerIT` (`addFilters=false` slice survives, but the spec change is noise).
   - **Recommendation:** flip — the proof the gate works is the cheapest behavior to assert. Refinement reflects flip.

2. **Audience validation — resolved 2026-05-18 to DEFER.**
   - Operator: prod IdP could be Google / Ory / Auth0 instead of Keycloak. Hardwiring a Keycloak-specific audience mapper (the boyscout fix the refinement proposed) drifts from ADR 0007's vendor-portability anchor. User/tenant mapping goes through DB lookup (S-022), not vendor-specific claim shapes.
   - **S-020 validator chain = `JwtTimestampValidator` + `JwtIssuerValidator`.** No `JwtAudienceValidator`. The issuer validator already attests "this token came from our IdP" — sufficient for the story's gate. Realm export untouched.

<!-- modernize-refine: end -->
