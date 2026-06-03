---
id: S-062h
title: Flight-edit resilience — IndexedDB drafts + 412 inline diff + marginal-3G
epic: E-07
status: todo
rolled_up_into: J-2
depends_on: [S-062c, S-067]
acceptance:
  - `flight-draft.service.ts` debounce-saves (500 ms) the in-progress wizard draft to the existing `af-user-preferences-service` Dexie store (provisioned by S-062c). Keys: `new:<sub>`, `edit:<flightId>:<sub>`, `copy:<sourceId>:<sub>`. Different keys never cross-restore.
  - On form open, when a matching draft key exists AND `lastSavedAt > server.lastModifiedAt`, prompt "Continue draft / Start fresh" via `<af-dialog>`. The page-header "Save draft" button forces a flush; cleared on successful server save.
  - Aircraft-change rebuild (engine-counter reset + defaults re-apply) writes rebuilt values BEFORE the 500 ms debounce flushes; draft holds rebuilt state, never clobbered.
  - Route-key change (`/flights/new` ↔ `/flights/:id` ↔ `/flights/copy/:id`) is a distinct draft scope; transitions never cross-restore.
  - `flight-conflict-prompt.component.ts` (`<af-dialog>`-backed) — on `412 Precondition Failed` from a PUT, opens an inline diff dialog: per-field "keep mine / keep theirs", first conflicting field focused on open, Enter activates the focused choice, **no auto-retry**. Replaces the placeholder 412 toast plumbed by S-062c.
  - On `409 Conflict` (state-gate reject from S-062a, e.g. `DELIVERY_BOOKED`, or `ObjectOptimisticLockingFailureException` race): non-blocking toast with "Reload latest" action; never an inline diff (policy not data).
  - SW mutation-queue replay with expired bearer (ADR 0015) surfaces a re-auth prompt offering "keep / discard queued change"; both the SW queue (origin-scoped) and the Dexie draft survive re-auth. No silent drop.
  - On form open, if the SW mutation queue has a pending entry for the current `flightId`, surface a "queued save from prior session" indicator above the wizard.
  - Conflict-prompt diff renders via Angular interpolation only; never serialized to `console.*` / telemetry. Telemetry emits `{flightId, fieldPaths[]}` only.
  - New Playwright specs (resilience-split from S-062c): `04f-draft-restore.spec.ts`, `04g-conflict-prompt.spec.ts`, `04h-marginal-3g.spec.ts`. Marginal-3G spec asserts dropdowns served from cache + save queues via SW + no spinner > 3 s at 200 ms RTT + intermittent loss.
estimate: M
adr_refs: [0005, 0007, 0008]
parity_test: none
split_from: S-062c
---

## Context
Resilience UX carved out of S-062c so the parity slice (wizard + paired-create + Copy-from-Last + smart-defaults + first-pass keyboard) can ship first. This story layers drafts + concurrency UX + marginal-connectivity polish on top once the happy path is green.

Reconciles AC-DIR-9 (drafts), AC-DIR-12 (412 inline diff / 409 toast — corrected per S-062c re-refine 2026-05-25), and AC-DIR-14 (marginal-3G) from the 2026-05-15b vision amendment.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `flight-draft.service.ts` — IndexedDB auto-save (500 ms debounce, per-route + per-user key) on top of the Dexie store provisioned by S-062c.
- [ ] Restore-prompt `<af-dialog>` (shares dialog primitive with `flight-conflict-prompt` and the dirty-confirm prompt from S-062c).
- [ ] `flight-conflict-prompt.component.ts` — inline diff for the 412 stale-version path; per-field keep-mine / keep-theirs.
- [ ] Wire 412 handler in `FlightStore.save` to open the diff dialog instead of the placeholder toast plumbed by S-062c.
- [ ] Wire 409 handler — toast with reload action.
- [ ] SW-queue replay re-auth prompt — coordinate with ADR 0015 implementation in `alpenflight/web/src/app/core/`.
- [ ] "Queued save from prior session" indicator on form open when SW queue has a pending mutation for current `flightId`.
- [ ] PII sanitization at the telemetry source for conflict-prompt content.
- [ ] Port resilience-split Playwright specs from S-062c's test plan: `04f-draft-restore.spec.ts`, `04g-conflict-prompt.spec.ts`, `04h-marginal-3g.spec.ts`.
- [ ] Vitest: `flight-draft.service.spec.ts` (route-key isolation, debounce timing, draft-restore precedence over `last-context`), `conflict-resolver.spec.ts` (already in S-062c test plan — verify it's owned here, not there).

## Notes
**Estimate calibration (M):** 2 services + 1 dialog component + 1 indicator + 3 Playwright specs + 2 Vitest specs. Reuses Dexie store + `<af-dialog>` primitive from S-062c — no new infrastructure.

**Why split from S-062c:** the parity slice (wizard, paired-create, Copy-from-Last, smart-defaults, first-pass keyboard, ported parity specs) is enough behavior for one PR. Adding drafts + conflict diff + marginal-3G specs pushes the PR past reviewable size and couples three independent risk surfaces.

**Depends on S-067** for the `@Version` column landing — 412 only fires when the server actually round-trips a version. S-062c plumbs the `If-Match` header outbound and a placeholder inbound toast; this story replaces the toast with the diff dialog once 412 actually fires.

**Out of scope:**
- Cross-flight draft management (only one in-progress new flight per user; new entry overwrites).
- Server-side draft persistence (drafts are client-only by design).
- Validation rejection-path UX depth — S-101.
