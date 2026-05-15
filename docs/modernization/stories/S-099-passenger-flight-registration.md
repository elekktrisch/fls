---
id: S-099
title: Passenger-flight registration port
epic: E-12
status: todo
depends_on: [S-097, S-025]
acceptance:
  - Public form at `/passengerflight` (no auth).
  - Submits to `POST /api/v1/passengerflightregistrations` with the `club_slug` per S-025.
  - Spec `09-public-flows.spec.ts` passes (passenger-flight portion).
estimate: M
adr_refs: [0008]
parity_test: tests/public/09-public-flows.spec.ts
---

## Context
Mirror of S-098 with different fields.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Backend controller.
- [ ] SPA form.
- [ ] Spec verification.

## Notes
Same email confirmation question as S-098 — confirm legacy behavior.

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Same directive as S-098 — public form, mobile-first:

- **AC-DIR-1 (mobile-first form layout).** Public form renders correctly at ≥ 360 × 640 portrait — single column, large touch targets, native input types where applicable, sticky submit below the keyboard.
- **AC-DIR-2 (no PII in URLs).** Form field values never appear in URL query string.
- **AC-DIR-3 (marginal-connectivity tolerance).** Submit either succeeds or surfaces a clear retry message — no spinner-lock > 3 s.
- **AC-DIR-4 (touch-target compliance).** Submit + fields ≥ 44 × 44 CSS px on `<md`.

**Refinement status flag:** Story is unrefined. Fold the above into the AC list when `/modernize-refine S-099` runs.

<!-- amendment-2026-05-15b: end -->
