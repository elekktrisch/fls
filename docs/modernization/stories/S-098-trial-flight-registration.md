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
