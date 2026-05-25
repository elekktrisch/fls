---
name: feedback-e2e-screenshots-for-visual-verification
description: Playwright specs in alpenflight/web/e2e/ must screenshot at key states so Claude can verify UI changes visually without the operator manually saving PNGs.
metadata: 
  node_type: memory
  type: feedback
  originSessionId: fb3468fb-f451-48ed-b919-d5b0f9111abc
---

Every Playwright spec under `alpenflight/web/e2e/tests/` that renders a non-trivial view writes a screenshot to `screenshots/<feature>/<descriptive-name>.png` at each meaningful state (populated, filtered, empty, error, dialog-open, post-submit, etc.).

**Why:** Without screenshots, Claude can't visually verify UI changes — when something looks wrong, the operator has to manually run the app + save a PNG into the repo so Claude can look at it. The operator did this once on S-067 (2026-05-25) for the flights filter-bar misalignment; doing it every time is friction. Letting the e2e run produce the screenshots makes `pnpm e2e` (or its CI artifact) the single source for "what does the UI actually look like right now".

**How to apply:**
- New Playwright specs: add `await page.screenshot({ path: 'screenshots/<feature>/<state>.png', fullPage: true })` at every state the spec asserts on. One PNG per assertion-cluster, not one per `expect`.
- Existing specs touched during a story: opportunistically add screenshots if the spec already drives the screen and is silent on visuals. Boyscout, not a blocker.
- Naming: `<NN>-<state>.png` (`01-populated.png`, `02-filtered.png`, `03-empty.png`) so files sort in flow order. See `flights-list.spec.ts:316-346` for the established pattern.
- Output dir is `screenshots/` at repo root (gitignored — these are diagnostic, not deliverables). CI uploads them as test artifacts.
- This is NOT visual-regression testing (no `toHaveScreenshot`). The screenshots are diagnostic output Claude can read; assertions stay on data-testids + DOM.

Codifies: see [[feedback-fe-tests-unit-for-logic-playwright-for-dom]] — same Playwright-owns-DOM rule, plus a "produce visual artifacts while you're at it" extension.
