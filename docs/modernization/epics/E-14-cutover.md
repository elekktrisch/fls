---
id: E-14
title: Cutover preparation & execution
status: todo
adr_refs: [0007, 0010]
---

## Goal
Convert the rehearsed data migration + rehearsed restore + parity-validated rules engine + green expanded test suite into an executable cutover plan. Coordinate OGN handoff in lockstep. Confirm Proffix integration. Pick the production IdP. Rename `next/` to the final product slug. Queue the password-reset emails for all users (C14). Execute the cutover inside the ≤6-hour window (C6).

## Scope
- In: cutover runbook; data-migration rehearsal #2 (full timing dry-run inside 6 hr); OGN handoff coordination; Proffix verification (live-code investigation from vision §8); production IdP selection; DNS / proxy cutover plan; rollback plan; final product slug + folder rename; cutover-day execution; decommission notes.
- Out: feature parity work (lives in feature epics).

## Stories
- [ ] S-112 — Cutover runbook (draft)
- [ ] S-113 — Data-migration rehearsal #2 (full timing inside 6 hr)
- [ ] S-114 — OGN maintainer handoff coordination (lockstep flip plan)
- [ ] S-115 — Proffix integration verification (vision §8 open item — confirm where the live code lives + ensure compat)
- [ ] S-116 — Production IdP selection (Ory / Logto / Auth0 / self-hosted Keycloak in prod)
- [ ] S-117 — DNS / reverse-proxy cutover plan
- [ ] S-118 — Rollback plan + pre-cutover snapshot procedure
- [ ] S-119 — Force password-reset email queue (C14)
- [ ] S-120 — Product slug + `next/` → final-name folder rename
- [ ] S-121 — Cutover-day execution (runbook execution + green-light verification)
- [ ] S-122 — Decommission tracker (old codebases, `FLS.Workflow.Activator`, `Alpinely.TownCrier`, `Ionic.Zip` references)

## Done when
- Cutover executed; new system serves production traffic; old system stopped.
- All users have received reset-password emails; Keycloak login flow works for them.
- OGN inbound is hitting the new POST endpoint, not the legacy DB.
- Proffix sync is pulling from the new `/api/v1/deliveries/*`.
- Post-cutover smoke (T3-equivalent + critical-path spot-check) green within 1 hour of cutover completion.
