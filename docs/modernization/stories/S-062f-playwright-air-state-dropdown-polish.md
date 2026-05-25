---
id: S-062f
title: Polish flights-list Playwright spec — air-state dropdown click reliability
epic: E-07
status: todo
depends_on: [S-062b]
acceptance:
  - `alpenflight/web/e2e/tests/flights/flights-list.spec.ts` passes end-to-end without retries on a freshly-installed Playwright runner — including the air-state dropdown narrowing block (`Apply Started → assert 1 of 3 flights → clear`).
  - Test interaction with `<af-select>` (which wraps `nz-select`) is reliable across the suite — extract a helper if more than one spec needs it.
  - `visual snapshots — populated, filtered, empty` block produces three valid PNGs in `e2e/screenshots/flights/`.
estimate: S
adr_refs: [0004, 0008]
origin: post-S-062b discovery
refined: false
---

## Context

The `flights-list.spec.ts` Playwright spec ships with S-062b, but its air-state dropdown interaction times out after the preceding row-body click + back-navigation block. The page renders correctly (visible smoke against the dev server confirms the layout, status pills, immat lookup, and click-to-edit navigation all work); the test failure is a Playwright interaction quirk, not a page bug.

Likely root cause: after `page.goBack()` from the placeholder route, the `<af-select>` overlay state is stale and the next `getByRole('option', { name: 'Started' })` query doesn't match the just-opened dropdown's portal. Fix: split the test into two blocks (navigation block + filter block), wait for the page to fully re-hydrate after `goBack()`, or use a more robust `nz-select-item` selector that doesn't depend on `role=option` portal layout.

## Out of scope

- Adding new assertions to the spec (column inventory, kebab nav, immat lookup, summary count) — those work today.
- The `<af-select>` primitive itself — if a fix requires deep changes there, escalate to S-008.

## Pickup notes

- The failing assertion is `page.getByRole('option', { name: 'Started' })` at `e2e/tests/flights/flights-list.spec.ts:~280`.
- A clean repro: run `pnpm exec playwright test --config=e2e/playwright.config.ts e2e/tests/flights/flights-list.spec.ts` after `pnpm exec playwright install chromium`.
- The new `alpenflight-e2e.yml` workflow runs this spec on every PR — the spec going green will turn the workflow card from red to green on `https://elekktrisch.github.io/fls/alpenflight/`.
