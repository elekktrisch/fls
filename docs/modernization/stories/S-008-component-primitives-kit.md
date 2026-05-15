---
id: S-008
title: Component primitives kit + Tailwind design tokens
epic: E-01
status: todo
depends_on: [S-002]
acceptance:
  - Component primitives approach is decided: Tailwind + Angular CDK (headless, recommended), Spartan UI, Angular Material, or PrimeNG.
  - A baseline UI kit exists under `next/web/src/app/shared/ui/` organised by atomic-design taxonomy (atoms / molecules / organisms — see `next/web/CLAUDE.md` §1).
    - **Atoms:** `<fls-button>`, `<fls-input>`, `<fls-select>`, `<fls-icon>`, `<fls-badge>`.
    - **Molecules:** `<fls-form-field>` (label + input + error wiring), `<fls-search-input>`, `<fls-field-errors>` (consumed by S-007).
    - **Organisms:** `<fls-data-table>`, `<fls-dialog>`, `<fls-date-picker>`, `<fls-nav-bar>`.
  - Tailwind v4 design tokens (colors, spacing scale, type scale) are defined in `src/styles.css` inside the `@theme { ... }` block (CSS custom properties — `--color-brand-500: oklch(...)`); documented in `next/web/src/app/shared/ui/README.md`. No ad-hoc colours / sizes outside the token set. **No `tailwind.config.js`** — v4 is CSS-first.
  - Layering is enforced by ESLint `no-restricted-imports`: atoms cannot import molecules/organisms; molecules cannot import organisms.
  - The reference form from S-007 uses kit components only.
  - A11y baseline per `next/web/CLAUDE.md` §5 holds for every primitive: visible focus ring, keyboard reachable, accessible name, and CDK `FocusTrap`/`Overlay` for dialog + date-picker.
estimate: M
adr_refs: [0004]
parity_test: none
---

## Context
ADR 0004 deferred the component-primitives choice to phase 4. Tailwind + Angular CDK is the recommended path: lowest commitment, headless behavior + utility-first styles, easy to swap pieces. Atomic-design taxonomy + folder layout were pre-staged in S-002 (see `next/web/CLAUDE.md` §1) — this story fills the empty `atoms/`, `molecules/`, `organisms/` folders.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Decide: Tailwind + Angular CDK (recommended). Document in `next/web/src/app/shared/ui/README.md`.
- [ ] Build atoms: `<fls-button>`, `<fls-input>`, `<fls-select>`, `<fls-icon>`, `<fls-badge>`. Each in its own folder under `shared/ui/atoms/<name>/` with a barrel `index.ts`.
- [ ] Build molecules: `<fls-form-field>`, `<fls-search-input>`, `<fls-field-errors>`. Compose from atoms only.
- [ ] Build organisms: `<fls-data-table>`, `<fls-dialog>`, `<fls-date-picker>`, `<fls-nav-bar>`. CDK-backed where applicable.
- [ ] Configure Tailwind v4 design tokens to a sane FLS baseline (slate-on-white, accent on blue) inside `src/styles.css` `@theme { ... }` block. Use OKLCH colours. No bare hex values in component templates.
- [ ] Wire ESLint `no-restricted-imports` to enforce the atoms < molecules < organisms layering.
- [ ] Integrate `<fls-data-table>` with NgRx Signal Store's `withEntities` for sortable/paginated lists (the legacy `ng-table` is the spec to match feature-wise).
- [ ] Verify a11y per `CLAUDE.md` §5: every interactive primitive has accessible name, keyboard reachability, visible focus state. Dialog + date-picker use CDK `FocusTrap` + `Overlay`.

## Notes
The legacy app leans on `ng-table` + `selectize` + `pikaday`. Matching their feature surface (paged, sortable, filterable tables; tag-style selects; date picker) is the bar.

Keep each primitive's public API small. Extend on demand from feature stories — over-spec'd primitives are a known trap (legacy R10 was effectively this).
