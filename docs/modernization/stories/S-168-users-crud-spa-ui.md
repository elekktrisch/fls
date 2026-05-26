---
id: S-168
title: Users CRUD — SPA admin UI (invite, edit roles, deactivate)
epic: E-06
status: todo
estimate: M
parity_test: tests/masterdata/27-user-crud.spec.ts
depends_on: [S-052]
integration_base: integration/users-suite
adr_refs: [0007, 0022, 0023]
refined: false
origin: scope-split
origin_story: S-052
---

## Context

Split off from [S-052](implemented/S-052-users-crud.md) when the backend slice landed independently. S-052 shipped the User aggregate, Users REST API (`GET /api/v1/users`, `GET/POST/PUT/DELETE /{id}`, `POST /{id}/resend-invite`), `RoleAssignmentPolicy`, the Keycloak admin-client integration, JIT-on-first-login projection, and the V2/V8/realm-export structural cleanup. This story is the SPA admin UI on top.

## Acceptance criteria

- Users list page under `/masterdata/users` for CLUB_ADMINISTRATOR. Columns: friendlyName, username, roles (chips), invitePending badge, active flag.
- Invite modal: `username`, `friendlyName`, `notificationEmail`, `languageId`, optional `personId` (Person picker reusing the S-051 lookup), role checkboxes. POSTs `/api/v1/users`.
- User-edit page: shows live roles from the backend (which already reads `role-mappings/realm` from KC); role checkboxes filtered to AlpenFlight realm roles only (built-ins + `proffix-sync` hidden); save diffs role list and PUTs.
- Soft-deactivate action with confirm; refused (409 surfaced) for self-delete and last-CLUB_ADMINISTRATOR.
- "Resend invite" action visible when `invitePending=true`; POSTs `/api/v1/users/{id}/resend-invite`.
- New-stack spec `alpenflight/web/e2e/tests/users/users-invite.spec.ts` per S-052's Test plan: invite → row appears + KC users?email= returns 1 + Mailpit invite + requiredActions=UPDATE_PASSWORD; role round-trip; deactivate.
- Legacy `e2e/tests/masterdata/27-user-crud.spec.ts` carve-out documented in `parity_excluded:` per S-052's Test plan §Parity strategy — route shape, auth model, form shape all greenfield.

## Notes

- TS client regen from the backend OpenAPI snapshot.
- Reuse the S-165 / S-167 page-layout primitives.
- Cutover gate (from S-052): zero-delta on (a) row-appears-after-invite, (b) role-change-visible-on-reload. Documented delta on everything else.
