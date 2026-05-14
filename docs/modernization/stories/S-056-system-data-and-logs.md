---
id: S-056
title: System data + system-logs view
epic: E-06
status: todo
depends_on: [S-027]
acceptance:
  - System-admin-only `SystemData` (key/value config) entity ported with edit UI.
  - System-logs view (legacy `flsweb/src/system/logs/`) ported and reads from the new `audit_event` table (S-027), filterable by tenant, actor, event type, time range.
  - Spec `19-audit-logs.spec.ts` passes.
estimate: M
adr_refs: [0007]
parity_test: tests/system/19-audit-logs.spec.ts
---

## Context
Wraps up the master-data CRUD epic. The logs view is the user-facing surface for the audit log (S-027) — important for diagnostics.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] SystemData entity + admin UI.
- [ ] Audit-event list endpoint with filters.
- [ ] SPA list screen (paginated, filterable).

## Notes
Authority: SystemData is system-admin-only; audit-event view is club-admin (own tenant) + system-admin (all tenants).
