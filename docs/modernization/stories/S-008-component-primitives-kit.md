---
id: S-008
title: Component primitives kit + Tailwind design tokens
epic: E-01
status: todo
depends_on: [S-002]
acceptance:
  - Component primitives approach is decided: Tailwind + Angular CDK (headless, recommended), Spartan UI, Angular Material, or PrimeNG.
  - A baseline UI kit exists under `next/web/src/app/ui/` with: `<fls-button>`, `<fls-input>`, `<fls-select>`, `<fls-dialog>`, `<fls-data-table>`, `<fls-date-picker>`.
  - Tailwind design tokens (colors, spacing scale, type scale) are defined in `tailwind.config.js`; documented in `next/web/src/app/ui/README.md`.
  - The reference form from S-007 uses kit components only.
estimate: M
adr_refs: [0004]
parity_test: none
---

## Context
ADR 0004 deferred the component-primitives choice to phase 4. Tailwind + Angular CDK is the recommended path: lowest commitment, headless behavior + utility-first styles, easy to swap pieces.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Decide: Tailwind + Angular CDK (recommended). Document in `next/web/src/app/ui/README.md`.
- [ ] Build the 6 baseline components above. Keep API surface small; we'll grow it as needed.
- [ ] Configure Tailwind design tokens to a sane FLS baseline (slate-on-white, accent on blue).
- [ ] Integrate `<fls-data-table>` with NgRx Signal Store's `withEntities` for sortable/paginated lists (the legacy `ng-table` is the spec to match feature-wise).

## Notes
The legacy app leans on `ng-table` + `selectize` + `pikaday`. Matching their feature surface (paged, sortable, filterable tables; tag-style selects; date picker) is the bar.
