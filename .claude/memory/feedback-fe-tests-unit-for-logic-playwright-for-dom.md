---
name: feedback-fe-tests-unit-for-logic-playwright-for-dom
description: "next/web testing posture — vitest covers logic classes only (services, signal stores, mappers, pure utilities). Playwright owns DOM testing. No *.component.spec.ts that asserts rendered output."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: f6575fd5-7529-4ef3-ae02-55ce640c45f9
---

For `next/web/` (Angular SPA), the testing split is:

- **vitest unit tests** = logic-only. Services, signal stores, mappers, type guards, pure utilities. Never `TestBed.createComponent` + DOM assertions (`nativeElement.textContent`, `querySelector`, `toHaveText`, etc.).
- **Playwright e2e tests** under `next/web/e2e/tests/<feature>.spec.ts` = everything DOM. UI rendering, routing, ARIA, keyboard flows, "the generated client actually wires to the network" assertions. Mock the backend via `page.route('**/api/v1/<path>', route => route.fulfill({...}))` when a live server isn't needed.

**Why:** zoneless + signal-based Angular makes vitest component testing fragile (signal-effect timing, change-detection cycles, missing `await fixture.whenStable()`). Every rendering assertion duplicates surface a Playwright test would cover sooner with a real browser. vitest stays cheap-and-fast for the cases where it actually catches regressions (logic-class behavior).

**How to apply:**
- New `*.component.spec.ts` files that assert on rendering: don't write them; write a Playwright spec instead.
- Existing offenders (e.g. `landing.component.spec.ts` predating this rule): delete on next touch — the Playwright sibling typically already covers it.
- Codified in `next/web/CLAUDE.md` §8 — that's the canonical source for future implementers. This memory note is the operator-preference-history breadcrumb.

Surfaced 2026-05-17 mid-S-004 implement after vitest's vi.mock cache-key didn't intercept the path-aliased generated client import (cost a CI cycle); the operator generalized "let's also update the testing strategy."
