---
id: S-027
title: Audit-log infrastructure (every mutating endpoint emits an event)
epic: E-03
status: in_progress
started_at: 2026-05-23
depends_on: [S-020, S-022]
acceptance:
  - An `audit_event` table (in V1__baseline — add via new V*__ migration if baseline already shipped) captures: `id`, `timestamp`, `actor_user_id` (nullable for anonymous), `actor_keycloak_sub`, `tenant_club_id`, `event_type`, `target_entity_type`, `target_entity_id`, `request_id`, `before_state` (jsonb, nullable), `after_state` (jsonb).
  - A Spring AOP advice or a request-mapping interceptor emits one event for every successful mutating endpoint (POST/PUT/PATCH/DELETE under `/api/v1/**`).
  - Failed mutations (4xx, 5xx) also emit events with a `failed` flag.
  - PII fields are redacted per a configurable list before serialization to `before_state`/`after_state`.
  - `audit_event` is queryable from the admin UI (S-056).
estimate: L
adr_refs: [0007]
parity_test: tests/audit/audit-log-coverage.spec.ts
refined: true
refined_at: 2026-05-22
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
context7_last_checked: 2026-05-22
github_issue: 104
github_pr: 101
---

## Context
Covers O4 + C12. Legacy audit log is partial (`AuditLogService` covers some entities); new system aims for 100%.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Schema migration for `audit_event`.
- [ ] AOP advice or interceptor that fires on controller method completion.
- [ ] before/after capture via JPA `@PostLoad` + dirty-checking or via @EntityListeners.
- [ ] PII-redaction config.
- [ ] Test that proves every mutating endpoint produces an event (parameterize by the OpenAPI spec's mutating operations).
- [ ] Admin UI surfacing in S-056.

## Notes
L because it touches every controller indirectly. Split tasks: (1) schema + emit infra, (2) before/after diff, (3) PII redaction, (4) coverage test, (5) admin UI hook.

<!-- modernize-refine: start -->

## Design notes

### Mechanism — service-layer publisher + transactional listener (not AOP-only)
Mutating service methods call `auditTrail.record(action, entity, beforeSnap, afterSnap)`, which publishes a `MutationAuditRequested` event. An `@TransactionalEventListener(phase = AFTER_COMMIT)` listener writes the row in **`REQUIRES_NEW`** so success rows ride a separate transaction. A `RequestAuditFilter` after Spring Security emits a synthetic `failed=true` row when the response is non-2xx and no event was already recorded. AOP-on-controllers alone was rejected because scheduled jobs (S-081), OGN ingestion (S-029), and the cutover importer mutate without a controller and would silently bypass. JPA `@EntityListeners` rejected — fires at persistence boundary with no HTTP context.

**Guardrail:** the S-024 leakage harness gets a new ArchUnit rule asserting every `@RestController` POST/PUT/PATCH/DELETE method either calls `auditTrail.record` (transitively) OR carries `@AuditedBy("<serviceBean>")`. Caught at CI, not at runtime.

**Envers rejected** despite working in Hibernate 7. Per-entity `<entity>_AUD` tables are incompatible with the AC's single-table jsonb shape and would force S-056 to join across N audit tables.

### Naming (avoids Actuator collision)
Spring Boot Actuator owns `org.springframework.boot.actuate.audit.AuditEvent` + `AuditEventRepository` (auth events). Pin: **`MutationAuditEvent`** + **`MutationAuditEventRepository`** + **`mutation_audit_event`** table + **`ch.alpenflight.server.audit`** package. AC's wording `audit_event` is overridden by this convention.

### Schema additions promoted from AC
AC's column list is incomplete. Promote: `failed BOOLEAN NOT NULL DEFAULT FALSE`, `system_actor BOOLEAN NOT NULL DEFAULT FALSE`, `http_status SMALLINT NULL`, `failure_reason TEXT NULL` (HTTP status + exception class only; **no message body** — may carry PII). `after_state` is also nullable (DELETE has no after). Two columns for the type: `action ENUM(CREATE, UPDATE, DELETE, STATE_TRANSITION, BULK_IMPORT)` + `target_entity_type` (open string, e.g. `Location`). S-056 filters become two independent dropdowns instead of a 50-value combined list.

### Tenant + actor semantics
- `tenant_club_id` is the operating tenant of the row being written (**per-row tenancy**), not the actor's home tenant. System-actor writes (scheduled jobs, OGN, importer) iterate per affected tenant. NULL reserved for true cross-tenant system events (tenant-creation itself) and uses `NO_TENANT` sentinel; readable only via S-023's `UnscopedTenantContext`.
- `@TenantId` annotation on `tenantClubId` — S-024's sweep auto-covers; the row participates in the standard filter.
- `system_actor=true` distinguishes "user X acted in tenant T" from "scheduled job acted on tenant T's data" — S-056 needs the column to render the actor field correctly.
- `actor_keycloak_sub` is the immutable forensic key (always populated when JWT present).
- `actor_user_id` is a nullable FK to `user` with **`ON DELETE SET NULL`** so GDPR/FADP right-to-erasure can delete the user row without orphaning audit history. Erasure scrubs PII columns + nulls the FK; never deletes the audit row itself.

### Before/after capture
Full snapshot on both sides for UPDATE — sparse "only-changed-fields" hides cascaded changes (state-machine transitions, derived fields). CREATE writes `after` only; DELETE writes `before` only. Diff computation is S-056's job (cheap with jsonb).

### `request_id` plumbing
MDC key `requestId`. S-031 (structured-JSON-logging) is the canonical correlation-ID owner; if S-031 hasn't shipped yet, **S-027 ships the minimal `RequestIdFilter`** (UUID v7 per request, populates MDC, echoes `X-Request-Id` header). Non-HTTP origins (jobs, ingestion) set `requestId = job:<jobName>:<runId>` in MDC at job start.

### PII redaction
**Default-deny serialization.** The serializer walks the entity reflectively and emits only fields explicitly marked safe. Two layers:
1. Field-level `@AuditRedact` annotation (drift-resistant — sits next to the field) for per-field overrides.
2. Central `audit-redaction.yml` for declarative bulk rules (e.g. "all `ch.alpenflight.persons.*` Person fields except `id`").

Redacted output: literal `"[redacted]"` sentinel — not the field omitted (absence is unambiguous), not a SHA-256 hash (forensic-correlation-via-hash is a future hardening; v1 is conservative). Person fields (`email`, `licence_number`, `birthday`, `medical_class`, `address*`, `phone*`) are deny-listed. **Drift control:** ArchUnit test asserts every `@Entity` in a tenant-scoped package has been visited by a `RedactionPolicyTest` once — new entity without a policy test → CI fails. Build-time reflection also asserts every entity field is either on the allow-list or deny-list — unknown fields fail the build.

### Append-only via DB role (ADR 0022 §2 carve-out)
Flyway grants the application DB role `INSERT, SELECT` on `mutation_audit_event` (no `UPDATE`, no `DELETE`); separate migration role retains DDL. Defends the audit perimeter against app-credential compromise. **ADR 0022 directive 2 deviation, justified:** this is defending the audit perimeter, not encoding domain logic. No triggers, no CHECK constraints on the action enum (lives in the Java `@Enumerated(STRING)` mapping), no generated columns.

### Cross-story contracts
- **Consumes from S-020:** the request boundary (Spring Security filter chain surface) for `RequestAuditFilter` placement; `Authentication{Success,Failure}Event`s remain Actuator-side (auth events NOT duplicated into `mutation_audit_event`).
- **Consumes from S-022:** `TenantContextCarrier.current()` → `tenantClubId`; `UserPrincipalLookup(sub)` → `actorUserId`.
- **Produces for S-056:** `MutationAuditEventRepository` + `GET /api/v1/admin/audit-events` filterable list. Club-admin scope works day one via `@TenantId`; system-admin cross-tenant scope is `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`-gated and **blocked on S-023's `UnscopedTenantContext`** (returns 403 until S-023 lands).
- **Produces for S-024:** the new `MutationAuditEvent` entity carries `@TenantId`; sweep auto-covers. The redaction-policy ArchUnit rule folds into S-024's harness.
- **Produces for every future controller:** convention is "call `auditTrail.record(...)` in the service method, OR annotate the controller `@AuditedBy(<serviceBean>)`; the synthetic-failure filter handles non-2xx automatically." No per-story migration edit.

### ADR 0022 directive 2 check
Schema is structural only: PK, FKs (`actor_user_id → user.id ON DELETE SET NULL`, `tenant_club_id → club.id`), NOT NULL on structural columns, jsonb columns, indexes (per Performance plan). **Two carve-outs called out for rationale:** (1) DB role grants restrict the app to `INSERT, SELECT` — defending the audit perimeter, not encoding business logic; (2) the `action` enum values are pinned in Java, **not** as a Postgres CHECK constraint or DB enum.

## Edge cases & hidden requirements

### Non-HTTP mutations
AC's "POST/PUT/PATCH/DELETE under `/api/v1/**`" misses scheduled jobs (S-081), OGN ingestion (S-029), and cutover importer writes. The chosen service-layer publisher mechanism covers them; AC's literal HTTP-boundary wording is implicitly overridden by the design.

### Auth events stay in Actuator
`/oauth2/token`, login/logout/refresh, 401/403 — Spring Boot Actuator's `AuditEventRepository` covers them. S-020 already publishes `Authentication{Success,Failure}Event`s into Actuator. **Do not duplicate** into `mutation_audit_event`; divergence risk + the surfaces serve different forensic queries. S-056's runbook documents both surfaces.

### GDPR erasure vs. forensic trail
`actor_user_id` FK with `ON DELETE SET NULL`; `actor_keycloak_sub` retained as opaque identifier. PII inside `before_state`/`after_state` jsonb is the harder problem — scrubbed by a **separate erasure job** owned by the future FADP/DSAR story. S-027 only commits to the `SET NULL` + `[redacted]` defaults; no in-band scrubber on Person delete.

### S-056 cross-tenant read forward-dep
`mutation_audit_event` is `@TenantId`-bearing → club-admin view works day one via normal filter. System-admin cross-tenant view requires **S-023 `UnscopedTenantContext`** (Phase G, deferred). S-027's endpoint ships with the `@PreAuthorize` gate; system-admin path returns 403 until S-023 lands. **Forward dependency made explicit.**

### Payload ceiling + bulk-write granularity
- Hard cap `before_state` / `after_state` at 64 KB serialized; throw on overflow (caller over-captured).
- **Bulk operations** (cutover importer, BULK_IMPORT action): one event per HTTP request with summary `after_state = { count: N, firstId, lastId }` — NOT per row. Per-aggregate granularity stays the rule for every other action; the summary form is `BULK_IMPORT`-only.

### Logs never carry the jsonb
Application logs reference `mutation_audit_event.id` only. A Logback `TurboFilter` rejects any logging payload containing the redacted jsonb. Otherwise PII bleeds from the audit table into log files.

### Retention policy
**7 years, all-online.** Aligns with Swiss commercial-record default. Volume estimate (~2 GB at 5 years — see Performance plan §Storage) fits a single Postgres instance with no partitioning or cold-storage infra in v1. Promotion to partitioning + archive is a future story when volume actually demands it.

## Security plan

### Threat model
| # | Vector | Severity | Caught by |
|---|---|---|---|
| (a) | Successful mutation missed by emitter. | **Crit** | ArchUnit guard + OpenAPI-driven coverage test (CI). |
| (b) | 5xx mid-transaction loses the audit row. | **Crit** | `REQUIRES_NEW` separate transaction; `failed_500_inside_tx_rolled_back_still_emits_event` test pins it. |
| (c) | Wrong-tenant audit row. | High | `@TenantId` on `tenantClubId`; S-024 sweep auto-covers. |
| (d) | App credentials tamper audit history. | High | DB role granted only `INSERT, SELECT` (no `UPDATE`, `DELETE`). ADR 0022 §2 carve-out justified. |
| (e) | PII bleeds into jsonb via new entity field nobody redacted. | **Crit** | Default-deny serializer + ArchUnit redaction-policy test. Build-time reflection check: unknown field → fail. |
| (f) | Audit row referencing a GDPR-erased user dangles. | Med | `actor_user_id ON DELETE SET NULL`; PII scrub job (separate future story) handles jsonb. |
| (g) | Auth-event/mutation-event confusion in incident response. | Low | Operator runbook (S-056) documents both Actuator + `mutation_audit_event` surfaces. |

### Authorization
S-056's list endpoint enforces club-admin → own tenant only (`@TenantId` auto); system-admin → all tenants (requires S-023, returns 403 until landed).

### Input validation
Audit rows are server-generated. The redaction config (`audit-redaction.yml`) is operator-managed — build-time check via reflection asserts every `@Entity` field is in the allow-list or deny-list; unknown → fail.

### PII handling
- **Default-deny serialization** — only allow-listed fields appear in jsonb.
- **At-rest encryption** of jsonb columns inherits the same envelope as the Person columns (vision NFR Security-at-rest); not the sole defense.
- **Logs never carry the jsonb** — Logback `TurboFilter` rejects audit payloads.

### OWASP applicability
- **A09 Logging & Monitoring** — primary; S-027 IS the mitigation.
- **A04 Insecure Design** — "failed mutations emit events" requires `REQUIRES_NEW`, not try/catch.
- **A02 Cryptographic Failures** — jsonb-at-rest encryption pinned alongside Person columns.
- **A01 Broken Access Control** — S-056 read; pre-gated until S-023.

### Story-specific pins
- Type names: `MutationAuditEvent` / `MutationAuditEventRepository` / `MutationAuditAdvice` — Actuator-collision-safe.
- CODEOWNERS: `audit-redaction.yml` + the `V*__audit_event*.sql` migration → security-review path.
- Tamper-detection HMAC chain — **out of scope v1**; future hardening.

## Test plan

### Layers
| Layer | Count | Strategy |
|---|---|---|
| Unit | ~6 | `PiiRedactor` matrix; `AuditEventBuilder` shapes; serialization edge cases; `ActorResolver`; `RequestIdMdcExtractor`; redaction policy reflection. |
| Integration | ~13 | `@SpringBootTest` extending `PostgresIntegrationTest`; real flyway, real `mutation_audit_event` table; MockMvc; assertions read rows back via `JdbcTemplate` (bypasses `@TenantId` for cross-tenant assertions). |
| OpenAPI coverage tripwire | 1 | The load-bearing CI gate. |
| E2E | 0 | Admin UI is S-056. |
| Parity | N/A | Legacy `AuditLogService` is the failure mode being fixed; not an oracle. |

### Specific named test cases
Integration (against `ClubsController` + `LocationsController`):
- `successful_post_emits_event_with_after_state`
- `successful_put_emits_event_with_before_and_after_state`
- `successful_delete_emits_event_with_before_state_after_state_null`
- `failed_400_validation_emits_failed_flag_no_after_state`
- `failed_500_inside_tx_rolled_back_still_emits_event` — pins the `REQUIRES_NEW` design.
- `audit_event_tenant_club_id_matches_runAs_A`
- `scheduled_job_under_runUnscoped_carries_per_row_tenancy` — assert no `NO_TENANT` sentinel on rows for real tenants.
- `request_id_propagated_from_MDC`
- `actor_user_id_resolves_for_federated_jwt_sub`
- `actor_user_id_null_for_anonymous_public_flow`
- `auth_failure_does_not_emit_mutation_event` — Actuator owns that surface.
- `pii_redaction_applied_to_configured_fields` (parameterized).
- `non_configured_field_appears_verbatim`.
- `@Disabled("S-023") cross_tenant_admin_read_blocked` — placeholder.

### OpenAPI-driven coverage tripwire
Single `@SpringBootTest` parses `alpenflight/server/openapi-snapshot.json` (S-003 pinned), filters operations to `POST|PUT|PATCH|DELETE` under `/api/v1/**`, reflection-resolves the handler method via `RequestMappingHandlerMapping#getHandlerMethods`, invokes via MockMvc with a minimal valid body synthesized from the operation's request-schema (`swagger-parser` + null-only stub for non-required fields), asserts exactly one `mutation_audit_event` row landed. The spec is the oracle — no developer-maintained registry. New mutating endpoint with no body fixture → test fails loud, forcing fixture registration in a `src/test/resources/audit-fixtures/<operationId>.json` map.

### Fixtures + isolation
Reuse `TenantTestContext.runAs`, `@WithTenant`, `SharedPostgresContainer.INSTANCE`, S-020's `JwtTestTokens.forSub(...)`. New: `AuditEventAssertions.assertSingleEvent(filter)` reads via `JdbcTemplate` (bypasses `@TenantId` filter for cross-tenant assertions). **The audit-listener commits in a separate transaction** — `@Transactional` rollback on tests can't clean it up. Override `PostgresIntegrationTest#cleanup` to truncate `mutation_audit_event` per test method.

### Risks
- Schema-stub synthesis brittle on `oneOf`/`allOf` — fail-loud + per-operationId override map.
- PII-redaction config drift between yaml and tests — both sides load the same `application-test.yml` redaction list.

## Performance plan

### Hot paths + budgets
- **Write:** ≤ 5 ms p95 added per mutating request (microbench via JMH).
- **Read (S-056):** p95 < 100 ms at 100K rows/tenant; pagination ≤ 200/page.

### Required indexes (all `tenant_club_id`-prefixed per S-011 convention)
- `(tenant_club_id, timestamp DESC)` — default S-056 list.
- `(tenant_club_id, target_entity_type, timestamp DESC)` — entity-type filter.
- `(tenant_club_id, actor_user_id, timestamp DESC)` — actor filter.
- `(request_id)` — cross-row correlation; not tenant-prefixed (globally unique).
- No GIN on jsonb in v1 — no consumer query drills into structure.

### Caching + ceilings
- Cache `JWT sub → user_id` in a 60 s Caffeine cache; invalidate on user deactivation. Avoids per-request SELECT.
- Hard cap `before_state` / `after_state` at 64 KB; throw on overflow.
- Bulk writes: one event per HTTP request, summary `after_state` (BULK_IMPORT action only).

### Storage
- Volume: ~3 KB × 130k rows/year ≈ 400 MB/year. 2 GB after 5 years; single Postgres handles it.
- TOAST handles >2 KB jsonb transparently.
- Tenant partitioning, GIN on jsonb, separate datasource: **all v2 or later.** Not now.

### Perf test plan
JMH on the AOP+listener overhead; k6 load test on S-056 list; `EXPLAIN ANALYZE` on the 3 list-query shapes — assert Index Scan on the composite index, never Seq Scan.

## Resolved design decisions (operator, 2026-05-22)

1. **Bulk-write granularity → per-request summary for `BULK_IMPORT` only.** Every other action stays per-aggregate. Summary `after_state = { count, firstId, lastId }`. Trades per-row forensic detail on imports for bounded write-amplification; per-aggregate stays the rule everywhere it matters.
2. **Retention → 7 years, all-online.** Swiss commercial-record default. ~2 GB at 5 years (Performance plan §Storage) fits a single Postgres instance — no partitioning, archiving, or cold-storage machinery in v1. Promotion is a future story when volume warrants it.
3. **Redacted-value shape → literal `"[redacted]"` sentinel.** Conservative v1. Zero forensic leakage, no dictionary-attack surface on low-entropy fields. Promote to hashed-hint (SHA-256 + last-4) only if "did the same person edit this twice" queries become a documented operator need.

<!-- modernize-refine: end -->
