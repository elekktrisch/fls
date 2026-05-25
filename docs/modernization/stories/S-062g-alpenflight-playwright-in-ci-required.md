---
id: S-062g
title: Wire alpenflight Playwright into PR CI required aggregator
epic: E-10
status: todo
depends_on: [S-062b, S-062f]
acceptance:
  - The `alpenflight-e2e` workflow's Playwright job is included in `ci.yml`'s `required` aggregator (or run as a parallel `required` job) so a failing Playwright run blocks PR merge under branch protection.
  - Workflow duration on a typical PR stays under 8 minutes (current single-spec run is ~3 min including ng-serve boot; budget extends once additional specs land).
  - Branch protection on `main` lists the new job as required.
  - Playwright report + screenshots continue to publish to `https://elekktrisch.github.io/fls/alpenflight/` on main pushes (the current `alpenflight-e2e.yml` already handles this; just don't break it).
estimate: S
adr_refs: [0004]
origin: post-S-062b discovery
refined: false
---

## Context

S-062b shipped three real bugs that all survived green CI: `tenantRequiredGuard` redirected every mock-auth tenant page to `/clubs`, `<af-date-picker mode="range">` deadlocked the browser, and `<af-icon name="copy" />` threw on every kebab render. None were caught because Vitest only exercises logic, never a browser. The new `alpenflight-e2e.yml` workflow runs the Playwright suite on every PR but is NOT in the `required` aggregator, so a regression still merges.

This story wires the workflow into `required` so future Playwright failures (route freezes, missing icons, dropdown breakage, primitive deadlocks) block merge.

## Dependency on S-062f

S-062f fixes the currently-failing air-state-dropdown interaction in `flights-list.spec.ts`. Wiring an unreliable spec into `required` would just be a flaky merge gate — fix the spec first, then promote.

## Out of scope

- Adding more specs to the alpenflight Playwright suite (per-feature stories own that).
- Cross-browser coverage — Chromium-only for now matches the legacy suite.
- Real-OIDC Playwright lane — that's S-021 follow-up territory.

## Pickup notes

- `alpenflight-e2e.yml` already uploads artifacts + publishes to gh-pages on main; the `required` wiring is the missing piece.
- Branch protection setting lives on the GitHub repo settings; document the manual flip in the PR description for whoever has admin access.
