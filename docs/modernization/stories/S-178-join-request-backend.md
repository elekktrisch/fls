---
id: S-178
title: Join-request domain + REST + emails + auto-Person on approve
epic: E-06
status: done
rolled_up_into: J-12a
depends_on: [S-082, S-176, S-177, S-051, S-052, S-026, S-027]
integration_base: integration/users-suite
acceptance:
  - New `JoinRequest` aggregate root + Flyway migration for `t_join_request` with columns: `id UUID PK`, `keycloak_sub UUID NOT NULL`, `email TEXT NOT NULL`, `friendly_name TEXT NOT NULL`, `club_id UUID NOT NULL FK`, `note TEXT NULL` (capped 500 chars), `status TEXT NOT NULL` (`pending` | `approved` | `denied` | `withdrawn`), `created_on TIMESTAMPTZ`, `decided_on TIMESTAMPTZ NULL`, `decided_by_user_id UUID NULL`, `decision_reason TEXT NULL` (capped 500 chars). `@TenantId` on `club_id` per ADR 0008. State machine + transitions live on the aggregate per ADR 0022 directive 2 (not in CHECK constraints).
  - Partial UNIQUE `ux_join_request_alive` on `(keycloak_sub, club_id) WHERE status = 'pending'` — one open request per (sub, club).
  - `POST /api/v1/join-requests` (any authenticated principal; tenant filter bypassed via a `LookupTenantContext` window — the caller has no tenant yet) accepts `{ joinCode, note? }`. Resolves code → club; rejects unknown code with 404; rejects when caller already has a `t_user` row (any tenant) with 409 (one-sub-one-club rule); rejects on deny cooldown with 429; rejects on rate-limit breach with 429. On success creates a row with `status = pending` + emits `join_request.submitted` audit + sends admin-on-new-request email (S-082) + publishes `join-request.status-changed` event on the principal bus (S-176).
  - `POST /api/v1/join-requests/{id}/withdraw` (the request's own KC sub) flips `pending → withdrawn` + audit + pilot-on-withdraw email + SSE event.
  - `GET /api/v1/me/join-request` returns the caller's latest non-final or most recent request (`pending` | `denied` | `withdrawn`); 204 if none exists.
  - `GET /api/v1/join-requests?status=pending` (CLUB_ADMINISTRATOR, tenant-scoped) returns the caller's tenant's pending list. Tenant gate enforced through Hibernate `@TenantId`.
  - `POST /api/v1/join-requests/{id}/approve` (CLUB_ADMINISTRATOR) accepts `{ roles[], personId? }`. Transition `pending → approved` + `decided_on` + `decided_by_user_id`. Side effects, all in one transaction: (1) write `clubId` user-attribute to the KC user via admin REST (S-052's `alpenflight-backend-admin` client); (2) materialize a `t_user` row with the request's sub + the caller's tenant + friendlyName from the request; (3) if `personId` provided → call `User.assignToPerson(personId)` + assert tenant via S-051 lookup pattern; (4) if `personId` absent → auto-create Person via `Person.register(firstFromKc, lastFromKc, null)` (firstname/lastname pulled from the KC user profile via admin REST) + `Person.joinClub(callerTenant, …defaults)` + `User.assignToPerson(newPerson.id)`; (5) apply roles via `RoleAssignmentPolicy`; (6) audit `join_request.approved` + `t_user.created` + `t_person.created` (when auto-created); (7) send pilot-on-approve email; (8) publish SSE event.
  - `POST /api/v1/join-requests/{id}/deny` (CLUB_ADMINISTRATOR) accepts `{ reason? }`. Transition `pending → denied` + start `(sub, club)` 24h cooldown + audit + pilot-on-deny email + SSE event.
  - **Brute-force defense:** per-KC-sub rate limit on `POST /api/v1/join-requests` — 5 attempts / 15 min, Bucket4j-equivalent in-memory store. On breach: 429 + `Retry-After`. Audit-log a `join_request.failed_code_attempt` row on every 4xx submission (unknown-code OR rate-limited) carrying `{ sub, code_last4, club_id (null if unknown), reason }`.
  - **Deny cooldown:** per-`(sub, club)` 24h cooldown after a `denied` row. Server rejects re-submit with 429 + `Retry-After`. Withdraw does NOT start a cooldown. Cooldown effectively resets if admin rotates the club code (the new code is a different discovery key but resolves to the same club; the cooldown is sub-club, not sub-code — design choice: keep it strict, admin rotation does NOT reset the cooldown; refine confirms).
  - Email templates land in `src/main/resources/templates/email/join-request/`: `admin-new-request.html`, `pilot-approved.html`, `pilot-denied.html`, `pilot-withdrawn.html`. Each localised via Thymeleaf message bundles for `de`/`fr`/`it`/`en`; recipient locale is `User.languageId` for admins (per-recipient send loop) and the KC user's `locale` claim for the pilot.
  - Audit blobs: PII-redaction policy — `note` and `decision_reason` are hashed (SHA-256) in audit, not stored plaintext, per S-027 + S-051's redaction policy. `email` + `friendly_name` are PII-redacted at audit-read time (S-027 redaction allow-list).
estimate: L
adr_refs: [0007, 0008, 0017, 0018, 0022, 0023]
---

## Context

Q3–Q12 grilling outcome locks the join-request slice: admin-shared per-club rotatable code (S-177) → pilot submits via `/api/v1/join-requests` → admin sees in dashboard + email + approves with roles + optional Person picker → auto-Person fallback if admin skips picker → pilot's SPA learns via SSE (S-176) → token refresh → pilot lands at `/start`.

The auto-Person fallback (acceptance criterion 7's branch 4) closes the Person↔User dead-end at the same surface — pilots arrive with `t_user.person_id` set on day 1 and can self-edit Person + PersonClub fields immediately (S-182).

## Cross-story contracts

- **Consumes:** S-082 JavaMailSender + Thymeleaf base; S-176 SSE channel + `MePrincipalEventBus.publish`; S-177 `Club.joinCode` + code lookup; S-051 Person aggregate + `joinClub` + S-051's `/persons/lookup` pattern for the admin-picker validation; S-052 `UsersService` patterns + `alpenflight-backend-admin` machine client for KC attribute writes; S-026 `RoleAssignmentPolicy`; S-027 audit infra + redaction policy.
- **Produces:** `JoinRequestsService` + REST endpoints. SSE event kind `join-request.status-changed` (first consumer of the channel from S-176). Email templates (canonical layout established by S-082). Closes the Person↔User dead-end for new join flows; the migration ingest path is covered by the S-141 refinement pin.

## Open design questions (for refine)

- **One-sub-one-club gate placement.** Acceptance 3 rejects submission with 409 when the caller has any `t_user` row. Alternative: defer to approval-time (approve fails, request is denied). Refine picks based on UX preference — the eager 409 carries a clearer error message; the deferred check tolerates the race where admin approves concurrently with the pilot signing up elsewhere. Operator's earlier directive ("one sub → one club") strongly suggests the eager 409 is right.
- **Cooldown reset on code rotation.** Acceptance 9 keeps the cooldown strict (sub-club, not sub-code). Refine confirms.
- **KC user-attribute write atomicity.** Approval is a single transaction in the backend, but the KC admin-REST call is an external side effect. If the KC call fails after the DB transaction commits, the pilot has a `t_user` row but no `clubId` attribute on their JWT — JIT-on-next-login won't fire. Mitigation: write the KC attribute *first* (before the t_user insert), or use a compensating job. Refine pins the strategy.
- **Locale resolution for pilot emails.** The KC user `locale` attribute is set during signup (S-134 / S-021); confirm it's propagated to the JWT for pilot-locale resolution at email send time.
- **Withdrawn-then-re-submit timing.** Pilot withdraws (no cooldown), immediately re-submits with the same code. Should the rate limit still apply (5 attempts / 15min)? Yes — the rate limit is anti-abuse, not state-machine-specific.

## Tasks

- [ ] `JoinRequest` aggregate + repository + migration.
- [ ] `JoinRequestsService` covering submit/withdraw/list/approve/deny.
- [ ] `JoinCodeService.resolve(code)` in `clubs.application` (or co-located with S-177's code utilities).
- [ ] Rate-limit + cooldown stores (in-memory; survive single-VPS scale; refine confirms).
- [ ] 4 email templates + Thymeleaf message bundles.
- [ ] SSE event publishing on every state transition.
- [ ] Audit-event emission + PII redaction policy entries.
- [ ] Auto-Person + PersonClub create path with default `PersonRoleFlags` / `PersonNotificationPrefs`.
- [ ] Integration tests covering: happy path; unknown code; one-sub-one-club; rate-limit; cooldown; withdraw + re-submit; concurrent approve / withdraw.
- [ ] Cross-tenant leakage test on `GET /api/v1/join-requests` (S-024 family).
