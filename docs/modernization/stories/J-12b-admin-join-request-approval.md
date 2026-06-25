---
id: J-12b
title: Admin join-request approval (/join-requests) + invite robustness
epic: E-06
status: in_progress
started_at: 2026-06-24
journey0: false
carved: true
depends_on: [J-12a]
rolls_up: [S-180, S-181]
acceptance:
  - "[happy] A CLUB_ADMINISTRATOR opens /join-requests and sees their own-club pending JoinRequests (friendlyName + email + submitted-at + note + Approve/Deny per row); the nav pending-count badge reflects the count and updates LIVE via the join-request.status-changed SSE."
  - "[happy] Approve via the modal (role checkboxes from the S-168 catalog gated by RoleAssignmentPolicy + an optional Person picker via /persons/lookup exact-match) → POST /join-requests/{id}/approve {roles[], personId?}: KC clubId set + t_user + auto-Person/PersonClub (or the picked Person linked) + roles; the row drops, a success toast shows, the pilot is admitted (J-12a's approve backend)."
  - "[happy] Approve WITHOUT a picked Person → the auto-Person + PersonClub are created server-side (verified via a DB read)."
  - "[edge] Deny via the modal (optional reason ≤500 + char counter) → POST /join-requests/{id}/deny {reason?}: the request → denied + the pilot-denied email; the row drops + the badge decrements."
  - "[key-error] 409s surfaced on the screen: a pilot who joined another club since submitting → 409 'user already attached'; a picked Person from a different tenant → 409."
  - "[edge] Empty state: 'no pending requests' + a link to the Club edit (S-177 join-code panel). A non-admin cannot reach /join-requests (403 / redirect)."
  - "[happy] Invite robustness (S-181): UsersService.invite pre-checks the KC user by email — no KC user → create + password-reset invite (today's path); an UNATTACHED existing KC user → bind to the inviting tenant + a welcome-attached email (skip password reset), localised per the KC user's locale; an email ATTACHED to another club → 409 'already attached — leave that club first, or share the join code'. Audit user.invited carries the branch."
screen: /join-requests (new admin screen) + the users-invite flow hardened (S-181 — backend, no new screen)
headless_pulled_in: "none new — rides J-12a's JoinRequest backend (list / approve / deny + the join-request.status-changed SSE), the S-168 role catalog + /persons/lookup Person-picker, and the S-052 alpenflight-backend-admin KC machine client. S-181 hardens UsersService.invite (recognise a pre-existing Keycloak user)."
migration: "N/A — greenfield (JoinRequest + invite are greenfield; the approve side-effects write into existing schema)."
parity_test: alpenflight/web/e2e/tests/real-idp/admin-approve.spec.ts (new) + UsersInviteRobustnessIT   # per-push real-idp proof runs ONLY this spec (ci.yml proof_spec derive off the integration branch name); a still-fixme stub fail-safes to the J-0 baseline, then auto-scopes once it carries an active test — prior journeys' real-idp specs stay nightly (alpenflight-e2e-real-idp.yml) + the §4 gate; no mock_test: → prior journeys' mock specs run the full chromium suite per-push
adr_refs: [0008, 0022, 0027]
---

## Context

J-12a shipped the pilot self-serve join (the `/join` screen + the full `JoinRequest` backend: submit /
withdraw / me / list / approve / deny + the cross-system approve orchestration + the SSE). J-12b builds the
**admin counterpart**: the `/join-requests` approval screen a CLUB_ADMINISTRATOR uses to triage and approve
or deny pending requests (with roles + an optional Person link), driving J-12a's approve/deny backend
through a real UI — and **S-181 invite robustness**, which closes the admin-push-invite side of the
one-sub-one-club rule (an invite to a pre-existing Keycloak user binds rather than fails). Together with
J-12a this completes the club join/invite feature.

## Spec must assert

Greenfield — no legacy `/join-requests` screen; the contract is S-180/S-181 + the J-12a backend it rides
(ADR 0022 §2). The real-idp run drives: admin opens `/join-requests` → sees own-club pending → approve via
the modal (roles + optional Person) → row drops, badge decrements via SSE, pilot admitted; deny+reason; the
empty state; the 409s (already-attached / cross-tenant Person). Plus the S-181 invite IT over the three
branches (new KC user / unattached-existing / attached-elsewhere-409).

1. **Admin list + live badge.** Own-club pending only (tenant-scoped, J-12a's `GET /join-requests?status=pending`); the nav pending-count badge subscribes to the SSE and moves live on submit/approve/deny.
2. **Approve modal.** Role checkboxes from the S-168 catalog (RoleAssignmentPolicy gating — a forbidden role is not offered / 403s), the optional Person picker (S-168 `/persons/lookup` exact-match), the read-only request info; approve calls J-12a's endpoint, the row drops, the pilot lands in-club.
3. **Deny modal.** Optional reason ≤500 + char counter; deny → denied + the pilot-denied email.
4. **Error envelope.** Already-attached → 409; cross-tenant Person → 409 — both surfaced on the screen.
5. **Invite robustness (S-181).** `UsersService.invite` recognises a pre-existing KC user: unattached → bind + welcome-attached (skip password reset); attached-elsewhere → 409. Audited with the branch.

## Notes

- **The backend is already shipped (J-12a).** J-12b is the admin SCREEN over J-12a's list/approve/deny
  endpoints + SSE + the cross-system approve orchestration — plus S-181's backend invite hardening. J-12a's
  e2e drove approval through the raw endpoint; **J-12b drives it through the real `/join-requests` UI** (the
  fuller proof). No new backend approve logic — reuse J-12a's.
- **No design reference** (`design-reference/` has no auth/onboarding screens). Build `/join-requests`
  greenfield from the AlpenFlight UI kit, **reusing S-168's role-catalog component + the `/persons/lookup`
  Person-picker + the modal patterns**. i18n English-first (match the S-168 / S-051 siblings).
- **S-181 is a backend hardening, not a new screen** — `UsersService.invite`'s KC pre-existing-user branch
  (bind-vs-create) + a welcome-attached email template + a `users-invite.spec.ts` extension. It's the
  admin-push counterpart to J-12a's self-serve join: one-sub-one-club is now enforced from BOTH paths.
  S-181's bind-existing path is adjacent to the JIT user-materialization — see the JIT-username rider.
- **≤40% debt slot (riders folded):** **[GH-PAGES-DEPLOY-RACE]** (HIGH — add the shared
  `concurrency: gh-pages-deploy` group across the deploy jobs; J-12b's gate runs the racing workflows and
  this is intermittently redding `main`); **[COMMENT-STRIP]** per-touch on `app.routes.ts` + `nav-sections.ts`
  (J-12b adds the `/join-requests` route + the nav pending-count entry); **[TEST-ORPHAN]** relocate
  `nav-bar.spec.ts` into a collected subdir (J-12b touches nav); **[JIT-username robustness]** if S-181's
  bind-existing touches `JitUserMaterializerImpl`.
- **Seam hints (non-binding, for /do-ship):** the `/join-requests` SPA screen (list + approve modal + deny
  modal + the nav pending-count badge + SSE subscribe) + store — one feature folder; the role-catalog +
  Person-picker REUSE from S-168 — components, not re-built; `UsersService.invite` KC-pre-check (3 branches)
  + the welcome-attached email (over J-11's resolver) + `UsersInviteRobustnessIT` — one application-service
  seam; the gh-pages shared-concurrency-group fix — one CI seam.

## Assumptions made

- `depends_on: [J-12a]` — the JoinRequest backend (list/approve/deny + SSE) + the join-code shipped in
  J-12a (#238, merged); S-168 (roles + `/persons/lookup`) and S-052 (invite + the KC machine client) are
  implemented. Greenfield, no migration FK closure.
- No design reference for `/join-requests` — built greenfield from the UI kit, reusing S-168 components;
  proof is the real-idp admin-approval lifecycle, not a legacy pairing.
- Carved on the **`do-retro/J-12a-window`** retro branch (clean off `origin/main`), so the retro's suite
  edits + `_BOYSCOUT.md` riders ride J-12b and merge with it (the fix-forward path).

## Tasks

Backend (list/approve/deny + SSE + the orval client `api/generated/join-requests/`) shipped in J-12a;
this is FE-screen-heavy + S-181's `UsersService.invite` hardening + folded riders. No migration → no fanout gate.

- [x] **T-01 — real-idp spec stub + gallery scaffold.** `admin-approve.spec.ts` (5 `test.fixme`; clubadmin4=admin, pilot1=non-admin). **Testids T-03..T-06 MUST expose** (`TESTIDS` @ `admin-approve.spec.ts:43`): `join-requests-page/-list/-empty/-empty-club-link`; `join-request-row` + `-friendly-name/-email/-submitted-at/-note/-approve/-deny`; `approve-modal` + `-role-checkbox/-person-picker/-request-info/-submit/-error`; `deny-modal`/`deny-reason`/`-counter`/`deny-submit`; `join-request-success-toast`; `nav-join-requests-badge`. Gallery data-driven (already in `_ORDER.md` + generator `ROADMAP_FALLBACK`).
- [x] **T-02 — scope the per-push gate to J-12b.** Heavy real-idp lane runs ONLY `admin-approve.spec.ts`; prior journeys (incl. J-12a) run mock-IdP. Full real-idp regression stays nightly + the §4 gate (standing slot). Scoped by the branch-name `ci.yml` proof_spec derive (no hardcoded spec in any workflow) → `parity_test:` first token; while the spec is all-fixme it fail-safes to the J-0 baseline, auto-scoping once thickened. No `mock_test:` → prior journeys' mock specs run the full chromium suite per-push.
- [x] **T-03 — `/join-requests` list screen + store + route.** New `features/join-requests/` folder: NgRx store over the generated `listPending`, the pending-list page (friendlyName + email + submitted-at + truncated note + Approve/Deny per row), the empty state ("no pending requests" + link to Club edit join-code panel), route registration (CLUB_ADMINISTRATOR-gated; non-admin → 403/redirect).
- [x] **T-04 — Approve modal.** Component: role checkboxes from `role-catalog.ts` (RoleAssignmentPolicy gating), the optional Person picker REUSING `person-picker.component.ts`, read-only request info; POST the generated `approve {roles[], personId?}` → row drops + success toast.
- [x] **T-05 — Deny modal.** Component: optional reason textarea ≤500 + char counter; POST the generated `deny {reason?}` → row drops.
- [x] **T-06 — Nav entry + live pending-count badge + SSE.** Add the `/join-requests` entry to `nav-sections.ts` (CLUB_ADMINISTRATOR-visible) with a pending-count badge subscribing to `/api/v1/me/events` `join-request.status-changed` (bump/decrement live). Folds **[COMMENT-STRIP]** (`app.routes.ts` + `nav-sections.ts`) + **[TEST-ORPHAN]** (relocate `nav-bar.spec.ts` into the collected subdir).
- [x] **T-07 — S-181 invite robustness (backend).** `UsersService.invite` (`UsersService.java:141`) gains a KC pre-check by email: no KC user → today's create+password-reset path; UNATTACHED existing KC user → bind to the inviting tenant + welcome-attached email (skip password reset), localised per the KC `locale`; email ATTACHED elsewhere → 409. New `welcome-attached.html` template. `user.invited` audit carries the branch. New `UsersInviteRobustnessIT` over the three branches. Folds **[JIT-username robustness]** if it touches `JitUserMaterializerImpl`.
- [x] **T-08 — S-181 SPA spec extension.** Extend S-168's `users-invite` spec with the unattached-existing-KC-user case (Google-signup fixture → admin invites → `t_user` appears + KC clubId attribute set + NO password-reset email + welcome-attached email asserted via Mailpit). The real-KC+Mailpit assertion can't run in the mock `tests/users/users-invite.spec.ts` (page.route harness), so the case lands as the real-idp counterpart `tests/real-idp/users-invite.spec.ts`; the suite drives no live Google login (creds never enter Playwright — `auth/README.md`), so the unattached existing user is the provisioned-no-clubId KC identity `join-request.spec.ts` already uses. New `getUserById` KC attribute-read helper.
- [x] **T-09 — [GH-PAGES-DEPLOY-RACE] rider.** Align the gh-pages deploy concurrency across `ci.yml` + `alpenflight-e2e.yml` onto one shared `gh-pages-deploy` group so the two deploy jobs serialise (currently disjoint `ci-${ref}` vs `alpenflight-e2e-${ref}` → intermittent red `main`).
- [x] **— BATCH-BOUNDARY full check** (§4 fail-safe `ci` + real-idp regression on final head) — caught a cluster (below); the per-task focused runs missed all of it.
- [x] **T-10 — thicken the real-idp spec.** Full real assertions in `admin-approve.spec.ts`: list own-club pending → approve modal (roles + optional Person) → row drops + badge decrements via SSE + pilot admitted; deny+reason → pilot-denied email; empty state; the 409s (already-attached / cross-tenant Person); non-admin 403. Captures the gallery pairing.

### Gate-revealed (§4)

- [x] **T-11 — S-181 backend gate failures (one seam).** (a) **cpd** — extract the cloned `register→updateProfile→save→flush` block (`UsersService.java:208` ↔ `:261`) into a shared helper (build red: cpd 5513>5420). (b) **audit BLOCKER** — `AuditedTarget.created` is keyed on `"User"` not `InvitedAuditPayload`, so `PiiRedactor` redacts the `branch` + whole `user` projection (`application.yml:451` block is dead) — key it on the payload type + add an IT assertion reading `t_mutation_audit_event` for `branch` present. (c) **email** — lowercase before `findUserByEmail` (mixed-case → 500 instead of bind/409). (d) **clubId fail-closed** — present-but-unparseable clubId attr → treat as ATTACHED (409), not unattached.
- [x] **T-12 — nav-change mock fallout (one seam).** (a) `e2e/tests/shell/nav-bar.spec.ts:61` collapsed-nav assertion stale (`af-nav-section-/clubs` now present for the badge rollup — assert hidden, verify badge reachable via hamburger). (b) `flights-list.spec.ts` `emptyPage` (manual `newPage()`) never gets the api floor → shell badge's `/join-requests` fetch 500s the console-guard — extend the `**/api/v1/**` floor (`[]`) to manually-created pages.
- [x] **T-13 — real-idp `users-invite.spec.ts` first real run.** Surfaced a genuine S-181 **backend bug**: `writeClubIdAttribute`/`clearClubIdAttribute` PUT only `{attributes}`, but KC's `PUT /users/{id}` is a FULL-representation replace → the bind NULLED the user's `email`/`username`/`firstName`/`lastName`, so the bound user vanished from `?email=&exact=true` (`findUserByEmail → undefined`). Fix: `putMergedUser` re-sends the identity fields read back from KC + preserves `locale`. IT asserts identity survives the attribute write; cpd 5362 (baseline tightened). Spec also made retry-idempotent (run-unique friendlyName + exact match). Green twice-in-a-row local real-idp. The other 3 regression reds (token-lifecycle, hardening-J26, fan-out) are pre-existing flakes, proven red on `main`, untouched.
- [x] **T-14 — faithful KC PUT round-trip (gap-hunter BLOCKER).** `putMergedUser` re-sends identity but DROPS `enabled` + `requiredActions` → under the full-replace premise a bind re-enables a disabled user and CLEARS a pending `VERIFY_EMAIL`/`UPDATE_PASSWORD` (credential-posture downgrade). **First establish KC 26.5.7's real partial-PUT semantics empirically** (which fields a `{attributes}`-only / `{enabled}`-only PUT actually nulls) — that resolves the premise vs shipped `setEnabled` (`:118`) PUT-ing `{enabled}` only. Then read-back + re-send `enabled` + `requiredActions` (non-null-guarded) in `putMergedUser`; if the probe shows `setEnabled` also nulls identity, fix it the same way. Add a wire-stub IT capturing the **clear-clubId (T-07 compensation) PUT body** — it currently mocks the port, never the real read-merge-write — asserting identity + enabled + requiredActions + locale all survive.
