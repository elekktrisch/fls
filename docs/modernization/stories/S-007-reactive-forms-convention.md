---
id: S-007
title: Reactive Forms convention + typed form helpers
epic: E-01
status: in_progress
started_at: 2026-05-19
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
---

## Context
Reactive Forms are the idiomatic Angular choice; every domain edit page in E-06..E-09 follows this pattern. The walking-skeleton already shipped a typed-FormGroup reference at `ClubsEditPage` (per S-048). This story formalises the convention, lands one async-validator example (slug uniqueness), and adds a short "forms" section to `shared/ui/README.md` that points at the live code instead of mirroring it.

## Acceptance criteria
See frontmatter.

<!-- modernize-refine: start -->

## Design notes

### Reference form lives at `ClubsEditPage`

`next/web/src/app/features/clubs/edit/clubs-edit.page.ts` is the canonical reference. It already satisfies frontmatter AC1-AC4:

- Typed `FormGroup<{ name; slug; clubKey; publicRegistrationEnabled }>` over `FormBuilder` with `nonNullable.control` per field (no `null | undefined` surprises in `getRawValue()`).
- Sync validators on the control: `Validators.required`, `Validators.maxLength`, `Validators.pattern`.
- Submitted values typed against generated DTOs (`ClubCreateRequest` / `ClubUpdateRequest`).
- Edit vs. create: single component, two routes (`/clubs/new` + `/clubs/:id/edit`); `isCreate()` computed signal derives mode from `paramMap`; `clubKey` field is hidden on edit (the field is immutable post-create).
- `[disabled]="form.invalid || saveSubmitted()"` on the submit button.

This story adds **one async validator** to make the AC1 "async validators" example explicit + reusable: a `slugAvailable(store)` validator that probes the loaded `ClubsStore` entity list for an existing slug, returning `{ duplicate: true }` on the control. This is a *client-side pre-check*; the authoritative duplicate signal stays the server's 409 (already mapped onto the same `{ duplicate: true }` errors object inside the `saveError` effect — error key chosen so both paths surface identically via `<af-field-errors>`).

### Convention (one section in `shared/ui/README.md`)

Short prose + pointer to `ClubsEditPage`. Covers:

1. **Validation lives in the form definition**, never in the component template or submit handler. `Validators.required`, `Validators.pattern`, etc. attach at `FormBuilder.group({...})` time. Custom validators are pure factory functions in a sibling `*.validators.ts` file when reused; inline when one-off.
2. **Error rendering uses `<af-field-errors>`** (via `<af-form-field [errors]="ctl.errors">`). The component maps validator-key → translation-key (`required` → `common.errors.required`); unknown keys fall through to `common.errors.<key>`. New custom validators just register a new error key — no template churn.
3. **Inline (per-keystroke) validation by default** (AC-DIR-1). Default `updateOn: 'change'` for sync validators. `updateOn: 'blur'` is reserved for **async** validators per Angular's perf guidance — they hit network on every keystroke otherwise. The legacy "top-level `MessageManager` error bar" pattern is not carried forward; errors render next to the offending control.
4. **Submit-disabled state**: `[disabled]="form.invalid || saveInFlight()"`. `markAllAsTouched()` on submit-of-invalid so error tips render even if the user never blurred a field.
5. **Native input types preferred** (AC-DIR-2). `type="time" | "date" | "email" | "tel"`, `inputmode="numeric"`, `autocomplete=` hints. Pre-existing in `shared/ui/README.md` "Native input types" section — link, do not duplicate.
6. **Edit vs. create — single component, two routes**: route param presence is the mode discriminator (`isCreate = computed(() => routeId().get('id') === null)`). On `create` the form binds to a fresh `FormBuilder.group(...)`. On `edit` an `effect()` reads the entity from the feature store and `patchValue()`s. Immutable-post-create fields (`clubKey`) are `disable({ emitEvent: false })`d in the same effect.
7. **Server-side per-field errors**: on save failure, the store sets a `saveError` signal; the page's effect inspects it and maps known shapes onto `setErrors({ duplicate: true })` on the right control, matching the same error key the async validator would have surfaced. Generic / unknown save errors render at the top via the `saveError` line — distinguished from field validation visually.

### AC-DIR-3 / AC-DIR-4 / AC-DIR-5 — JIT-deferred (decision)

The 2026-05-15b amendment listed five directive ACs (AC-DIR-1..AC-DIR-5). AC-DIR-1 and AC-DIR-2 land here as convention. The remaining three are **deferred to first real consumer evidence**, in line with ADR 0022 directive 1 (working software over speculative documentation):

- **AC-DIR-3 (responsive form layout)** — the reference form is single-column on every viewport because it has 4 fields. The "stacked at `<md`, multi-column at `≥lg`" pattern needs a 10+ field form to be load-bearing; S-062c flight-edit is the first such consumer. Decided there with real code, not pre-specified here.
- **AC-DIR-4 (IndexedDB auto-save / draft restore)** — speculative infra. No current consumer has a long-fill form that bites. The pattern lands when S-062c's flight-edit (legacy carrying 30+ fields, dirty over minutes) actually needs it. Building it now without that data shape = building it twice. Surface in S-062c's refinement.
- **AC-DIR-5 (Ctrl+S / Ctrl+D / Esc / Enter keyboard contract)** — same call. Tab + Shift+Tab + visible focus + Enter-submits already work for free via native HTML semantics + the reactive-forms default. Ctrl+S / Ctrl+D / Esc-prompt are flight-edit affordances; pin in S-062c when there's a real form that benefits.

This decision is the load-bearing one in the refinement — written down so a future implementer doesn't try to build IndexedDB draft infra against the 4-field `ClubsEditPage`.

### Integration with other stories

| Story | Contract |
|---|---|
| S-008 (UI kit) | `<af-form-field>`, `<af-field-errors>`, `<af-input>` — already shipped. S-007 consumes; no new primitive. |
| S-048 (Clubs CRUD) | Owns `ClubsEditPage` + `ClubsStore`. S-007 layers one `slugAvailable` async validator + a short e2e for the validation paths. |
| S-049+ (further CRUD pages) | Inherit the convention; the README section is what they read. |
| S-062c (Flight edit) | Owns AC-DIR-3 / AC-DIR-4 / AC-DIR-5 if/when needed. Refinement flag forward. |

## Edge cases & hidden requirements

- **Async validator runs against the in-memory store, not the network.** `ClubsStore` is already loaded by `onInit`'s `loadAll()`; the validator filters entities by slug. No new HTTP. Trade-off: on first paint before the load completes, the validator returns `null` (no duplicate detection) — the server 409 still catches it. Documented in the validator file's doc comment.
- **Async validator excludes the currently-edited entity.** Edit mode must NOT flag the current row's own slug as duplicate. Validator factory takes `currentId: () => string | null` and skips that entity.
- **Validator key alignment**: server-409 path and async-validator path both set `{ duplicate: true }` so `<af-field-errors>` renders one consistent message. `field-errors.ts` already maps `duplicate` → `common.errors.duplicate`.
- **`patchValue` does not reset `disabled` state** — when navigating from edit to new in the same component instance, the `effect()` re-runs and must re-enable `clubKey`. Verified by the e2e (covers nav from `/clubs/:id/edit` → `/clubs/new`).
- **`getRawValue()` over `value`** — `value` skips disabled controls; create-mode submit needs `clubKey` always. The existing code uses `getRawValue()`; convention pins it.
- **Slug pattern (`^[a-z0-9-]+$`) and length bounds (3-64)** must mirror the server DTO; sourced from `ClubCreateRequest.slug.pattern` in the generated OpenAPI types (compile-time link, not a copy-pasted constant). Slugs ≥ 65 chars never round-trip server-side; UI pattern is the same regex.

## Security plan

(N/A — convention doc + one client-side async validator. The validator hits no network. Server is the authoritative validator: AC pins the 409 path as the authoritative duplicate signal; the async pre-check is a UX nicety, not a security boundary.)

## Test plan

### What's already covered

The walking-skeleton e2e `next/web/e2e/tests/clubs/clubs-crud.spec.ts` already exercises happy-path create / edit / 409-duplicate. Test names:

- `clubs: lists the seeded row at /clubs`
- `clubs: editing the seeded row updates the list`
- `clubs: creating a new club appears in the list`
- `clubs: 409 on duplicate slug surfaces as a save error`

### What this story adds

- **Vitest:** one spec file for the new `slugAvailable` validator — covers (a) returns null on empty input, (b) returns null when the slug is unique, (c) returns `{ duplicate: true }` when the slug matches another entity, (d) excludes the currently-edited entity.
- **Playwright:** extend `clubs-crud.spec.ts` with one assertion that covers the inline-validation contract end-to-end: open `/clubs/new`, type an invalid slug ("AB" — too short), observe the field error renders inline (visible before submit-click).

Per `next/web/CLAUDE.md` §8: no `*.component.spec.ts` with DOM assertions; the validator is pure-function, vitest-tested; the inline-validation UX assertion is Playwright.

## Performance plan

(N/A — convention + tiny pure-function validator. No HTTP. Async-validator perf rule (`updateOn: 'blur'`) is the *recommendation* for cross-network validators; this story's validator runs in-memory and uses default `change` updateOn — documented in the README section.)

<!-- modernize-refine: end -->
