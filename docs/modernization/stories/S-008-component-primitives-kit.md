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

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (see [`02-vision-and-constraints.md`](../02-vision-and-constraints.md) §C21–C24 + §F1–F16) sets the mobile-first + dense-desktop conventions that every primitive in this kit must encode. ADR 0017 (responsive breakpoint + density conventions) will be the cross-cutting decision; this story is where the conventions become real code.

**Layered acceptance criteria (additive — extend the baseline list, do not replace):**

- **AC-DIR-1 (breakpoint tokens in `@theme`).** Tailwind v4 design tokens include the FLS breakpoint set as CSS custom properties: `--breakpoint-sm: 360px; --breakpoint-md: 768px; --breakpoint-lg: 1024px; --breakpoint-xl: 1440px`. Every primitive's responsive behavior uses these (no ad-hoc media queries). (§2 NFR "responsive breakpoints".)
- **AC-DIR-2 (density tokens).** Two density modes encoded as `data-density="comfortable"` (default; mobile + tablet) and `data-density="dense"` (≥lg). Tokens for padding scale, font scale, and row-height vary by density attribute. The mode is selected by a `<fls-density-provider>` directive at the layout root, normally driven by viewport breakpoint via `@container` queries; can be overridden per subtree.
- **AC-DIR-3 (touch-target lint).** ESLint custom rule + axe-core check in Playwright: every interactive primitive renders a hit area ≥ 44 × 44 CSS px when ancestor `data-density="comfortable"`, ≥ 28 × 28 CSS px when `data-density="dense"` (icon-only secondary actions). Build fails on violation. (§2 NFR "touch targets".)
- **AC-DIR-4 (`<fls-data-table>` card-mode variant).** Below `--breakpoint-md`, `<fls-data-table>` renders as a stack of cards instead of rows (`mode="auto"` default; `mode="row"` / `mode="card"` forces). Column-to-card mapping is declarative — table consumers pass `[primary]`, `[secondary]`, `[meta]` slots. S-062b consumes this.
- **AC-DIR-5 (`<fls-autocomplete>` with recency-bias).** New primitive (or `<fls-select>` variant) supporting:
  - Searchable list with fuzzy-match across multiple fields.
  - "Recently used (last 7 days)" group at top of dropdown.
  - "Recently used" set sourced from a `RecentlyUsedService` (per-user, localStorage-backed, scoped by primitive-key like `aircraft` / `pilot` / `location`).
  - Mobile-first: full-width on `<md`, native-feeling inertia scroll, large hit targets per AC-DIR-3.
  - Replaces selectize from the legacy SPA (current-state §6) wholesale.
- **AC-DIR-6 (`<fls-time-now-button>`).** New primitive: button that sets a bound `<input type="time">` to the current minute rounded down. Used by S-062c on glider/tow start/landing time fields. Replaces the inline "Set Now" + format-on-blur dance from `FlightsController.js:716–736`.
- **AC-DIR-7 (`<fls-sticky-bar>`).** New primitive: layout slot for sticky action bars (typically save / cancel). Anchors to viewport bottom on `<md`, in-flow on `≥md`. Supports a "compact" variant for dense desktop. Used by S-062c, S-064, public-flow forms.
- **AC-DIR-8 (`<fls-accordion-section>`).** New primitive: accordion section with header, expand/collapse state managed via signal. Section auto-collapses (configurable) when fields inside become "complete". Used by S-062c on mobile to chunk the 35-field form. Same primitive used by reservations + planning where applicable.
- **AC-DIR-9 (native input types preferred).** `<fls-input>` and `<fls-form-field>` documentation explicitly recommends `type="time"`, `type="date"`, `inputmode="numeric"`, `autocomplete` hints over custom JS widgets. The CSS-only date / time pickers from a previous Tailwind kit are NOT introduced. (§F10 / soft pref §4 "native input types".)
- **AC-DIR-10 (Storybook / sample page parametrized by viewport).** A primitives showcase page exists under `next/web/src/app/dev/primitives/` that mounts every primitive at each breakpoint (sm / md / lg / xl) — reviewable visually before consumers consume. Playwright snapshot test runs at all four viewports.
- **AC-DIR-11 (a11y at both densities).** The existing a11y baseline applies to both `comfortable` and `dense` density modes. Keyboard focus ring is visible at both; in dense mode the ring is slightly thinner but still meets WCAG 2.4.7 (≥ 2 px or sufficient contrast).

**Refinement status flag:** Story is currently unrefined (`refined:` absent). When `/modernize-refine S-008` runs, fold these directive ACs into the primitive list + design tokens + a11y plan natively — do not preserve this amendment block as a separate section once refinement lands. Once refined, the original "Acceptance criteria" frontmatter list should grow to include AC-DIR-1..AC-DIR-11 as primary criteria, not appended ones.

<!-- amendment-2026-05-15b: end -->
