---
id: S-007
title: Reactive Forms convention + typed form helpers
epic: E-01
status: todo
depends_on: [S-002, S-004]
acceptance:
  - A reference form (e.g. `HelloEditForm`) demonstrates typed Reactive Forms (`FormGroup<{...}>`), control-level validation messages, async validators, submit-disabled state.
  - Conventions are documented: validation lives in form definitions (not in components); error rendering is via a reusable `<fls-field-errors>` component (lives at `next/web/src/app/shared/ui/molecules/field-errors/` per atomic-design layout in `next/web/CLAUDE.md` §1).
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

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (§F9, §F10) sets two form-level conventions that this story is the right place to land:

- **AC-DIR-1 (inline validation, not on-blur).** The reference form demonstrates inline validation: error messages render next to the offending field (via `<fls-field-errors>` from S-008) and update as the user types / moves focus. The legacy "validate-on-blur + surface errors in a top-level `MessageManager` bar" pattern is **not** carried forward. The convention doc covers: (a) how to wire control-level validators eagerly; (b) how to surface server-side `ValidationErrorDto` per-field; (c) how to distinguish "validation error" (per-field) from "save error" (toast).
- **AC-DIR-2 (native input types preferred).** Convention doc recommends `type="time"`, `type="date"`, `inputmode="numeric"`, `type="email"`, `type="tel"`, and `autocomplete` hints over custom JS widgets. Examples cover the gotchas: storing a `Date` in a form control while binding to a `type="date"` string input; locale formatting for date pickers; the difference between `type="time"` `value` ("HH:mm") and a `Date` object. Closes the legacy "text-with-format-on-blur" pattern from `FlightsController.js`.
- **AC-DIR-3 (mobile-first form layout convention).** Convention doc covers the responsive-form pattern: stacked labels + single column at `<md`; inline labels + multi-column at `≥lg`. Form layout is CSS-driven on the component, not branched in TypeScript. Example shows a `<fls-form-field>` rendering correctly across all breakpoints without per-breakpoint template variants.
- **AC-DIR-4 (auto-save / IndexedDB draft convention).** Convention doc covers the pattern from S-062c AC-DIR-9: debounced auto-save to IndexedDB; restore on reload with a "continue from draft / start fresh" prompt; clear draft on successful save. The reference form demonstrates this against the hello endpoint.
- **AC-DIR-5 (keyboard-only completion convention).** Convention doc lists the standard keyboard contract for forms in the new app: Tab / Shift+Tab traverses controls in source order; Enter inside a single-line input submits the form (unless the form opted out); Esc on a dirty form prompts before discarding; Ctrl+S = save (debounced); Ctrl+D = save + copy where applicable. Stories that adopt the convention inherit the contract.

**Refinement status flag:** Story is currently unrefined. When `/modernize-refine S-007` runs, fold these directive ACs into the convention doc + reference-form ACs natively — do not preserve this amendment block as a separate section once refinement lands.

**Inputs picked up from sibling stories:**

- S-008 — `<fls-field-errors>`, `<fls-form-field>`, native-input primitives.

<!-- amendment-2026-05-15b: end -->
