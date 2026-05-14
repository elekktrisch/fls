---
id: S-027
title: Audit-log infrastructure (every mutating endpoint emits an event)
epic: E-03
status: todo
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
