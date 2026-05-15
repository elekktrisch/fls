---
id: S-064
title: Air movements (motor aircraft) UI + endpoint parity
epic: E-07
status: todo
depends_on: [S-062a, S-062c]
acceptance:
  - Motor-aircraft flight UI lives at `/airmovements` (parity with legacy `flsweb/src/flights/airmovements/`).
  - Backend uses the same Flight controller endpoints (no separate controller — same entity).
  - Spec `07-airmovements-crud.spec.ts` passes.
estimate: M
adr_refs: [0005, 0008]
parity_test: tests/flights/07-airmovements-crud.spec.ts
---

## Context
Legacy has near-identical `airmovements/` and `flights/` AngularJS modules. New SPA should not duplicate — share the form/list components, differ only by `FlightAircraftType` filter.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Decide on UI sharing strategy (recommend: one parameterized list/form, two routes pointing at it with different filters).
- [ ] Implement.
- [ ] Spec verification.

## Notes
Avoid the legacy's full-copy duplication.
