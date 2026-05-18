---
id: S-022
title: ClubTenantIdentifierResolver + @TenantId plumbing on first entity
epic: E-03
status: in_progress
started_at: 2026-05-18
github_issue: 66
depends_on: [S-012, S-015, S-020]
acceptance:
  - `ClubTenantIdentifierResolver` (Hibernate `CurrentTenantIdentifierResolver`) reads the authenticated principal's `clubId` claim.
  - Hibernate is configured with `multi_tenancy=DISCRIMINATOR` and the resolver bean.
  - `@TenantId` is applied to a worked example entity (recommend: `Club`-scoped entity like `Location` once S-049 is in flight; for this story, a placeholder entity is fine).
  - A test executing a `findAll()` against the example entity under different tenant contexts returns different result sets.
  - Test fixtures (S-015) successfully default to a known tenant before running queries; without a context, queries throw a clear error (or return empty per chosen policy).
estimate: M
adr_refs: [0008, 0022]
parity_test: none
refined: true
refined_at: 2026-05-18
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
context7_last_checked: 2026-05-18
---

## Context
ADR 0008's core plumbing. Once this lands, *every* tenant-scoped entity added afterwards just needs `@TenantId` on its `club_id` column.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Configure Hibernate properties for discriminator multi-tenancy.
- [ ] Implement `ClubTenantIdentifierResolver` reading from Spring Security context.
- [ ] Apply `@TenantId` to one entity end-to-end as a worked example.
- [ ] Wire a `@WithTenant(clubId)` test helper that sets the security context with a JWT-shaped principal carrying the `clubId` claim.
- [ ] Document the convention in `next/server/docs/multi-tenancy.md`.

## Notes
The first entity to wear `@TenantId` is the worked example — likely `Location` or `Club` itself. The pattern then propagates to E-06+ stories.

<!-- modernize-refine: start -->

## Design notes

### Resolver shape

`ClubTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>`. `UUID` parameter — `club.id` is `uuid`, `@TenantId` accepts UUID, Spring's `BeanContainer` parameterizes Hibernate. Claim is a string in the JWT; convert once via `UUID.fromString` (parse failure → fail-closed). `validateExistingCurrentSessions()` returns `false` (stateless web). Sentinel = **nil UUID** `00000000-0000-0000-0000-000000000000` (`NO_TENANT`); `resolveAnyTenantIdentifier()` returns the same.

**Precedence chain** (first non-empty wins):
1. `TenantTestContext.current()` — S-015 swap-in; test seam only.
2. **Trusted-issuer fast path** — if `JwtAuthenticationToken.getToken().getIssuer()` is in the `alpenflight.auth.trusted-issuers` allowlist (our Keycloak, plus the future prod IdP when it's locked in), trust `clubId` claim directly: parse to UUID, return.
3. **Federated-issuer DB-verify** — if `iss` is NOT in the allowlist (future Google / Auth0 / similar): even when a `clubId` claim is present, resolve through `JdbcTemplate`-backed `UserTenantLookup` (queries `user.club_id` by `keycloak_sub` first per S-012 UNIQUE, then by lowercased `email` only when `email_verified=true` claim and `user.email` row is unique). Multiple matches or unverified email → sentinel.
4. **Claim-absent fallback** (federated users without a `clubId` claim — Google/Auth0 baseline): same `UserTenantLookup` path as (3).
5. Sentinel → fail-closed.

The DB lookup uses `JdbcTemplate`, **not** JPA — `CurrentTenantIdentifierResolver` runs inside Hibernate's session-open path, so opening another JPA session is a reentrancy hazard. `User` is not `@TenantId`-bearing (`user.club_id` is data, the entity itself is cross-tenant per S-012), so unfiltered JDBC is safe. The DB-verify result is memoized per request-scoped `Authentication` so per-query resolver calls don't fan out into N JDBC hits.

**Trusted-issuer allowlist:** `alpenflight.auth.trusted-issuers` is a Spring `@ConfigurationProperties` `List<String>`; default `[${spring.security.oauth2.resourceserver.jwt.issuer-uri}]` so the existing Keycloak issuer is trusted out of the box. Federated issuers join when the prod IdP swap lands (deferred per S-020).

### Insert-poisoning guard

A Hibernate `PreInsertEventListener` (registered via `EventListenerRegistry` at startup) scans entities for `@TenantId` fields; if the discriminator value is the nil-UUID sentinel at insert time, it throws `MissingTenantContextException` and rolls back. Domain code per ADR 0022 directive 2 — not a DB CHECK. Reads return empty because no real row carries the sentinel.

### Hibernate config

`@TenantId` on the entity field is enough to enable discriminator multi-tenancy in Hibernate 7. The `@Bean ClubTenantIdentifierResolver` is auto-discovered via Spring's `BeanContainer` — **no `spring.jpa.properties.hibernate.multi_tenancy` / `tenant_identifier_resolver` properties needed**. AC2's "configured with `multi_tenancy=DISCRIMINATOR`" is satisfied structurally; pin this in CONVENTIONS so a future implementer doesn't add the properties defensively.

### Worked-example entity

Use `MemberState` — an S-012 child of `Club` (`member_state.club_id NOT NULL FK → club.id`, schema already shipped in V2). No new Flyway migration. Stand up the JPA mapping under `ch.alpenflight.clubs.MemberState` (alongside `Club`) with `@TenantId` on `club_id`; add a thin `MemberStateRepository extends JpaRepository<MemberState, UUID>` for AC4's `findAll()`. Two seed rows per test club are enough to prove cross-tenant filtering. The mapping is minimal at S-022 (just enough to demonstrate the resolver); the future story that owns per-club configuration of member statuses will extend it without rework. Picked over `EmailTemplate` / `ExtensionValue` because those have *nullable* `club_id` (SYSTEM_GLOBAL rows) — `@TenantId` against a nullable column is ill-defined.

`Club` itself does NOT carry `@TenantId` — it IS the tenant. Aggregate-internal children of `Club` (`MemberState` here, future `ClubExtension` etc.) carry the discriminator redundantly with their parent; pin the rule in CONVENTIONS.

### UUID v7 generator

`FlsUuidV7Generator` + `@UuidV7` ship here under `ch.alpenflight.platform.id` per S-012's deferred plan. Adds `com.github.f4b6a3:uuid-creator:6.x` to `next/server/build.gradle.kts`. Worked-example entity uses `@UuidV7`. `Club.id` keeps `@GeneratedValue(strategy=UUID)` (v4) — flip to v7 as a boyscout when S-052 lands.

### `@WithTenant` type evolution

S-015's refinement specifies `@WithTenant(long)` + `TenantTestContext` as `ThreadLocal<Long>`. Annotations cannot have `UUID` parameters; bump to `@WithTenant(String)` (UUID-string literal, parsed in `TenantContextExtension`) before S-015 implements. `TenantTestContext` becomes `ThreadLocal<UUID>`; `runAs(UUID, Runnable)` / `runUnscoped(Runnable)` (latter pushes the sentinel). No call sites exist yet — pre-coordinate, no source-incompatibility cost.

### Mock-auth interaction

Resolver under `@Profile("mock-auth")` reads the JWT claim only — DB fallback is **bypassed** (no users seeded under mock-auth; the principal's hardcoded `clubId="019e30c3-2c00-7001-8000-000000000001"` always wins). `UserTenantLookup` is `@Profile("!mock-auth")`; the resolver injects `Optional<UserTenantLookup>` and skips fallback when absent. Mock-auth chain rip-out timing is contested — see Open design questions.

### Virtual-thread / ThreadLocal

Stay on `SecurityContextHolder.MODE_THREADLOCAL` + plain `ThreadLocal<UUID>` in `TenantTestContext`. Tomcat 11 + Spring Boot 4 with `spring.threads.virtual.enabled=true` gives each request one virtual carrier; JEP 491 removed `synchronized` pinning. Spring's `DelegatingSecurityContextRunnable` already propagates across `@Async` / `TaskExecutor` boundaries. Anti-pattern to document: raw `CompletableFuture.runAsync` / `Thread.startVirtualThread` without the delegating wrapper — child sees no principal, resolver returns sentinel, query returns empty. `ScopedValue` (JEP 487 preview) deferred.

### Native SQL bypass

`@TenantId` filters JPA only. `@Query(nativeQuery=true)`, `JdbcTemplate`, raw `DataSource.getConnection()` bypass. No production native queries in the codebase today. Pin in CONVENTIONS; the repo-wide CI sweep is S-024.

### Cross-story contracts

**Consumes:** S-020 (`JwtAuthenticationToken` + `clubId` claim shape); S-015 (`@WithTenant` / `TenantContextExtension` / `TenantTestContext` — type-bumped per above); S-012 (`user.{club_id,keycloak_sub,email}`).

**Produces:**
- `ClubTenantIdentifierResolver` + `NO_TENANT` sentinel — consumed by S-023 (unscoped session API), S-024 (leakage CI), S-025 (public-flow tenant from URL), S-031 (MDC logging), S-066 (OGN ingest), S-081 (scheduled jobs cross-club), S-098/099 (trial-flight / passenger registration).
- `@UuidV7` + `FlsUuidV7Generator` — consumed by every future aggregate-root entity (S-047, S-049, S-050, S-052, S-062a, …).
- `UserTenantLookup` SPI — S-027 (audit-log MDC), S-031 may also consume.
- CONVENTIONS "Multi-tenancy" section (replaces planned `next/server/docs/multi-tenancy.md` per ADR 0022 directive 1 — one section, not a separate file).

### Rip-out + schema-deviation notes

Mock-auth chain (`ch.alpenflight.auth.*`) **stays** through S-026; rip-out lands with role enforcement (preserves the walking-skeleton operator-demo + dev auth-free path until then). S-022 mark-done flips the default `application-dev.yml` profile from `mock-auth` to the real chain but does not delete the mock classes. No schema-level business logic: V2's `member_state` table is PK + FK + structural NOT NULL only; fail-closed semantics live on the Java resolver + `PreInsertEventListener`, not as DB CHECK / trigger / generated column.

## Edge cases & hidden requirements

- **Resolver must tolerate anonymous `SecurityContext`** — `getAuthentication()` is `null` / `AnonymousAuthenticationToken` on permitAll paths (actuator, springdoc, future S-025 public flows). Null-guard before reading principal; return sentinel, never throw.
- **DB fallback runs outside JPA** — `UserTenantLookup` uses `JdbcTemplate`. Avoids reentering Hibernate's session-open path. Lookup result memoized per `Authentication` (request-scoped) so the resolver-called-per-query pattern doesn't fan out queries. Cross-request caching deferred (avoid invalidation problem when a user is moved between clubs).
- **Insert poisoning under sentinel** — Hibernate would otherwise bind nil UUID into `club_id`. Mitigated by a `PreInsertEventListener` that rejects nil-UUID `@TenantId` values (see Design notes — insert-poisoning guard).
- **`Club` is the tenant** — must not wear `@TenantId`. If applied accidentally, `clubs.findAll()` would generate `WHERE club_id = ?` on a table without that column → DDL validation fails fast. Cheap safety; document the rule in CONVENTIONS.
- **`MockAuthenticationFilter` rip-out** — chain stays through S-026 (overrides S-020's pre-S-026 plan). DB-fallback / DB-verify integration tests must run against the real chain (not mock-auth) so the production code paths are actually exercised.
- **`@WithTenant(long)` → `@WithTenant(String)`** — S-015 hasn't been implemented yet. Pre-coordinate the type bump (annotations can't carry `UUID`); store on `TenantTestContext` as `UUID` after parse.
- **`junit-platform.properties` `parallel.enabled=false`** — pinned by S-015 and must stay false through S-022. ThreadLocal correctness under parallel JUnit is unverified; revisit only when ADR 0021's parallel rule is fully honored.
- **`multi-tenancy.md` artifact** — Task line 29 calls for a standalone doc; ADR 0022 directive 1 prefers a section in `next/server/CONVENTIONS.md`. Fold; don't create the standalone file.
- **Cross-tenant FK preservation** — `Flight` references `Person` from a different operating club via `PersonClub` (R1 / ADR 0008 §Context). `@TenantId` only filters root queries; FK-by-ID loads still work cross-tenant. Worked example must not accidentally model the cross-tenant case (keep the placeholder entity single-club-scoped to avoid muddling the demonstration).

## Security plan

### Threat model — S-022-decision rows only

| # | Threat | Sev | S-022 mitigation |
|---|---|---|---|
| T1 | JWT `clubId` claim spoofed or mismapped (e.g. federated IdP misconfiguration) | High | Trusted-issuer allowlist (`alpenflight.auth.trusted-issuers`): claim is trusted directly only when `iss` is in the list (our Keycloak). For any other `iss`, the resolver DB-verifies the claim against `user.club_id` resolved by `sub`/`email`; mismatch → sentinel. |
| T2 | DB fallback / DB-verify → privilege escalation via duplicate email or spoofed `sub` | High | Lookup order: `keycloak_sub` (S-012 UNIQUE) first; email only if `email_verified=true` claim **and** the `user.email` row is unique; multiple matches or unverified email → sentinel. |
| T3 | Sentinel returned and a downstream INSERT writes nil UUID into `club_id` | Med | Hibernate `PreInsertEventListener` rejects inserts where any `@TenantId` discriminator equals nil UUID — domain-level guard per ADR 0022 directive 2. Reads return empty because no real row carries nil. |
| T4 | `resolveAnyTenantIdentifier()` / unscoped seam abused by feature code | Med | S-022 ships the resolver only. The unscoped-session mechanism is S-023; S-022 leaves `resolveAnyTenantIdentifier()` returning the sentinel so it's inert until S-023 explicitly opts a call site in. |
| T5 | Native SQL bypasses `@TenantId` | Med | No native SQL in production code today. Pin convention; repo-wide CI sweep + `@UnscopedNativeQuery` marker is S-024's scope. |
| T6 | `MockAuthenticationFilter` reaches prod | Low | Already gated by `@Profile("mock-auth")`. Chain stays through S-026; rip-out lands with role enforcement. |

### Authorization, validation, PII, audit

Resolver answers *which tenant*, not *which role* — `@PreAuthorize` (S-026) runs independently. JWT-claim UUID parse failure → WARN log with `sub` only, return sentinel. DB fallback logs `sub` + outcome bucket (`hit-sub` / `hit-email` / `miss`); never the email value, the resolved `clubId`, or the JWT. Audit-log infra is S-027; tenant resolution is plumbing, not a domain event.

### Cross-tenant leakage split

S-022 = **structural** defense (Hibernate cannot skip the filter from JPA). S-024 = **CI sweep** that asserts "create-as-A, read-as-B = empty" across every tenant-scoped repository and forbids un-marked native SQL. OWASP A01 (Broken Access Control) — primary; A09 (logging failures) — secondary.

## Test plan

**Coverage S-022 owns** — resolver precedence chain (claim → DB fallback → sentinel), claim-parse failure, anonymous request tolerance, `@TenantId` actually filters `findAll()`, insert under context writes the correct `club_id`, mock-auth bypass of DB fallback, `@WithTenant` swap into real `SecurityContext`.

**Defers** — cross-tenant leakage CI sweep (S-024), per-entity `@TenantId` coverage on future domain entities (S-049, S-050, …), unscoped-session mechanism (S-023), audience-claim validation (deferred indefinitely per S-020).

**Cases by AC** —
- AC1 (resolver reads claim): unit — `Jwt` with `clubId` → returns UUID; without `clubId` but with `sub` → DB lookup; with neither → sentinel; with malformed `clubId` → sentinel + WARN log.
- AC2 (Hibernate discriminator wired): integration — resolver `@Bean` is found via `BeanContainer`; `EntityManagerFactory.getProperties()` reflects multi-tenancy state; no application-property keys for `tenant_identifier_resolver`.
- AC3 (`@TenantId` on worked example): integration — entity-table DDL has `club_id NOT NULL`; mapping resolves cleanly.
- AC4 (`findAll()` returns different sets): integration — seed 2 clubs + 2 entity rows; `@WithTenant(A)` → 1 row; mid-test `runAs(B)` → 0 or different rows; mid-test `runAs(A)` again → 1 row. Insert under `@WithTenant(A)` → raw-JDBC verify `club_id=A`.
- AC5 (no-context behavior): integration — extension absent, no JWT → resolver returns sentinel; `findAll()` returns empty (the chosen policy, since sentinel matches no real row); insert attempt fails fast.
- Federated-user path (no `clubId` claim): integration — seed a `user` row with `keycloak_sub` only; resolver via DB fallback returns its `club_id`.

**Parity:** N/A — `ClubTenantIdentifierResolver` replaces `BaseService.CurrentAuthenticatedFLSUserClubId` wholesale; no legacy oracle.

**Fixtures** — extends `PostgresIntegrationTest` (S-015). 2 `Club` rows + 2 worked-example rows + 1 `user` row (for fallback) per ADR 0021 isolation rules. No `@AfterEach`; rollback handles cleanup; non-rollback adversarial tests pre-clean by stable key.

**Risks** — ThreadLocal bleed under future parallel JUnit (S-015 keeps it disabled); native-SQL escape hatches (S-024 catches); mock-auth precedence vs `@WithTenant` (test-context wins, meta-test asserts); resolver throwing on anonymous request (would 500 actuator probes — null-guard test covers).

## Performance plan

- **Resolver hot path** — claim path is `Map.get` on already-parsed `Jwt` (sub-µs, negligible). DB fallback runs only for federated users with no `clubId` claim; memoize the lookup on the request-scoped `Authentication` so per-query resolver calls don't fan out into N JDBC hits. No cross-request cache yet (invalidation when a user is re-assigned clubs is a complication not worth solving in S-022).
- **`@TenantId` SQL plan** — `WHERE club_id = ?` appended to every tenant-scoped query; S-012's FK indexes on `club_id` cover the single-table case. Forward pattern (out of scope here, pin in CONVENTIONS): future hot queries want `(club_id, …)` composite indexes leading with the discriminator so Postgres can index-only-scan the tenant slice.
- **UUID v7 generation** — `UuidCreator.getTimeOrderedEpoch()` ≈100 ns. Time-ordered keys give B-tree-friendly inserts; matters for S-028's bulk cutover (N clubs × M users), not S-022.
- **Virtual threads / `SecurityContextHolder`** — `MODE_THREADLOCAL` stays default. JEP 491 removes pinning. Anti-pattern to document: raw `CompletableFuture.runAsync` / `Thread.startVirtualThread` without `DelegatingSecurityContextRunnable` propagation.
- **Perf gates** — none ship in S-022. S-015's `transactional_rollback_under_100ms` covers per-test cost.

<!-- modernize-refine: end -->
