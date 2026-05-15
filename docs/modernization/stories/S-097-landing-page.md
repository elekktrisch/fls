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

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (C21 mobile-first whole-app, including public surfaces) requires:

- **AC-DIR-1 (mobile-first landing + nav-bar).** The landing page renders correctly and usably at viewport ≥ 360 × 640 portrait. The nav-bar mechanism collapses to a hamburger / overflow menu at `<md`; on `≥md` it renders inline. Same component, breakpoint-driven layout per C22.
- **AC-DIR-2 (touch targets on landing CTAs).** Primary CTAs (trial-flight, passenger-flight, login) meet ≥ 44 × 44 CSS px hit area on `<md`. (§2 NFR "touch targets".)
- **AC-DIR-3 (whitelabel splash works at all breakpoints).** The per-club splash photo (C19) renders correctly and proportionally at every breakpoint — `object-fit: cover` with breakpoint-aware focal-point hints, not a fixed pixel size. Same for the per-club logo in the nav-bar.

**Refinement status flag:** Story is unrefined. Fold the above into the AC list when `/modernize-refine S-097` runs.

<!-- amendment-2026-05-15b: end -->
