---
id: S-052
title: Users CRUD + role assignment
epic: E-06
status: done
started_at: 2026-05-26
done_at: 2026-05-26
merged: true
merged_at: 2026-05-28
depends_on: [S-051, S-026, S-019, S-020]
acceptance:
  - `User` entity ported, with `keycloak_sub` column linking to the IdP user.
  - Backend Users REST API for CLUB_ADMINISTRATOR (list / get / invite / update / soft-delete / resend-invite), with role assignment delegated to the Keycloak admin REST API via the new `alpenflight-backend-admin` machine client.
  - First-login JIT projection — `UsersService.materializeFromJwt` is in place; the filter that fires it on first authenticated request moves to S-169 (Users — JIT projection on first authenticated login).
  - SPA admin UI + the new-stack e2e spec move to S-168 (Users CRUD — SPA admin UI). Legacy parity spec `27-user-crud.spec.ts` is parity-excluded; the carve-out lands with S-168.
follow_ups: [S-168, S-169]
estimate: L
adr_refs: [0007, 0008, 0018, 0022, 0023]
refined: true
refined_at: 2026-05-26
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer]
github_issue: 134
github_pr: 135
---

## Context

Authority lives in Keycloak; the FLS app still needs a `User` row for `club_id` scoping, `personId` linkage, and audit-log actor resolution.

## Architectural rule (operator, 2026-05-26)

Users are managed by CLUB_ADMINISTRATOR only; SYSTEM_ADMINISTRATOR manages clubs, not users. No `/api/v1/admin/users/**` path exists — not deferred, not future work. Sysadmin cutover provisioning lives in S-028; one-off prod intervention goes through the Keycloak admin UI. The rule propagates to S-163 and every future user-touching story.

## Cross-story contracts

- **Consumes:** S-019 realm shape (extended with confidential `alpenflight-backend-admin` machine client, service-account scoped to `manage-users` + `view-users` + `query-users` only on `realm-management`); S-020 / S-026 unchanged; S-051 `PersonResponse` + `/api/v1/persons/lookup` pattern + cross-tenant Person PK-load guarantee; `UserPrincipalLookup` / `MeService` extended (`MeService.KNOWN_REALM_ROLES` retired in favor of `users.domain.Role.isKnown`).
- **Produces:** `UserDirectoryPort` + Keycloak adapter shared with **S-028** (bulk-provision); `UsersService.materializeFromJwt(Jwt, languageId)` wired in **S-169** (first-login filter); `User.assignToPerson()` + `UserPrincipalLookup.resolvePersonIdFor(jwt)` consumed by **S-163** (aircraft-owner predicate); `usr-` typed-id prefix registered in the codec; enables **S-058 / S-068** charter-flight crew flows once S-163 lands.

## Parity exclusions

Legacy `e2e/tests/masterdata/users-crud.spec.t/27-user-crud.spec.ts` is parity-excluded as an active gate (carve-out lands with S-168). Stays as the mechanics-and-persistence oracle. Excluded fields:

- `X-HTTP-Method-Override` POST-as-PUT/DELETE envelope (greenfield REST verbs).
- `UserName` / `FriendlyName` / `NotificationEmail` form shape — new stack uses `username` + `friendlyName` + `notificationEmail` per OIDC convention.
- `CanDeleteRecord` flag — replaced by RBAC + KC delete authz.
- Legacy confirmation-token email send-path — delegated to KC `UPDATE_PASSWORD` required-action flow.

Cutover gate: zero-delta on (a) row-appears-after-invite, (b) role-change-visible-on-reload. Documented delta on everything else.

## Accepted residual risks

- **Two concurrent CLUB_ADMINs editing the same user:** last-write-wins on both KC roles and DB row. Acceptable at this scale.
- **Stale JWT after role revoke:** access tokens live 15min (ADR 0007). Revoke takes effect on next refresh; audit row captures intent at revoke time.
- **List view per-row role-mapping read:** KC has no batch role-mapping endpoint. One KC call per row at list time; accepted at per-club user counts ≤ a few hundred (perf plan threshold). Revisit when the endpoint paginates.

## Rip-out plan

- `V8__dev_user_seed.sql` — defer deletion until S-169 (JIT-on-login filter) has passed one full dev bring-up cycle without it.
- Stale `// TODO(S-052)` markers — `grep -rn "TODO.*S-052"` post-merge.
