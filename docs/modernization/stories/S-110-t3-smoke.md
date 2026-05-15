---
id: S-110
title: T3-equivalent smoke against new stack
epic: E-13
status: todo
depends_on: [S-020, S-021, S-062c]
acceptance:
  - A smoke spec: log in (via OIDC), GET `/api/v1/users/my`, GET a flight, PUT an update, GET again to confirm persistence.
  - Runs in CI on every PR.
  - Runs in production post-deploy as a synthetic health check.
estimate: S
adr_refs: []
parity_test: self
---

## Context
The "T3 sequence" from current-state §6 — minimum bar for "the system is alive."

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Write the smoke spec.
- [ ] Wire to CI.
- [ ] Wire to post-deploy synthetic monitoring.

## Notes
This is the spec the uptime probe (S-037) doesn't catch — it tests the full auth + DB + EF round-trip, not just `/actuator/health`.

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (§2 NFR) extends the T3 smoke to cover both density modes:

- **AC-DIR-1 (T3 runs on mobile + desktop projects).** The smoke spec runs on both the `mobile` (360 × 640) and `desktop-dense` (1920 × 1080) Playwright projects from S-109. Demonstrates the full auth → fetch → edit → persist round-trip works at both density modes.
- **AC-DIR-2 (dense-desktop keyboard-only path).** A second `desktop-dense` smoke variant exercises a keyboard-only flight edit — no mouse events fired — and asserts the save succeeds. Validates AC-DIR-3 of S-062c (keyboard-only completion).
- **AC-DIR-3 (synthetic monitor parity).** Production synthetic monitor runs the same dual-project setup; an alert fires if either project regresses.

**Refinement status flag:** Story is unrefined. Fold the above into the AC list when `/modernize-refine S-110` runs.

<!-- amendment-2026-05-15b: end -->
