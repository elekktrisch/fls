---
id: S-007
title: Reactive Forms convention + typed form helpers
epic: E-01
status: todo
depends_on: [S-002, S-004]
acceptance:
  - A reference form (e.g. `HelloEditForm`) demonstrates typed Reactive Forms (`FormGroup<{...}>`), control-level validation messages, async validators, submit-disabled state.
  - Conventions are documented: validation lives in form definitions (not in components); error rendering is via a reusable `<fls-field-errors>` component.
  - The reference form integrates with the generated API client from S-004 — submitted values are typed against the generated DTO.
  - A pattern for "edit" vs. "create" modes (single form, two routes) is documented.
estimate: S
adr_refs: [0004]
parity_test: none
---

## Context
Reactive Forms are the idiomatic Angular choice and match what legacy AngularJS tries to do. Every domain story in E-06/E-07/E-08/E-09 follows this pattern.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Build typed `FormGroup<HelloDto>` from generated types.
- [ ] Build `<fls-field-errors>` reusable error renderer.
- [ ] Document the validation patterns: required, min/max, custom sync, custom async.
- [ ] Document edit-vs-create handling (router-resolver-driven initial value vs. fresh blank state).

## Notes
Pair with S-008 for input components.
