---
id: S-168
title: Users CRUD — SPA admin UI (invite, edit roles, deactivate)
epic: E-06
status: done
started_at: 2026-05-26
done_at: 2026-05-26
estimate: M
parity_test: alpenflight/web/e2e/tests/users/users-invite.spec.ts
parity_excluded:
  - Legacy `e2e/tests/masterdata/users-crud.spec.ts` — reasons enumerated in S-052 §Parity exclusions (legacy `X-HTTP-Method-Override` envelope, `UserName`/`FriendlyName`/`NotificationEmail` form shape, `CanDeleteRecord` flag, legacy confirmation-token email send-path).
depends_on: [S-052]
integration_base: integration/users-suite
adr_refs: [0007, 0022, 0023]
refined: true
refined_at: 2026-05-26
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer]
github_issue: 136
github_pr: 137
origin: scope-split
origin_story: S-052
---

## Context

Split off from [S-052](implemented/S-052-users-crud.md) when the backend slice landed independently. S-052 shipped the User aggregate, REST API, `RoleAssignmentPolicy`, and Keycloak admin-client integration. This story is the SPA admin UI on top.

## Acceptance criteria

- Users list page under `/masterdata/users` for CLUB_ADMINISTRATOR. Columns: friendlyName, username, roles (chips), invitePending badge, active flag.
- Invite modal: `username`, `friendlyName`, `notificationEmail`, `languageId`, optional `personId` (Person picker reusing the S-051 lookup), role checkboxes. POSTs `/api/v1/users`.
- User-edit page: shows live roles from the backend (which already reads `role-mappings/realm` from KC); role checkboxes filtered to AlpenFlight realm roles only (built-ins + `proffix-sync` hidden); save diffs role list and PUTs.
- Soft-deactivate action with confirm; refused (409 surfaced) for self-delete and last-CLUB_ADMINISTRATOR.
- "Resend invite" action visible when `invitePending=true`; POSTs `/api/v1/users/{id}/resend-invite`.
- New-stack spec `alpenflight/web/e2e/tests/users/users-invite.spec.ts` per S-052's Test plan: invite → row appears + KC users?email= returns 1 + Mailpit invite + requiredActions=UPDATE_PASSWORD; role round-trip; deactivate.
- Legacy `e2e/tests/masterdata/27-user-crud.spec.ts` carve-out documented in `parity_excluded:` per S-052's Test plan §Parity strategy — route shape, auth model, form shape all greenfield.

<!-- modernize-refine: start -->

## Decisions that survive the code

**Route.** Flat `/users` shipped, not the AC's `/masterdata/users` text — no `/masterdata/*` namespace exists and none was introduced. The AC line reads as shorthand for the surface, not the literal path.

**Language.** No `/api/v1/languages` endpoint introduced. FE maps the four canonical Transloco locales (`de`/`fr`/`it`/`en`) → V2-seeded UUIDs in `features/users/language-options.ts`; invite form defaults to the active Transloco locale. Region-tagged variants (`de-CH`, `fr-CH`, `it-CH`, `rm`) deferred.

**Diff-and-PUT preserves out-of-band roles.** Load-bearing: PUT payload = `(currentFromServer \ uiManagedRoles) ∪ checkedBoxes` (`features/users/role-catalog.ts → mergeManagedRoles`). Without this, a CLUB_ADMIN editing a sysadmin's profile silently demotes them — backend's `RoleAssignmentPolicy` only checks the *added* set, not the *removed* set. Regression witness: spec `users: edit preserves SYSTEM_ADMINISTRATOR (out-of-band role) on save` + unit test on `mergeManagedRoles`.

**Person picker = `/persons/lookup` exact-match.** Picker is the only entry point for `personId` on the wire; never `GET /persons?q=` (enumeration risk; S-051 lookup is rate-limited + audited). State + HTTP go through `UsersStore.lookupPerson` per CLAUDE.md §4.

**Route guard.** `clubAdminGuard` composes `authGuard` + `currentClubId !== null` + `isClubAdmin()`. Non-admins land at `/start`, never trigger `GET /api/v1/users`.

**Cutover gate inherited from S-052.** Zero-delta on row-appears-after-invite + role-change-visible-on-reload. Spec exercises both, the latter via `page.reload()` before assertion.

## Open follow-ups

- **AC text drift.** AC line 1 reads `/masterdata/users`; shipped `/users`. AC line 7 cites `27-user-crud.spec.ts` (stale path); actual legacy spec is `users-crud.spec.ts` — corrected in `parity_excluded:` + `parity_test:` frontmatter. AC amendment is a decompose-skill follow-up, not blocking.
- **Username regex source-of-truth.** `features/users/edit/users-edit.page.ts` mirrors `UserDtos.UserInviteRequest.username` `@Pattern` literally. A future drift detector (codegen / lint) could prevent silent divergence.
- **409 detail-string discrimination.** Backend uses one `user-conflict` URN for self-delete, last-admin, and username-taken; FE classifies by detail-string regex (`features/users/users.store.ts`). Distinct backend URNs would remove the English-copy coupling — file with S-052 owner if i18n on the backend lands first.

<!-- modernize-refine: end -->
