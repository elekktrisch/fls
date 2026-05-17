---
id: S-109
title: Port full Playwright suite to run against the new stack
epic: E-13
status: todo
depends_on: [S-002, S-057, S-097]
acceptance:
  - The existing 34 specs (plus expansions from S-101..S-106) run against `next/server/` + `next/web/` and pass.
  - A separate Playwright config (`playwright.config.next.ts`) points at the new app's URLs.
  - CI runs both legacy and new-stack Playwright on relevant branches; the legacy suite stays green for as long as any tenant has not migrated.
estimate: L
adr_refs: []
parity_test: self
---

## Context
Specs are the parity oracle. Running them against the new stack is the verification step.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Copy + adapt specs as needed (selectors may differ; underlying assertions should not).
- [ ] Wire CI.
- [ ] Triage failures back into feature stories until green.

## Notes
L because adapting selectors across 34+ specs is real work. Consider data-testid attributes on the new SPA to make selectors stable.

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (§2 NFR "responsive breakpoints") makes Playwright the verification surface for multi-viewport coverage:

- **AC-DIR-1 (viewport-parametrized projects).** `playwright.config.next.ts` defines four projects representing the breakpoint set: `mobile` (360 × 640), `tablet` (768 × 1024), `desktop` (1280 × 800), `desktop-dense` (1920 × 1080). Each spec runs against all four unless explicitly opted out via test metadata.
- **AC-DIR-2 (per-spec viewport selection).** Specs that are inherently single-viewport (e.g. a dense-desktop keyboard-only flow) opt in via `test.use({ project: 'desktop-dense' })`. Specs that exercise the mobile / tablet layouts of the same page run on `mobile` / `tablet` / `desktop`. The matrix is explicit, not implicit.
- **AC-DIR-3 (axe-core a11y check per viewport).** Every page-load spec runs an axe-core scan at the active viewport and fails on any "serious" or "critical" finding. The touch-target rule (§2 NFR) is part of this scan.
- **AC-DIR-4 (network-throttling profile).** A reusable `marginalConnectivity` NetworkConditions profile (200 ms RTT + 5% packet loss) ships with the config; specs that exercise offline / poor-coverage UX consume it.
- **AC-DIR-5 (CI matrix expansion).** CI runs all four viewport projects on the new-stack workflow. Legacy-stack workflow remains single-viewport (legacy is laptop-only territory).

**Refinement status flag:** Story is unrefined. Fold the above into the AC list when `/modernize-refine S-109` runs.

<!-- amendment-2026-05-15b: end -->
