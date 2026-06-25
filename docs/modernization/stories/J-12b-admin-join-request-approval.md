---
id: J-12b
title: Admin join-request approval (/join-requests) + invite robustness
epic: E-06
status: done
started_at: 2026-06-24
done_at: 2026-06-25
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
J-12b is FE-screen-heavy + S-181's `UsersService.invite` hardening + folded riders. No migration → no fanout gate.

- [x] T-01 — real-idp spec stub (`admin-approve.spec.ts`) + data-driven gallery scaffold
- [x] T-02 — scope the per-push heavy lane to J-12b (branch-name `proof_spec` derive)
- [x] T-03 — `/join-requests` list screen + NgRx store + CLUB_ADMINISTRATOR-gated route + empty state
- [x] T-04 — approve modal (S-168 role catalog + reused Person picker + 409→inline error + toast)
- [x] T-05 — deny modal (optional reason ≤500 + char counter)
- [x] T-06 — nav entry + live SSE pending-count badge; folds [COMMENT-STRIP] + [TEST-ORPHAN] (nav-bar relocation)
- [x] T-07 — S-181 invite robustness backend (3-branch KC pre-check + welcome-attached template + tenant-leak compensation)
- [x] T-08 — S-181 real-idp `users-invite.spec.ts` (bind-existing: KC clubId set + welcome-attached + no reset)
- [x] T-09 — [GH-PAGES-DEPLOY-RACE]: one shared `gh-pages-deploy` concurrency group across 5 deploy jobs
- [x] T-10 — thicken `admin-approve.spec.ts` to the full real-idp lifecycle + gallery capture
- [x] T-11 — gate cluster (one seam): cpd helper extraction · audit re-keyed to `InvitedAuditPayload` · email lowercased pre-lookup · clubId fail-closed
- [x] T-12 — nav-change mock fallout: `nav-bar.spec.ts` collapsed assertion + `flights-list` `emptyPage` api-floor
- [x] T-13 — KC PUT identity bug (below) + `users-invite.spec.ts` made retry-idempotent
- [x] T-14 — faithful KC PUT round-trip (re-send `enabled`+`requiredActions`) + real wire-stub IT for write+clear paths

## Outcome

Shipped: the `/join-requests` CLUB_ADMINISTRATOR screen (tenant-scoped pending list, approve modal with
RoleAssignmentPolicy-gated roles + optional Person link, deny modal, live SSE pending-count badge, empty
state, non-admin guard) over J-12a's approve/deny backend, plus **S-181** — `UsersService.invite` binds a
pre-existing Keycloak user (unattached → bind + welcome-attached, skip reset; attached-elsewhere → 409)
with deterministic clubId-attribute compensation. Riders cleared: [GH-PAGES-DEPLOY-RACE] (5 deploy jobs
serialized), [COMMENT-STRIP] (`app.routes.ts`+`nav-sections.ts`), [TEST-ORPHAN] (`nav-bar.spec.ts`).

**Load-bearing finding (escalation-worthy, fixed):** the §4 real-idp gate exposed a genuine backend bug in
the shipped S-181 bind path — `writeClubIdAttribute`/`clearClubIdAttribute` PUT `{attributes}` only, but
Keycloak 26.5.7's `PUT /users/{id}` is **field-selective**: a body omitting `email`/`firstName`/`lastName`
NULLS them (empirically probed 3×), so the bind wiped the bound user's identity and it vanished from
`?email=&exact=true`. Fixed with a full read-merge-write (`putMergedUser`) that re-sends identity +
`enabled` + `requiredActions` (the latter two defensively, against KC version drift) and preserves `locale`;
proven by wire-stub ITs capturing both the write and the clear-compensation PUT bodies. `setEnabled`'s
`{enabled}`-only PUT was empirically confirmed safe. cpd baseline tightened 5420 → 5362.

**Gate:** `ci` job-level green on the merge head (build + `check` + all mock-e2e shards + the admin-approve
real-idp clean-seed proof + dashboard/profile proofs). gap-hunter ×3 + a confirming pass → green-is-honest
(no mocked seams on happy/key-error; the one credential-posture blocker fixed + tested). Cross-journey
real-idp regression: J-12b's own specs green; the only reds are 4 chronic pre-existing flakes
(`token-lifecycle:87/:190`, `hardening-J26:226`, `fan-out-migration-parity:143`), each proven red on `main`.

**Parity exclusion / for /do-retro:** the 4 chronic real-idp flakes above are not J-12b's — candidate for a
quarantine/stabilization rider.
