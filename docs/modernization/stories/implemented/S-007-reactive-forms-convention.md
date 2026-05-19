---
id: S-007
title: Reactive Forms convention + typed form helpers
epic: E-01
status: done
started_at: 2026-05-19
done_at: 2026-05-19
merged: true
merged_at: 2026-05-19
depends_on: [S-002, S-004]
acceptance:
  - A reference form (`ClubsEditPage` at `next/web/src/app/features/clubs/edit/`) demonstrates typed Reactive Forms (`FormGroup<{...}>`), control-level validation messages, an async validator, submit-disabled state.
  - Conventions live as one short section in `next/web/src/app/shared/ui/README.md` (NOT a long-form CONVENTIONS doc): validation lives in form definitions (not in components); error rendering is via the existing `<af-field-errors>` component (`next/web/src/app/shared/ui/molecules/af-field-errors/`).
  - The reference form integrates with the generated API client from S-004 — submitted values are typed against the generated DTO (`ClubCreateRequest` / `ClubUpdateRequest`).
  - The "edit" vs. "create" mode pattern (single component, two routes — `/clubs/new` + `/clubs/:id/edit`) is documented as one paragraph + a pointer to the live code, not duplicated as a template.
estimate: S
adr_refs: [0004]
parity_test: none
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements-engineer, solution-architect, qa-engineer]
context7_last_checked: 2026-05-19
github_issue: 74
github_pr: 75
---

## Context
Reactive Forms are the idiomatic Angular choice; every domain edit page in E-06..E-09 follows this pattern. The walking-skeleton already shipped a typed-FormGroup reference at `ClubsEditPage` (per S-048). This story formalises the convention, lands one async-validator example (slug uniqueness), and adds a short "forms" section to `shared/ui/README.md` that points at the live code instead of mirroring it.

## Acceptance criteria
See frontmatter.

<!-- modernize-refine: start -->

## Design notes

The reference form is `next/web/src/app/features/clubs/edit/clubs-edit.page.ts` (shipped by S-048). Convention prose lives in `next/web/src/app/shared/ui/README.md` § *Reactive Forms convention (S-007)*; the slug `slugAvailable` validator factory in `clubs-edit.validators.ts` is the in-memory async-style example.

### AC-DIR-3 / AC-DIR-4 / AC-DIR-5 — JIT-deferred (decision)

The 2026-05-15b amendment listed five directive ACs (AC-DIR-1..AC-DIR-5). AC-DIR-1 and AC-DIR-2 land here as convention. The remaining three are **deferred to first real consumer evidence** per ADR 0022 directive 1:

- **AC-DIR-3 (responsive form layout)** — the reference form is single-column on every viewport because it has 4 fields. The "stacked at `<md`, multi-column at `≥lg`" pattern needs a 10+ field form to be load-bearing; S-062c flight-edit is the first such consumer.
- **AC-DIR-4 (IndexedDB auto-save / draft restore)** — speculative infra. No current consumer has a long-fill form that bites. Lands with S-062c.
- **AC-DIR-5 (Ctrl+S / Ctrl+D / Esc / Enter keyboard contract)** — same call. Tab + Shift+Tab + Enter-submits work for free via native HTML + reactive-forms defaults; the Ctrl+S/D + Esc-prompt affordances pin in S-062c when there's a form that benefits.

A future implementer should NOT build IndexedDB draft infra against the 4-field `ClubsEditPage`.

### Load-bearing edge cases

- **Slug pattern + bounds** (`^[a-z0-9-]+$`, 3-64) mirror the server DTO via the generated `ClubCreateRequest` schema — the regex on the form must stay in lock-step with the server constraint; either change triggers an OpenAPI snapshot + codegen regen.
- **Validator key alignment**: client async validator and the server-409 mapping both write `{ duplicate: true }` so `<af-field-errors>` renders one message regardless of which path fires.

## Security plan

(N/A — convention doc + one in-memory client-side validator. Server 409 stays the authoritative duplicate gate.)

## Test plan

(See git history — the validator's vitest spec + the inline-validation Playwright extension in `clubs-crud.spec.ts` are the additions.)

## Performance plan

(N/A — pure-function validator over an in-memory entity list.)

<!-- modernize-refine: end -->
