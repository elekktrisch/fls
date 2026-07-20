---
id: S-027
title: Audit-log infrastructure (every mutating endpoint emits an event)
epic: E-03
status: done
started_at: 2026-05-23
done_at: 2026-05-23
merged: true
merged_at: 2026-05-23
depends_on: [S-020, S-022]
acceptance:
  - The `mutation_audit_event` table captures `id`, `occurred_at`, `actor_user_id` (nullable), `actor_keycloak_sub` (TEXT — raw JWT subject), `tenant_club_id` (per-row operating tenant, nullable for system events), `action`, `target_entity_type`, `target_entity_id`, `request_id`, `before_state` / `after_state` (jsonb, nullable), `failed`, `system_actor`, `http_status`, `failure_reason`.
  - Every successful mutating service-layer call emits one event via `AuditTrail.record(...)`; `ControllerAuditCoverageTest` enforces that every mutating `@RestController` method reaches the call (or carries `@AuditedBy`).
  - Failed mutations (4xx / 5xx) emit a synthetic `failed=true` row via `RequestAuditFilter` — except 401 / 403, which belong to Actuator's auth-event surface (S-020).
  - PII redaction is default-deny — only fields explicitly allow-listed in `application.yml::audit.redaction` (or covered by `@AuditRedact`) land verbatim in jsonb; the walk recurses into nested aggregates.
  - `mutation_audit_event` is queryable via `GET /api/v1/admin/audit-events` (paginated; per-tenant scoped via Hibernate `@TenantId`).
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

Covers O4 + C12. Legacy `AuditLogService` covers some entities; the new
trail is uniformly enforced via the `ControllerAuditCoverageTest`
ArchUnit rule.

## Load-bearing decisions

- **Service-layer publisher + transactional listener.** Mutating service
  methods call `auditTrail.record(...)`; a `@TransactionalEventListener
  (AFTER_COMMIT)` writes the row in `REQUIRES_NEW`. AOP-on-controllers
  was rejected so scheduled jobs (S-081), OGN ingestion (S-029), and the
  cutover importer (non-HTTP origins) participate via the same call.
- **Synthetic-failure path covers rollbacks.** `RequestAuditFilter`
  emits a `failed=true` row when a mutating `/api/v1/**` request returns
  non-2xx and the AFTER_COMMIT-only success row didn't land. Auth events
  (401 / 403) deliberately skipped — Actuator's `AuthenticationEvent`s
  own that surface (S-020). Cross-tenant admin paths bind the failure
  row to the path-variable target via `RequestTenantHint` +
  `AuditTargetTenantInterceptor` (`Tenants.runAs` unwinds before the
  filter's finally block, so the hint is captured pre-validation).
- **Naming carve-out for Actuator collision.** `MutationAuditEvent` /
  `MutationAuditEventRepository` / `mutation_audit_event` /
  `ch.alpenflight.audit` — pinned to avoid Spring Boot Actuator's own
  `AuditEvent` / `AuditEventRepository` types.
- **`actor_keycloak_sub` is TEXT, not UUID.** Federated IdPs (Google
  numeric, Auth0 custom) hand non-UUID subjects. The audit trail records
  whatever the JWT carried; `system_actor` flips based on auth-token
  type (`JwtAuthenticationToken`), never sub shape.
- **Per-row tenancy via `@TenantId` on `tenant_club_id`.** Operating
  tenant, not actor home tenant. NULL only for true cross-tenant system
  events (tenant creation itself); reads via S-023 `UnscopedTenantContext`.
- **GDPR/FADP-safe FK action.** `actor_user_id ON DELETE SET NULL` lets
  right-to-erasure delete the `user` row without orphaning audit history.
  PII inside the jsonb snapshots is scrubbed by a separate erasure job
  (future story).
- **PII redaction is default-deny + recursive.** The serializer walks
  the snapshot reflectively, emits only allow-listed fields, and
  recurses into nested aggregates under the runtime type's simple-name
  policy key. `@AuditRedact` overrides the policy for known-PII fields
  even if accidentally allow-listed. `AuditRedactionCoverageTest`
  ArchUnit guard catches `@Entity` fields lacking an explicit decision.
- **Append-only — enforced structurally (S-160).** The port exposes only
  `append`; no `UPDATE` / `DELETE` surfaces exist. S-160 split the DB roles
  (migration V54): the running app boots as `alpenflight_app`, granted only
  `INSERT, SELECT` on `t_mutation_audit_event` — its `UPDATE` / `DELETE`
  privilege is revoked, so app-credential compromise cannot tamper or erase
  audit history. Flyway migrates as the separate `alpenflight` migrator role,
  which retains full CRUD for lawful maintenance.
- **Logs never carry the jsonb.** `AuditPayloadTurboFilter` denies any
  log line containing the redactor sentinel or the explicit
  `AUDIT_PAYLOAD_MARKER` — defense-in-depth against PII bleeding from
  the audit table into log files.

## Cross-story contracts

- **Consumes S-020:** `JwtAuthenticationToken` from the security filter
  chain; auth events stay in Actuator.
- **Consumes S-022:** `TenantContextCarrier` / `UserPrincipalLookup`.
- **Produces for S-056:** `GET /api/v1/admin/audit-events`. Club-admin
  scope works day-one via `@TenantId`. SYSADMIN cross-tenant view
  returns 403 until S-023 lands.
- **Produces for S-024:** `MutationAuditEvent` is `@TenantId`-bearing;
  the leakage harness sweeps it via `TenantScopedEntityCatalog`'s
  generalised tenant-column resolver (audit uses `tenant_club_id`; other
  entities use `club_id`).
- **Produces for every future controller:** "call `auditTrail.record(...)`
  in the service method OR annotate `@AuditedBy(<serviceBean>)`; the
  filter handles synthetic failure." Enforced by ArchUnit.

## Follow-ups

- **S-023 (existing)** — cross-tenant audit read for SYSADMIN; until it
  lands, `cross_tenant_admin_read_blocked` is `@Disabled` in the IT.
- Future: tamper-detection HMAC chain, jsonb-at-rest encryption (out of
  scope v1).

## Resolved design decisions (operator, 2026-05-22)

1. **Bulk-write granularity** — per-request summary for `BULK_IMPORT`
   only; everything else stays per-aggregate.
2. **Retention** — 7 years, all-online (Swiss commercial-record default;
   single Postgres fits the volume).
3. **Redacted-value shape** — literal `"[redacted]"` sentinel (no
   forensic leakage; no dictionary-attack surface).
