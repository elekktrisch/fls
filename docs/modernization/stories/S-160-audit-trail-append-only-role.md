---
id: S-160
title: Split migration + app DB roles; grant audit table INSERT,SELECT-only to the app role
epic: E-03
status: todo
estimate: S
depends_on: [S-027]
origin: rework-meta
origin_story: S-027
kind: deferred-hardening
adr_refs: [0022]
parity_test: none
refined: false
---

## Context

S-027 ships `mutation_audit_event` as a regular tenant-scoped table — the
app role has full CRUD against it. Threat-row (d) in S-027's security plan
("App credentials tamper audit history") is mitigated **structurally** by
splitting the DB roles: a `flyway_migrator` role retains DDL + DML against
all tables, the application role gets only `INSERT, SELECT` on
`mutation_audit_event`, no `UPDATE` / `DELETE`. Defends the audit perimeter
against app-credential compromise.

The carve-out is intentionally deferred from S-027 because the current
ops topology runs migrations + app on the same role, and revoking DELETE
from that role inside a Flyway migration would lock Flyway itself out of
future maintenance migrations. The split needs an ops-side change first.

## Acceptance criteria

- Two distinct DB roles exist: `alpenflight_migrator` (DDL + DML, used
  exclusively by Flyway) and `alpenflight_app` (DML, used by the running
  application).
- The Flyway role retains UPDATE / DELETE on `mutation_audit_event`; the
  app role is granted only INSERT, SELECT.
- Compose, Helm / k8s manifests, and CI use the migrator role for
  `flywayMigrate` and the app role for the running service.
- An IT verifies an UPDATE statement run as the app role fails with
  `permission denied`. (Skipped on Testcontainers if test infrastructure
  doesn't yet split roles — fail-loud TODO referencing this story.)
- S-027's security-plan threat-row (d) — already pointing at this story —
  no longer carries the "deferred" qualifier in any prose.

## Notes

S-027's V9 header comment cites this story as the deferral target. Keep
the reference current when this lands (or rename the new migration to
match V<n> at landing time).
