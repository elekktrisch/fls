---
id: S-097
title: Landing page port + nav-bar mechanism (closes R12)
epic: E-12
status: todo
depends_on: [S-002, S-008]
acceptance:
  - `/` (the public landing) renders with the legacy content.
  - Nav-bar visibility is controlled by a route flag (`data: { publicLayout: true }`) or a layout slot — *not* by a boolean expression in code.
  - A test asserts that the nav-bar is hidden on `/`, `/trialflight`, `/passengerflight` (closes R12).
  - Page is reachable without authentication.
estimate: S
adr_refs: [0004]
parity_test: tests/public/landing.spec.ts
---

## Context
R12 (the `||` tautology bug) is a vibe-level bug — replace the broken mechanism with a real one.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Implement the public layout pattern (Angular route data + layout component).
- [ ] Port the landing page content.
- [ ] Spec verification (and a new test specifically for nav-bar hiding).

## Notes
Choose: route flag (`data: { publicLayout: true }`) is the cleanest. The layout component checks the flag from the activated route.
