---
id: S-168
title: Users CRUD — SPA admin UI (invite, edit roles, deactivate)
epic: E-06
status: todo
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

<!-- modernize-refine: start -->

## Design notes

**Route.** Flat `/users` (matches `/persons`, `/aircraft`, …); the AC's `/masterdata/users` text is shorthand — no `/masterdata/*` namespace exists today and none is being introduced (operator decision 2026-05-26).

**Module layout.** Clone `features/persons/`: `users.routes.ts` (`''` list, `'new'`, `':id/edit'`), `users.store.ts`, `list/users-list.page.ts`, `edit/users-edit.page.ts`. Lazy-loaded from `app.routes.ts`.

**Store.** `UsersStore` with `withEntities<UserListItem>`, mirroring `PersonsStore`. `SaveErrorKind` discriminates `forbidden | conflict-self-delete | conflict-last-admin | conflict-username-taken | role-grant-rejected | validation | other` via `ProblemDetail.type` URN match. Emits `user.created/updated/deleted` on `MUTATION_BUS`; subscribes `session.logout` + `session.tenantSwitch` → `clear()`. Refetch class **masterdata** (CLAUDE.md §4b): `bootstrapPrefetch()` runs `loadAll()` only when `session.isClubAdmin()`.

**TS client regen.** Re-run `pnpm openapi:gen` — `orval.config.ts` already produces `api/generated/users/users.service.ts` from the `Users` tag in `openapi/openapi.json`. No config change.

**Role catalog.** Hard-coded constant `CLUB_ADMIN_GRANTABLE_ROLES = [CLUB_ADMINISTRATOR, FLIGHT_OPERATOR, PILOT, OFFICE_USER, GUEST]` under `features/users/`, with a comment pointing back to `RoleAssignmentPolicy.CLUB_ADMIN_GRANTABLE`. SYSTEM_ADMINISTRATOR + KC built-ins never offered. Critical: PUT-payload roles = `(currentFromServer \ uiManagedRoles) ∪ checkedBoxes` — preserves any out-of-band roles a legacy/sysadmin user carries, so a profile edit never silently demotes them.

**Invite form (`/users/new`).** Person picker reuses `POST /api/v1/persons/lookup` (exact-match, audited per S-051) — **not** `GET /persons?q=` (enumeration). Inline mini-form (email OR firstname+lastname+birthday) → up to 5 match rows → click-to-pin; cleared chip on revert. At least one role required (inline error pre-submit). Username helper text states the regex constraint up-front.

**Language.** No `/api/v1/languages` endpoint. FE maps the four canonical Transloco locales (`de`/`fr`/`it`/`en`) → V2-seeded UUIDs in a constant `LANGUAGE_OPTIONS` under `features/users/`; invite form defaults `languageId` to the active Transloco locale's UUID and exposes a small picker for the four canonical locales. Region-tagged variants (`de-CH`, `fr-CH`, `it-CH`, `rm`) deferred — not surfaced.

**Edit form (`/users/:id/edit`).** Username read-only (identity-binding). If loaded user carries a role outside `CLUB_ADMIN_GRANTABLE_ROLES` (e.g. SYSTEM_ADMINISTRATOR), render a read-only banner ("Additional roles managed outside this screen: …") above the editable group. `invitePending=true` users show a banner with notificationEmail + inline Resend action.

**Deactivate.** `af-dialog` confirm. 409 (`USER_DELETE_REFUSED_SELF` / `USER_DELETE_REFUSED_LAST_ADMIN`) renders inline in the dialog with the `ProblemDetail.detail` copy — no toast. Dialog stays open so the operator sees the constraint.

**Resend invite.** Button on every `invitePending=true` row + the edit-page banner. Disabled during the in-flight POST; transient inline confirmation on 204; no toast infra is introduced for this story.

**List columns.** Via `af-data-table`: primary = `friendlyName` (link to edit); secondary = `username` + role chips (inline Tailwind, no new atom); meta = `invitePending` amber chip + "Inactive" when `!enabled`. Default server order, no sort UI.

**Nav-bar.** Add `{ path: '/users', label: 'Users', icon: 'shield-user' }` to a new `CLUB_ADMIN_SECTIONS` constant in `AppComponent.sections()`; visible iff `isClubAdmin() && !isSystemAdmin()`. (Persons keeps the `users` icon; `shield-user` avoids the collision.)

**Route guard.** New `clubAdminGuard` (sibling to `sysadmin.guard.ts`), composed with `tenantRequiredGuard`. Prevents non-admin flash + the polluting 403 on `GET /api/v1/users`.

**i18n.** English-hardcoded, matching persons/aircraft (S-051 deferred follow-up). No Transloco keys added beyond what the language-locale lookup needs.

## Edge cases & hidden requirements

- **Empty `roles[]` on invite.** Backend's `@NotNull Set<Role>` accepts `{}`. UI requires ≥1 checkbox (inline form-field error) — avoids inviting a no-privilege ghost user.
- **Self-demotion during edit.** A CLUB_ADMIN unchecks their own CLUB_ADMINISTRATOR role: backend's `canEdit` is checked at request entry (still admin); next request 403s. No UI duty — flag as known acceptable; stale chips visible until token refresh (≤15 min per ADR 0007 / S-052 residual).
- **Editing a user who carries SYSTEM_ADMINISTRATOR.** Backend returns it in `roles[]`. Diff-and-PUT must preserve it (see Design § `roles` payload formula); UI shows it as a muted read-only banner, never a checkbox.
- **`personId` not in caller tenant.** Picker is the only entry point and is tenant-scoped via `/persons/lookup`; no free-text UUID input. Backend re-verifies on invite/update path is deferred — picker-only flow is the trust boundary.
- **409 surfaces.** `conflict-self-delete` / `conflict-last-admin` → inline in dialog; `conflict-username-taken` → inline on the `username` field; `role-grant-rejected` → defensive-only toast (catalog hides SYSTEM_ADMINISTRATOR, so it should never fire).
- **Stale list across two admins.** S-052 accepted residual (last-write-wins). UI refetches on `MUTATION_BUS` `user.*` events from this store; cross-admin staleness stays.
- **`notificationEmail` vs OIDC `email`.** Single `notificationEmail` input + helper text "Used for in-app notifications; login email is managed in Keycloak" — preserves the decoupling `UsersController.java:35-39` documents.
- **Username regex.** Helper text shows the constraint up-front (`letters, digits, dot, underscore, dash; 3-256 chars`) — inline `<af-field-errors>` on blur.

## Security plan

- **Route guard.** New `clubAdminGuard` (mirrors `sysadminGuard`). In-page `@if (session.isClubAdmin())` alone is insufficient — a non-admin must never trigger the `GET /api/v1/users` 403. Backend `@PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")` is authoritative; the guard is the UX guardrail.
- **Role catalog is policy-mirrored, not free-typed.** Mirror `RoleAssignmentPolicy.CLUB_ADMIN_GRANTABLE` in one FE constant with a comment pointing back. Drift is silent — guard against it by ADR-citing in the comment.
- **Diff-and-PUT preserves unrepresented roles.** Load-bearing for this story: PUT payload = `(currentFromServer \ uiManagedRoles) ∪ checkedBoxes`. The naive shape (checkbox-set → PUT) silently demotes a legacy-imported SYSTEM_ADMINISTRATOR on any profile edit because the backend's policy is checked on the *added* set, not the *removed* set.
- **Self-delete / last-admin.** Surface backend 409 inline in the confirm dialog. Optional UX hint (disable Delete when `user.id === session.user.id`) is fine; backend gate is authoritative.
- **Stale JWT after revoke.** S-052 accepted residual (ADR 0007: 15-min access token). UI affordances may stay until refresh; every API call 403s in the meantime. No UI duty.
- **Person picker shape.** Must use `POST /api/v1/persons/lookup` (exact-match + audited per S-051) — never `GET /persons?q=` (enumeration). Reviewer check: network tab on invite flow.
- **Store hygiene.** `UsersStore` carries `UserListItem[]` + edit view-model only. No `keycloakSub` (drop from mapper if surfaced by regen), no `access_token`/refresh/id_token (per CLAUDE.md §10).
- **No new audit events.** S-052 owns `USER_INVITED` / `USER_UPDATED` / `USER_DELETED` / `USER_ROLE_GRANT_REJECTED` / `USER_RESEND_INVITE`. Surface rejected-roles list from a 403 response — never retry with a stripped set silently.

## Test plan

**Pyramid.**
- *Unit (Vitest):* `UsersStore` (list/select/invite/update/deactivate/resend, the 409-classifier, the role-diff formula `(current \ managed) ∪ checked`). No `*.component.spec.ts` per CLAUDE.md §8.
- *E2E (Playwright):* one spec, content pinned by S-052 §Parity strategy. **Mock-backend** (`page.route` + mock-auth), not live Keycloak — live-IdP SPA e2e is S-109/S-110 territory per CLAUDE.md §8; S-052's Mailpit/KC assertions are backend-side and already shipped.
- *Principal:* mocked CLUB_ADMINISTRATOR (sole role) via per-spec `/api/v1/me` stub — exercises the architectural rule that sysadmin has no surface. If a reusable helper is wanted later, that's a small fixture extraction (PR-description note, not a blocker).

**Parity.** Legacy `e2e/tests/masterdata/users-crud.spec.ts` carved out via `parity_excluded:` (stamped above). Rationale points at S-052 §Parity exclusions; not re-enumerated. **Cutover gate** inherited from S-052 (zero-delta on row-appears-after-invite + role-change-visible-on-reload); not restated.

**Screenshots** in `alpenflight/web/e2e/screenshots/users/`:
- `01-list-populated.png`, `02-invite-form.png`, `03-list-after-invite.png`, `04-edit-roles.png`, `05-deactivate-confirm.png`.

**Fixtures.** Spec must seed at least one Person in the `/persons/lookup` mock (the picker happy-path). Role-catalog filtering is client-side (covered by the unit test on the store); no `/api/v1/roles` endpoint exists or is needed.

## Performance plan

(N/A — pure SPA story; no hot paths, queries, indexes, or latency budgets introduced. Backend perf was pinned in S-052; per-row KC role-mapping read is the accepted residual.)

<!-- modernize-refine: end -->
