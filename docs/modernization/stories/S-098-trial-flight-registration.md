---
id: S-098
title: Trial-flight registration port
epic: E-12
status: todo
depends_on: [S-097, S-025]
acceptance:
  - Public form at `/trialflight` (no auth).
  - Submits to `POST /api/v1/trialflightregistrations` with the `club_slug` per S-025's tenant-from-URL mechanism.
  - Spec `01-public.spec.ts` and `09-public-flows.spec.ts` pass (trial-flight portion).
estimate: M
adr_refs: [0008]
parity_test: tests/public/01-public.spec.ts, tests/public/09-public-flows.spec.ts
---

## Context
Public flow. Validates the public-tenant mechanism in real use.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Backend controller (public-permitted under Spring Security).
- [ ] SPA form using the public layout.
- [ ] Spec verification.

## Notes
Send a confirmation email to the registrant (or to the club admin) — confirm legacy behavior.

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (C21 mobile-first whole-app, including public surfaces) requires:

- **AC-DIR-1 (mobile-first form layout).** Public form renders correctly at ≥ 360 × 640 portrait — single column, large touch targets, native input types (`type="date"`, `type="tel"`, `inputmode="numeric"` where applicable), sticky submit button below the keyboard.
- **AC-DIR-2 (no PII in URLs).** Form field values never appear in URL query string. `POST` only; success page navigation does not echo the registrant's email or name in the route.
- **AC-DIR-3 (marginal-connectivity tolerance).** At simulated 200 ms RTT + intermittent loss the submit either succeeds or surfaces a clear "please try again" message — no spinner-lock > 3 s, no silent failure.
- **AC-DIR-4 (touch-target compliance).** Submit button + form fields meet ≥ 44 × 44 CSS px on `<md`.

**Refinement status flag:** Story is unrefined. Fold the above into the AC list when `/modernize-refine S-098` runs.

<!-- amendment-2026-05-15b: end -->
