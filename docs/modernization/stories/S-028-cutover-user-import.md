---
id: S-028
title: Cutover user export-and-import script + reset-email queue
epic: E-03
status: todo
depends_on: [S-019, S-012, S-026]
acceptance:
  - A script reads `User` + `Person` + email from the legacy SQL Server DB and creates corresponding users in the production Keycloak realm with the "must reset password" required action.
  - Each user is created in the right realm, linked to the right club, granted the right roles (mapped from legacy `Role`).
  - Keycloak's reset-password email is *queued but not sent* during dry-run; sent at cutover-go-time (controlled by a flag).
  - The script is idempotent: re-running with the same input produces no duplicate users.
  - A dry-run report lists each user with: email, club, roles, action ("create" / "skip - already exists").
estimate: L
adr_refs: [0007]
parity_test: none
---

## Context
C14 forces a password reset for every user at cutover. This is the script that makes it happen.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Read user list from legacy DB.
- [ ] For each, call Keycloak admin API: create user, set required actions = `UPDATE_PASSWORD`, set realm role mappings.
- [ ] Map legacy roles → Keycloak roles (per S-026).
- [ ] Set `user.keycloak_sub` in the new Postgres DB after Keycloak returns the user ID (the FK from `user` to Keycloak).
- [ ] Idempotency via Keycloak `searchByEmail` first.
- [ ] Dry-run mode that prints would-do actions without applying.
- [ ] Send-emails toggle so emails fire at the cutover instant, not before.

## Notes
S-052 (Users CRUD on the new server) lands the `user.keycloak_sub` column. This story uses it.
