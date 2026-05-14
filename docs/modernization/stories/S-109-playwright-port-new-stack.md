---
id: S-109
title: Port full Playwright suite to run against the new stack
epic: E-13
status: todo
depends_on: [S-002, S-057, S-097]
acceptance:
  - The existing 34 specs (plus expansions from S-101..S-106) run against `next/server/` + `next/web/` and pass.
  - A separate Playwright config (`playwright.config.next.ts`) points at the new app's URLs.
  - CI runs both legacy and new-stack Playwright on relevant branches until cutover; new-stack-only post-cutover.
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
