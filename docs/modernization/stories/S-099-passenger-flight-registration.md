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
