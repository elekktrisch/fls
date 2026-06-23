---
id: J-12a
title: Pilot self-serve club join (/join)
epic: E-06
status: in_progress
started_at: 2026-06-23
journey0: false
carved: true
depends_on: [J-3, J-4]
rolls_up: [S-177, S-178, S-179]
acceptance:
  - "[happy] A newly-signed-up authenticated user with no t_user lands on /join (post-signup default), enters a valid 8-char club join code + optional note (≤500) and submits → a pending JoinRequest is filed (one open request per (keycloak_sub, club_id)), the club admin is emailed and an SSE join-request.status-changed fires; the pilot routes to /join/pending showing the club name/city/logo (public projection) + a Withdraw action."
  - "[happy] Approve (POST /join-requests/{id}/approve by a CLUB_ADMINISTRATOR with roles[] + optional personId) runs ONE transaction: set the Keycloak clubId user-attribute, create t_user, auto-create Person+PersonClub (or link the picked Person) with default roles, audit + email + SSE; the pending page force-refreshes the OIDC token and lands the now-member on /start."
  - "[edge] Withdraw: the pilot withdraws a pending request (pending→withdrawn, email + SSE), returns to /join, and can immediately re-submit (a withdraw starts NO cooldown)."
  - "[key-error] Submit errors: unknown code → 404 (inline 'check the code with your club admin'); caller already has a t_user (one-sub-one-club) → 409; 5 attempts / 15 min per sub → 429 + Retry-After (countdown); a denied (sub, club) within 24h → 429 (the cooldown survives join-code rotation)."
  - "[edge] Deny (POST /join-requests/{id}/deny, optional reason ≤500) → pending→denied; the pilot sees the reason on /join/pending + 'try a different code' → /join."
  - "[edge] /start guard: a user without t_user but with a non-final JoinRequest hitting /start (or any tenant-scoped route) is redirected to /join/pending; with neither → /join."
  - "[edge/audit] Tenant + PII: the pending-list + approve/deny are CLUB_ADMINISTRATOR own-club only; the join code is shown only to admins (null to pilots); audit redacts note + decision_reason (SHA-256) and email/friendly_name per S-027; the join_code_rotated event carries no code."
screen: /join (+ /join/pending) — new route, no legacy screen
headless_pulled_in: "JoinRequest backend (aggregate + state machine + submit/withdraw/me/list/approve/deny endpoints + 4 email templates + SSE join-request.status-changed + auto-Person fallback) → homed by the /join screen; Club.joinCode field + rotateJoinCode + POST /clubs/{id}/join-code/rotate + a minimal admin rotate affordance → homed here so the pilot/e2e has a real code"
migration: "N/A — greenfield. No legacy join mechanism exists (legacy registration is admin-push only). J-12a only WRITES new rows (JoinRequest, t_user, Person+PersonClub) into already-existing schema."
parity_test: alpenflight/web/e2e/tests/real-idp/join-request.spec.ts   # per-push real-idp proof runs ONLY this spec (ci.yml proof_spec derive); a still-fixme stub fail-safes to the J-0 baseline, then auto-scopes once it carries an active test — prior journeys' real-idp specs stay nightly + the §4 gate
mock_test:                                                              # real-idp-only journey, owns no mock-auth screen (chromium project excludes tests/real-idp/) — no per-push mock filter to scope
adr_refs: [0008, 0022, 0027]
---

## Context

Today a person can only become a club member by an admin push-invite (S-052). J-12a inverts that:
a pilot self-serves — signs up, enters a club's rotatable **join code**, and files a **join request**
the admin approves with roles (+ an optional existing Person link). It's the dominant new-member path
and the screen new signups land on by default. There is **no legacy precedent** (legacy has no
join-by-code), so the whole flow is greenfield; the proof is the real-idp join lifecycle, not a
legacy↔AlpenFlight pairing. J-12a ships the pilot `/join` screen + the full shared `JoinRequest`
backend; the admin approval *screen* is the sibling **J-12b** (it rides this backend).

## Spec must assert

No legacy file:line to cite — greenfield. The contract is S-177/S-178/S-179 + ADR 0022 §2 (the
state machine lives on the `JoinRequest` aggregate, not the DB). The one green real-idp run drives:
signup → `/join` → submit valid code → `/join/pending` → approve (via the real endpoint, admin
principal) → SSE → token-refresh → `/start`; plus deny+reason, withdraw+resubmit, the 429 rate-limit,
and the 404 unknown-code — i.e. acceptance items 1–7.

1. **Submit + pending.** Valid code files a pending request (partial UNIQUE `ux_join_request_alive`
   on `(keycloak_sub, club_id) WHERE status='pending'` = one open request per pair); admin emailed;
   SSE fires; `/join/pending` renders the public club projection + Withdraw.
2. **Approve = one transaction, cross-system.** KC clubId attribute write + `t_user` + auto-Person/
   PersonClub (or linked Person) + roles + audit + email + SSE, then the pilot's token refreshes and
   `/start` admits them. (Integration risk — see Notes.)
3. **Terminal + guard transitions.** withdraw / deny are terminal; re-submit allowed after withdraw,
   blocked 24h after deny; `/start` without a `t_user` but with a live request → `/join/pending`.
4. **Error envelope.** 404 unknown code, 409 already-a-member, 429 rate-limit + cooldown (cooldown
   survives code rotation — the code is a discovery key, not an auth token).
5. **Authz + PII.** Admin-only code visibility + pending-list + decisions (own club); audit redaction
   per S-027.

## Notes

- **Scope (operator-directed 2026-06-23).** A journey needs **≥1 screen for a visible result**, not
  exactly one — but here the operator chose to **ship the pilot screen first** for a fast visible
  result. So J-12a = pilot `/join` + `/join/pending` + the full shared `JoinRequest` backend
  (S-177/S-178/S-179); the admin `/join-requests` approval screen + S-181 invite robustness become
  **J-12b** (`depends_on: [J-12a]`, carved JIT later). The approve/deny ENDPOINTS ship in J-12a — the
  pilot lifecycle proof needs them; J-12a's e2e drives approval through the real endpoint with an admin
  principal even though the admin SCREEN is J-12b. [[feedback_journey_min_one_screen_not_exactly_one]]
- **No design reference, no legacy screen.** `design-reference/` has no auth/onboarding screens; legacy
  has no join flow. Build `/join` + `/join/pending` greenfield from the AlpenFlight UI kit. The story
  FIELD specs are load-bearing: 8-char code input (auto-uppercase, monospace, alphabet
  `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`), note textarea ≤500, the public club projection (name/city/logo)
  on the pending page, the 429 Retry-After countdown.
- **Cross-system approve is the load-bearing integration risk (budget for it at the gate).** Approve
  writes the Keycloak clubId user-attribute AND the local `t_user`/Person rows. A partial failure
  (KC write commits, DB rolls back, or vice-versa) half-joins the user. Order the KC write so a failed
  DB txn doesn't strand a KC attribute (or make approve idempotent + reconciling); the 409-on-existing-
  `t_user` makes a retry safe. This is exactly the kind of work the gate surfaces — flagged, not a
  re-carve trigger.
- **Email synergy with J-11.** The 4 join-request templates (admin-new-request, pilot-approved,
  pilot-denied, pilot-withdrawn, i18n) ride J-11's just-shipped Thymeleaf DB-override-then-file resolver
  — per-club override-able with no redeploy. These are APP-sent (Spring mail → mailpit), NOT
  Keycloak-sent, so the KC-26 SMTP-drift rider does NOT apply here.
- **SSE reuse.** `join-request.status-changed` reuses J-3's dashboard SSE push infrastructure.
- **Schema readiness (greenfield write, no migration).** `Club`, `Person`+`PersonClub`, `User`,
  `UserDirectoryPort` + the `alpenflight-backend-admin` KC machine client all exist (J-4 / S-052);
  `Person.joinClub(...)` mutator already takes default roles for the auto-Person path. New schema only:
  `t_club.join_code TEXT NOT NULL` + `UNIQUE ux_club_join_code` (global), and `t_join_request` +
  `ux_join_request_alive`. (`t_person_club` already exists — it was never *migrated*, but the table is
  there, so the write is safe.)
- **≤40% debt slot.** Zero `_BOYSCOUT.md` riders touch this surface (verified) — J-12a is a
  ~pure-feature greenfield journey (the ≥60%-feature rule is trivially met). COMMENT-STRIP / HISTORY→GIT
  apply per-touch as always.
- **Seam hints (non-binding, for /do-ship):** `Club.joinCode` + `rotateJoinCode(Clock)` + the rotate
  endpoint — one aggregate touch; the `JoinRequest` aggregate + state machine + `t_join_request` Flyway
  — one aggregate; `JoinRequestController` (submit / withdraw / me / list / approve / deny) — one
  resource; the approve orchestration (KC attribute + t_user + auto-Person/PersonClub + roles, one txn)
  — one application service; the rate-limit/cooldown guard (5/15min per sub, 24h per (sub,club)) — one
  component; the 4 email templates + send-on-transition (over the J-11 resolver) — one component; the
  pilot SPA (`/join` form + `/join/pending` + post-signup landing flip + `/start` guard + SSE) + store —
  one feature folder.

## Tasks

- [x] **T-01** — Real-idp `join-request.spec.ts` stub (structure + selectors + thin signup→/join→submit→pending flow) + scaffold the J-12a one-page proof gallery + link from the persistent index.
- [x] **T-02** — Scope the per-push gate to J-12a (journey `mock_test`/`real_test` frontmatter + CI filter); prior journeys run mock-IdP (full regression → nightly + the §4 gate).
- [x] **T-03** — `Club.joinCode`: Flyway (`t_club.join_code TEXT NOT NULL` + `UNIQUE ux_club_join_code` global) + `Club.rotateJoinCode(Clock)` domain method + `POST /api/v1/clubs/{id}/join-code/rotate` (CLUB_ADMINISTRATOR) + admin-only `ClubResponse.joinCode` (null to pilots) + `club.join_code_rotated` audit (no code in payload).
- [x] **T-04** — `JoinRequest` aggregate + state machine (pending→approved/denied/withdrawn ON the aggregate, ADR 0022 §2) + Flyway `t_join_request` (+ partial UNIQUE `ux_join_request_alive` on `(keycloak_sub, club_id) WHERE status='pending'`) + repository.
- [x] **T-05** — JoinRequest submit/read REST: `POST /api/v1/join-requests` (joinCode+note → 201; 404 unknown code; 409 already-member; one-open-per-pair) + `POST /{id}/withdraw` + `GET /api/v1/me/join-request` (204 none) + `GET /api/v1/join-requests?status=pending` (CLUB_ADMINISTRATOR, tenant-scoped). Audit note SHA-256 redaction (S-027).
- [x] **T-06** — Approve/deny application service (cross-system, one txn): `POST /{id}/approve {roles[], personId?}` → KC clubId user-attribute write + `t_user` + auto-Person/PersonClub (or link picked Person) + roles + audit + email + SSE; `POST /{id}/deny {reason?}` → denied. Order the KC write so a failed DB txn strands nothing; re-approve idempotent (409-on-existing-t_user). **Sizing watch — split KC-write/persist vs endpoints if it overflows.**
- [x] **T-07** — Brute-force + cooldown guard: 5 submit attempts / 15 min per sub → 429 + `Retry-After`; 24h deny cooldown per `(sub, club)` (survives code rotation; withdraw starts NO cooldown).
- [x] **T-08** — 4 join-request email templates (admin-new-request, pilot-approved, pilot-denied, pilot-withdrawn; i18n) over J-11's Thymeleaf DB-override resolver + send-on-transition; publish SSE `join-request.status-changed` (reuse J-3's SSE infra).
- [x] **T-09** — Pilot SPA store + API client (submit / withdraw / me over `/api/v1/join-requests` + club join-code rotate).
- [ ] **T-10** — `/join` screen: route + 8-char code input (auto-uppercase, monospace) + note textarea ≤500 + submit; error envelope (404 inline, 409 message, 429 countdown); post-signup landing default → `/join` (S-134 flip, intent params).
- [ ] **T-11** — `/join/pending` screen: public club projection (name/city/logo) + Withdraw + SSE subscribe → on approved force OIDC token-refresh → `/start`, on denied show reason → `/join`, on withdrawn → `/join`; + the `/start` guard (no `t_user` + live request → `/join/pending`; neither → `/join`).
- [ ] **T-12** — Thicken `join-request.spec.ts` to full real assertions: signup → `/join` → submit → `/join/pending` → approve (real endpoint, admin principal) → SSE → token-refresh → `/start`; deny+reason; withdraw+resubmit; 429 rate-limit; 404 unknown-code.

## Assumptions made

- J-12 is split into J-12a (this) + J-12b; the operator chose to ship J-12a first (a fast visible
  result). J-12b `depends_on: [J-12a]` and is carved JIT later.
- `depends_on: [J-3, J-4]` — greenfield write, no migration FK closure to satisfy; the write targets
  (Club/Person/PersonClub/User + KC machine client) all exist from J-4/S-052, SSE from J-3.
- The approve endpoint + its KC-write side-effect ship here (first needed by the pilot lifecycle proof),
  exercised in J-12a's spec by a real CLUB_ADMINISTRATOR principal via the endpoint.
