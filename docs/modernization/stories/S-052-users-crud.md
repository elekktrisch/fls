---
id: S-052
title: Users CRUD + role assignment
epic: E-06
status: todo
depends_on: [S-051, S-026, S-019, S-020]
acceptance:
  - `User` entity ported, with `keycloak_sub` column linking to the IdP user.
  - Roles are assigned via Keycloak (admin UI or API); the FLS `User` row is created in lockstep when a Keycloak user is added (event handler or batch sync).
  - Spec `27-user-crud.spec.ts` passes.
  - A user-edit screen on the SPA shows roles read from Keycloak; saves trigger Keycloak role updates.
estimate: L
adr_refs: [0007, 0008]
parity_test: tests/masterdata/27-user-crud.spec.ts
---

## Context
Tricky because authority lives in Keycloak now, but the FLS app still needs a User row (for `club_id` scoping, `PersonId` linkage, audit-log actor lookup).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `User` entity with `club_id`, `person_id` (nullable — for logins not tied to a Person), `keycloak_sub` (UUID).
- [ ] Sync mechanism: on first login (or via webhook from Keycloak if configured), create/update the FLS User row.
- [ ] Role-management endpoints call Keycloak admin API (Spring Security can carry an admin client).
- [ ] User-edit screen.
- [ ] Spec verification.

## Notes
S-028 (cutover user import) writes the initial `user.keycloak_sub` values; this story handles the ongoing case (new users post-cutover).

L because the Keycloak-FLS-DB sync is non-trivial. Webhook vs. on-login lazy sync vs. periodic full sync — pick one. Lazy on-login is simplest; document the trade-offs.
