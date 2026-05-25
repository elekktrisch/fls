---
id: S-167
title: Home/dashboard page — sysadmin variant
epic: E-07
status: todo
depends_on: [S-165]
acceptance:
  - When the authenticated user has the `SYSTEM_ADMINISTRATOR` role, `/start` renders the sysadmin variant instead of the pilot or club-admin variant.
  - Variant covers cross-tenant surfaces (TBD during refine — candidates: total clubs / users / flights, demo-tenant management, recent provisioning activity, system health summary).
  - Sysadmin can navigate into any tenant from the dashboard (entry point for cross-club admin actions).
estimate: M
adr_refs: [0008]
---

## Context
Follow-up to S-165. Consumes the `GET /api/v1/me` endpoint shipped by S-165 for role-based variant routing. Sysadmins operate across all clubs and don't have a "my flights" surface in any meaningful way (per S-165 they see the empty state — confirmed acceptable as a placeholder, but a dedicated cross-tenant dashboard is the real answer).

**Scope is TBD until refine.** Decompose with the operator. Cross-cuts with E-15 (self-service migration) — the sysadmin dashboard is the launch pad for trial-tenant provisioning, demo-mode oversight, and cutover monitoring.

## Open questions for refine

- Which cross-tenant data lands at MVP? Clubs list snapshot, recently provisioned tenants, active demo sessions, system health (deferred to observability stack, S-035)?
- Does the sysadmin variant share UI with E-15 stories (S-138 trial-tenant provisioning, S-147 funnel telemetry)?
- How does tenant-context switching work — picker, dropdown, /clubs page hop?
- Sysadmins typically need cross-club aggregates (total flights this week, cluster of new-club registrations); separate aggregation queries vs. on-the-fly count over each club.
