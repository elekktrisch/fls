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
adr_refs: [0007, 0008, 0018, 0022, 0023]
parity_test: tests/masterdata/27-user-crud.spec.ts
refined: true
refined_at: 2026-05-26
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer]
github_issue: 134
github_pr: 135
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
S-028 (bulk-provision tenant users in Keycloak) writes the initial `user.keycloak_sub` values when an operator-owned tenant is onboarded; this story handles the ongoing case (admin invites a new user from inside an active tenant).

L because the Keycloak-AlpenFlight-DB sync is non-trivial. Webhook vs. on-login lazy sync vs. periodic full sync — pick one. Lazy on-login is simplest; document the trade-offs.

<!-- modernize-refine: start -->

## Design notes

### Sync model — eager push on admin-invite + lazy JIT on first-login

Two write paths, no Keycloak SPI:

- **Admin invites (this story's primary flow):** `POST /api/v1/users` → Keycloak admin REST creates the KC user (`enabled=true`, `requiredActions=["UPDATE_PASSWORD"]`, `attributes.clubId=callerClub`, `locale` from picked `languageId`), service follows the `Location` header / `GET ?username=` to obtain the auto-generated `sub`, then inserts the local `user` row in the **same Spring transaction**. On DB-insert failure the orchestrator issues a compensating `DELETE` against Keycloak (best-effort; logs `USER_INVITE_KC_ORPHAN` on compensation failure). `executeActionsEmail` fires after commit (best-effort; "resend invite" endpoint covers retry).
- **First-login JIT (covers S-028 bulk imports, sysadmin manual KC edits, federated IdP):** extend `UserPrincipalLookup` so that when the JWT carries `clubId` + non-empty `realm_access.roles[]` but no row matches `sub`, a `user` row is materialized from JWT claims (`preferred_username`, `email`, `given_name`, `family_name`, `locale`). `ON CONFLICT (keycloak_sub) DO NOTHING` + re-read handles the dual-request race.

**Why not webhook SPI:** real push semantics, but cost is a custom Keycloak SPI JAR + an internal endpoint with shared-secret hardening + retry/DLQ. Eager-push covers in-app mutations; JIT covers out-of-band. Park as future work if drift bites operationally.

**Why not periodic full sync:** lands in wrong phase (S-081); the drift it catches converges on next login.

### Aggregate + schema

New `ch.alpenflight.users/` module, mirror the `persons/` four-package layout. `User` aggregate root is **cross-tenant** (the principal subject — tenant-scoping the principal is circular); CLUB_ADMIN scoping is `WHERE club_id = callerClub` in the repo, gated by `@PreAuthorize`.

Java-side invariants (not in DB, per ADR 0022 directive 2):
- `keycloak_sub` immutable post-create (identity binding).
- `club_id` immutable post-create (move-club = delete + recreate).
- `assignToPerson(personId)` / `unlinkPerson()` are the only `person_id` mutators — required by S-163.
- Soft-delete (`deleted_on`) is paired with KC `enabled=false`. Hard-delete deferred.
- **Roles do not live on the aggregate.** Read live from `realm_access.roles` (cache `MeService.KNOWN_REALM_ROLES` constant); writes go through KC admin client.

**Schema delta (V14 — rip-out + cleanup):**
- DROP `user_role` + `role` tables (and the V2 stale seed). Roles live in Keycloak; the seed catalog (`ADMIN/FLIGHT_OPS/INSTRUCTOR/PILOT/READER`) does not even match the realm-role catalog (`SYSTEM_ADMINISTRATOR/CLUB_ADMINISTRATOR/FLIGHT_OPERATOR/PILOT/OFFICE_USER/GUEST`) — a latent footgun.
- DROP `user.account_state_id` — dangling FK-shaped column with no target table; ADR 0022 directive 2 deviation that should not survive the port. The KC `enabled` flag + `deleted_on` cover the states.
- **Operator call (see Open design questions):** DROP `lockout_enabled`, `lockout_end_date_utc`, `access_failed_count`, `two_factor_enabled`, `phone_number_confirmed`, `email_confirmed` — all KC-owned. Keep risk: stale columns become a parallel-truth attack surface.
- DELETE `V8__dev_user_seed.sql` once JIT-on-login proves itself; until then keep V8 (idempotent on conflict) for fast-start parity.

### API surface

CLUB_ADMINISTRATOR cabin only (sysadmin variant deferred — see Open design questions):

| Endpoint | Notes |
|---|---|
| `GET /api/v1/users` | List in caller's club. Includes live KC fields: `roles[]`, `enabled`, `invitePending` (KC `requiredActions` non-empty), `lastLoginAt`. |
| `GET /api/v1/users/{id}` | 404 (not 403) for cross-tenant — IDOR contract from S-051's `PersonsController`. |
| `POST /api/v1/users` | Invite. `{username, friendlyName, notificationEmail, languageId, personId?, roles[]}`. Service validates the picked person belongs to caller's club via the S-051 lookup pattern. |
| `PUT /api/v1/users/{id}` | Mutable fields only (`friendlyName`, `notificationEmail`, `personId`, `languageId`, `roles[]`). Role diff → KC `role-mappings/realm` add/remove delta calls (NOT full replace). |
| `DELETE /api/v1/users/{id}` | Soft-delete local + KC `enabled=false`. Self-delete refused; last-CLUB_ADMINISTRATOR-of-club refused (409). |
| `POST /api/v1/users/{id}/resend-invite` | Re-fire KC `executeActionsEmail` for `UPDATE_PASSWORD`. Idempotent. |

### Keycloak admin-client integration

**Pick: hand-rolled Spring `WebClient` (or `RestClient`) against the KC admin REST API**, NOT the official `keycloak-admin-client` library. The official lib transitively pulls RESTEasy + JBoss-Logging + Jakarta-Activation — unwelcome on Spring Boot 4 / Reactor / Jackson. Surface needed is ~8 calls. Wired in `ch.alpenflight.users.infra.keycloak/`:

- `KeycloakAdminClient` — typed façade (`KcUserSpec`, `KcRoleAssignment` records).
- `KeycloakAdminTokenSupplier` — caches service-account access token (refresh ~30s before expiry).
- Logging interceptor MUST redact `Authorization: Bearer …` (admin token is sensitive).

**New realm-export delta (touched by this story, rebuilds `alpenflight-keycloak:local` image):** add a confidential client `alpenflight-backend-admin` with service-account `realm-management` roles **scoped to `manage-users` + `view-users` + `query-users` only** (NOT `manage-realm`, NOT `impersonation`, NOT `manage-clients`). Dev secret committed (matches `alpenflight-proffix` precedent); prod secret via `ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET`. Update `check-realm-shape.sh` to assert (a) the new client's `bearerOnly=false`, (b) `serviceAccountsEnabled=true`, (c) the secret value is the dev placeholder. Rotation procedure documented alongside the proffix one in `auth/README.md`.

**Realm-role catalog:** consolidate `MeService.KNOWN_REALM_ROLES` + SPA `AppRole` union into a single Java enum `Role` in `users.domain/`; wire-format is the string name.

### Cross-story contracts

- **Consumes:** S-019 realm shape (delta above); S-020 / S-026 unchanged; S-051 `PersonResponse` + `/api/v1/persons/lookup` pattern + cross-tenant Person PK-load guarantee; `UserPrincipalLookup` / `MeService` (extended, not replaced).
- **Produces:** `KeycloakAdminClient` + `KeycloakAdminTokenSupplier` shared with **S-028** (bulk-provision); `User.assignToPerson()` + `UserPrincipalLookup.resolvePersonIdFor(jwt)` consumed by **S-163** (aircraft-owner predicate); `usr-` typed-id prefix registered in the codec (mirrors `pn-` from S-051); enables **S-058 / S-068** charter-flight crew flows once S-163 lands.

### Rip-out (in this PR)

- `V8__dev_user_seed.sql` — defer deletion until JIT-on-login passes one full dev-bring-up cycle (keep as idempotent fast-start until then).
- `user_role` + `role` tables + seed + the V2 header comment lines mentioning them (V14 migration).
- `user.account_state_id` column (V14).
- Stale legacy `// TODO(S-052)` markers if any in the codebase (`grep -rn "TODO.*S-052"`).

## Edge cases & hidden requirements

### Sync orchestration
- **KC `sub` is server-assigned.** The admin `POST /users` returns 201 + empty body + `Location` header; the service must follow up with `GET /users?username=` (or parse Location) to obtain the `sub` before inserting the local row.
- **Idempotency on admin-invite retry.** Network blip / double-click → duplicate KC user with same email. Realm export must set `duplicateEmailsAllowed=false`; the `ux_user_username_lower` index blocks the DB side only after KC create succeeds. Service should treat "username already in KC" as a soft conflict (409) when no local row exists, prompting the operator to either resend-invite or reconcile.
- **Concurrent admin edits.** Two CLUB_ADMINs editing same user → last-write-wins on both KC roles and DB row. Acceptable at this scale; surface as accepted residual risk in PR.

### Role mgmt
- **Privilege-escalation matrix.** `RoleAssignmentPolicy.assertGrantable(callerRoles, targetRole)` is server-side enforcement (not just a DTO `@Pattern`). CLUB_ADMIN may grant `{CLUB_ADMINISTRATOR, FLIGHT_OPERATOR, PILOT, OFFICE_USER, GUEST}` only — never `SYSTEM_ADMINISTRATOR`. Target user's KC `clubId` attribute must equal caller's clubId. Rejection is 403 + audit emission of `USER_ROLE_GRANT_REJECTED`.
- **Filter the KC role list.** Edit screen must show only AlpenFlight realm roles — hide Keycloak built-ins (`uma_authorization`, `offline_access`, `default-roles-alpenflight`) and the `proffix-sync` client role.
- **Role read for target user ≠ caller.** Edit screen reads target's KC realm role-mappings (`GET /admin/.../users/{id}/role-mappings/realm`), NOT the caller's JWT. Easy misread of the AC.
- **Stale JWT after role revoke.** Access tokens live 15min (ADR 0007). Revoke takes effect on next refresh. Accepted residual risk; audit trail captures intent at revoke time.

### Lifecycle
- **Soft-delete + username re-use.** `ux_user_username_lower` is a full unique index (no `WHERE deleted_on IS NULL`). Re-creating a deleted user's username fails. Either flip to partial-on-alive (matches `member_number` pattern) or block reuse — **operator call**.
- **`person_id` orphaning under Person soft-delete.** `fk_user_person_id ON DELETE SET NULL` only fires on hard-delete; Person soft-delete leaves `user.person_id` pointing at a dead row. `MeService` already guards (`AND p.deleted_on IS NULL`); the user-edit screen must follow the same shape.
- **Self-edit / self-delete.** Caller cannot delete self (409). Removing one's own last CLUB_ADMINISTRATOR role refused at `RoleAssignmentPolicy`. Removing the **last** CLUB_ADMINISTRATOR of a club refused (409) — orphan guard.
- **`notification_email` vs KC login email.** Recommendation: keep decoupled (notification = transactional email, KC email = login identifier). Sync would re-create the legacy entanglement ADR 0007 aimed to escape. Document in the controller Javadoc.
- **Locale on KC user.** Picked `languageId` → KC `locale` attribute so `UPDATE_PASSWORD` email arrives in the right language. Defaults to English otherwise.

### Cross-tenant ride-through (legitimate, do NOT guard)
- `user.person_id` may point at a Person whose primary PersonClub is in a different club (multi-club pilot). Same cross-tenant ride-through as the Flight crew picker. Add `UserPersonCrossTenantIT` as the regression witness; document in the service Javadoc to forestall a well-meaning "tighten this" PR.

## Security plan

### Threat model (load-bearing rows only)

| Threat | Mitigation |
|---|---|
| CLUB_ADMIN privilege escalation via KC role grant | `RoleAssignmentPolicy` server-side allow-list per caller-role; `SYSTEM_ADMINISTRATOR` ungrantable from `/api/v1/users/**`; target's KC `clubId` attribute = caller's clubId. |
| KC admin-client credentials at rest | Dedicated confidential client `alpenflight-backend-admin` (`manage-users`+`view-users`+`query-users` only). Secret in env var, never in `realm-export.json` / `application.yml`. `check-realm-shape.sh` asserts dev-placeholder. Rotation runbook in `auth/README.md`. |
| Sync drift (orphan in either system) | **KC is source of truth for identity/credentials; local row is the projection.** Invite: KC-first, DB-second in one `@Transactional`; DB failure → compensating KC delete (best-effort, audit `USER_INVITE_KC_ORPHAN` on compensation failure). Delete: local soft-delete first, then KC `enabled=false` (NOT delete — preserves KC event log). Periodic reconciler deferred. |
| Email enumeration on invite/lookup | Exact-match-only lookup; audit `LOOKUP_HIT` + `LOOKUP_MISS` (SHA-256 hashed canonical key). Bucket4j rate-limit (10/min/caller) **deferred** to the same broader rate-limit story S-051 deferred to. |
| Legacy `lockout_*` / `access_failed_count` parallel-truth | DROP at port time (V14). If kept-for-cutover: receiving story must zero them on every import. |

### Authorization

- All mutating endpoints: `@PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")` + `@userAccess.canEdit(id, jwt)` SpEL bean (shape: `AircraftAccess`).
- `GET /{id}`: `@userAccess.canView(id, jwt)`; 404 (not 403) cross-tenant per S-051.
- **KC admin-API calls MUST scope by `q=clubId:<callerClub>`** when listing/searching users — the admin client is realm-wide; forgetting the filter leaks the realm. New R1-shape risk for this story; IT covers it.

### PII / audit

- `audit.redaction.entities.User` allow-list: `id, clubId, keycloakSub, personId, active, deletedOn, deletedByUserId`. Email + friendlyName + phone **omitted** (ride as `[redacted]` in before/after state); same for `UserResponse` DTO type key.
- Events emitted via `AuditTrail.record(...)`: `USER_INVITED`, `USER_UPDATED`, `USER_DELETED`, `USER_ROLE_GRANTED`, `USER_ROLE_REVOKED`, `USER_ROLE_GRANT_REJECTED` (synthetic-failure-style row from `RoleAssignmentPolicy`), `USER_LOOKUP_HIT` / `USER_LOOKUP_MISS`. Role-event extras carry `targetRole`, NEVER email.
- WebClient interceptor redacts `Authorization: Bearer …` from request logs.

## Test plan

### Pyramid
- **Unit (~8-12):** sync orchestrator two-phase, role-mapping delta, `RoleAssignmentPolicy`, JIT-create projection.
- **IT (~6-10):** authz matrix (mirror `ClubsAuthorizationTest`), tenant-scoped repo, role endpoints against WireMock KC, JIT-create on first authenticated request, KC list-by-clubId scoping (the R1-shape leak test).
- **E2E (~3):** new-stack spec `alpenflight/web/e2e/tests/users/users-invite.spec.ts` — invite → row appears + KC `users?email=` returns 1 + Mailpit shows invite + `requiredActions=["UPDATE_PASSWORD"]`; role round-trip; deactivate.

### Load-bearing cases worth naming
- **Compensating-delete on KC-create-success-then-DB-write-failure** — orchestrator returns a `Result` testable without reflection; WireMock asserts the compensating DELETE fired.
- **DB-write-success-then-KC-role-assign-failure** — pin the contract: half-state visible via `GET /{id}` (with `invitePending`/diagnostic state) vs auto-rollback. Test fixes the choice.
- **JIT-create race** — two concurrent first-login requests → single row (`ON CONFLICT DO NOTHING` + re-read).
- **`q=clubId:` scoping** — seed users in two KC clubs, list as CLUB_ADMIN of A → exactly A's users; missing filter = test fails loud.
- **`RoleAssignmentPolicy` matrix** — every (callerRole × targetRole) cell; SYSTEM_ADMINISTRATOR grant from CLUB_ADMIN path → 403 + `USER_ROLE_GRANT_REJECTED` emitted.

### Keycloak fixture strategy
**WireMock for unit + IT; real Keycloak (alpenflight-dev compose) for e2e only.** Real-Testcontainers KC adds ~30s context startup per IT class — unaffordable given the S-051 wallclock pain. WireMock is high-fidelity to the wire spec; drift bounded by the e2e layer hitting a real KC. Inject via `keycloak.admin.base-url` swap in `application-test.yml`; fixture lives alongside `JwtTestFixture` as `KeycloakAdminWireMockFixture`.

### Parity strategy
Legacy `e2e/tests/masterdata/users-crud.spec.ts` is **parity-excluded as the active gate** — same carve-out as S-051's persons-add-modal (route shape, auth model, form shape all greenfield). Stays as the **mechanics-and-persistence oracle** (row appears post-create, edit updates, delete removes/deactivates). The new-stack spec is the active gate. Frontmatter `parity_excluded:` block (file at finalize):

- `X-HTTP-Method-Override` POST-as-PUT/DELETE envelope.
- `UserName` / `FriendlyName` / `NotificationEmail` form shape — new stack uses `email` + `displayName` per OIDC convention.
- `CanDeleteRecord` flag — replaced by RBAC + KC delete authz.
- Legacy confirmation-token email send-path — delegated to KC `UPDATE_PASSWORD` required-action flow.

**Cutover gate:** zero-delta on (a) row-appears-after-invite, (b) role-change-visible-on-reload. Documented delta on everything else.

## Performance plan

(N/A — per-club user counts are tens to low hundreds; no hot path; KC admin calls are admin-UI initiated, not request-path. List endpoint returns unbounded `List<UserListItem>` matching S-051's deferred-pagination posture; promote to `Page<>` when first club crosses ~200 users.)

## Open design questions

1. **Sysadmin cross-cutting `/api/v1/admin/users/**` — ship now or defer?** Recommendation: defer. S-159 stripped sysadmin from tenant-scoped paths; S-028 covers cutover-time provisioning. No current operational driver for sysadmin ongoing user-mgmt — Keycloak admin UI suffices in the interim. Re-file when a concrete need surfaces.
2. **Drop legacy KC-shadow columns now (V14) or defer until after S-028 cutover?** Columns: `lockout_enabled`, `lockout_end_date_utc`, `access_failed_count`, `two_factor_enabled`, `phone_number_confirmed`, `email_confirmed`. Recommendation: drop now — S-028 writes Keycloak users, not these columns; keeping them is a parallel-truth attack surface (security threat-model row). If kept: S-028 must zero them on every import.
3. **`notification_email` decoupled from KC login email — confirm.** Recommendation: yes, decoupled. Notification email = transactional contact; KC email = login identifier. Sync would re-create the legacy entanglement ADR 0007 aimed to escape.
4. **Soft-deleted username re-use — allow or block?** Recommendation: flip `ux_user_username_lower` to partial-on-alive (matches `member_number` pattern in PersonClub). Legacy behavior was reuse-on-delete; user-friendliness wins.
5. **Realm-export `alpenflight-backend-admin` client — land in S-052 PR or file as S-019 amendment?** Recommendation: land in S-052 (this story owns the consumer; the realm delta has no value without the admin code).
6. **Delete V8 dev seed in V14, or keep as idempotent fast-start?** Recommendation: keep until JIT-on-login passes one dev-bring-up cycle; rip in a follow-up.

<!-- modernize-refine: end -->
