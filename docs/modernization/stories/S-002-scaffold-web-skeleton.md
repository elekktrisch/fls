---
id: S-002
title: Scaffold next/web/ Angular skeleton
epic: E-01
status: todo
depends_on: []
acceptance:
  - `ng serve` runs the dev server; a placeholder "Hello FLS" route renders.
  - TailwindCSS is wired and a sample utility class (`text-blue-600`) renders correctly.
  - ESLint + Prettier are configured; `ng lint` passes on the skeleton.
  - Unit-test runner (Vitest preferred over Karma+Jasmine — modern, Vite-fast) is configured; one passing component test exists.
  - Playwright is wired against the new app (separate from the legacy `e2e/`); one passing landing-page test exists.
estimate: M
adr_refs: [0004]
parity_test: none
---

## Context
Frontend twin of S-001. Establishes the Angular project skeleton.

## Acceptance criteria
- See frontmatter. Plus: project uses standalone components (no NgModules); the `inject()` DI pattern; signal-based reactivity; control-flow syntax (`@if`/`@for`).

## Tasks
- [ ] Generate skeleton via `ng new next-web --standalone --routing --style=css --strict`.
- [ ] Add TailwindCSS via official Angular guide; commit `tailwind.config.js`.
- [ ] Configure ESLint with `@angular-eslint` recommended; add Prettier.
- [ ] Replace Karma+Jasmine with Vitest (or Jest if Vitest's Angular support has edges — re-evaluate at impl time).
- [ ] Add Playwright in a separate `next/web/e2e/` directory; write one smoke spec hitting `ng serve`.
- [ ] Confirm `tsconfig.json` is strict, no `any`.

## Notes
Modern Angular (signal-based, Angular 21 line per ADR 0004). Closes — by virtue of TypeScript strict mode + S-003/S-004 — the precondition for R5's fix.
