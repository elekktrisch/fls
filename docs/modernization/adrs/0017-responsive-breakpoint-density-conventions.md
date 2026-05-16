# 0017 — Responsive breakpoint + density conventions

- **Status:** Accepted
- **Date:** 2026-05-16
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)):
  1. Off-EOL & long-supported
  2. Team-familiar stack
  7. Solo-operator operability
  12. Supports the C17 end-user improvements within the chosen stack *(2026-05-15a)*
  13. Supports a single-component responsive mobile-first model with a dense-desktop variant *(2026-05-15b)*

## Context

The 2026-05-15b amendment locked the UX direction: every screen is designed mobile-first (start at 360 × 640 portrait, progressively enhanced to tablet → desktop → dense-desktop). The flight-edit form and flight-list are designated airfield hot-path screens ([C23](../02-vision-and-constraints.md#3-hard-constraints)) and MUST collapse to a dense, low-padding, keyboard-first layout at ≥ lg breakpoint — single responsive component, **CSS-driven density** ([C22](../02-vision-and-constraints.md#3-hard-constraints)), no parallel mobile/desktop component trees.

Frontend stack is already pinned: Angular 21 + Tailwind 4 ([ADR 0004](0004-frontend-framework-and-build-tool.md)) + NgRx Signal Store ([ADR 0006](0006-frontend-state-management.md)). This ADR picks the cross-cutting *convention* — breakpoint set, layout grid, density-toggle mechanism, touch-target sizing — so every primitive in the component kit (S-008), every form (S-007), and every screen inherits one decision instead of re-litigating per story.

## Options considered

### Option A — Tailwind 4 responsive variants + container queries + CSS variables

- **Capabilities:** Tailwind utility classes with `sm:` / `md:` / `lg:` / `xl:` breakpoint variants for screen-level layout. `@container` queries for component-local density (a `<fls-flight-form>` shown inside a sidebar can use the compact variant even on a desktop viewport). CSS variables for the whitelabel primary color ([ADR 0014](0014-per-tenant-theming.md)) and the density-toggle (single class flips a CSS variable that propagates to padding / font-size / gap utilities). Breakpoint values override Tailwind defaults: `sm=360 / md=768 / lg=1024 / xl=1440` per [vision §2](../02-vision-and-constraints.md#2-non-functional-requirements). Touch-target rule encoded as utility class: `min-h-[44px] @lg:min-h-[28px]` for icon-only secondary actions on dense-desktop.
- **Fit to criteria:** Criterion 13 ✓ (Tailwind 4 + Angular 21 make CSS-driven density the cheap path; container queries are first-class). Criterion 12 ✓ (CSS variables are the whitelabel pattern from ADR 0014 — same mechanism for density-toggle). Criterion 7 ✓ (zero JS for layout; no observer subscriptions; no rxjs graph). Criterion 1 ✓ (Tailwind 4 + container queries supported in evergreen browsers; Angular 21 lifecycle through 2027+).
- **Migration cost:** low. The convention is captured in `next/web/tailwind.config.ts` (breakpoint overrides + plugin for container queries) + a CONVENTIONS.md section in `next/web/`. Primitives kit (S-008) inherits via utility classes; no new abstractions.
- **Ecosystem risk:** low. Tailwind 4 is the current major; container queries are Baseline 2023 (Chrome 105+, Safari 16+, Firefox 110+). Angular 21 doesn't fight this — components are template-driven.
- **Escape hatch:** every Tailwind class is a CSS rule under the hood. Swapping to plain CSS or another utility framework is mechanical. Container queries are CSS-spec-level, not framework-bound.

### Option B — CSS variables + Angular Signal-driven viewport detection

- **Capabilities:** A `viewport()` signal in Angular exposes `{ isMobile, isTablet, isDesktop, isDense }` derived from `window.matchMedia`. Components read the signal and switch templates / classes accordingly.
- **Fit to criteria:** Criterion 13 ✗ — encourages template branching (`@if (viewport().isMobile)`) which is exactly the parallel-tree pattern C22 forbids. Criterion 7 ~ (JS-driven; needs SSR coordination for the initial render).
- **Migration cost:** medium — every screen wires the signal; reviewers must catch parallel-tree drift.
- **Why not chosen:** structurally invites the wrong pattern. Better as a tool for content-dependent logic (e.g. "load 10 rows vs 50 rows" — not "different layouts").

### Option C — Angular CDK `BreakpointObserver` + per-component layout switches

- **Capabilities:** Angular's official RxJS-based breakpoint observable + `Breakpoints.HandsetPortrait` / etc constants.
- **Fit to criteria:** Criterion 13 ✗ (same parallel-tree risk as Option B). Criterion 7 ✗ (couples layout to RxJS graph; doesn't match Signal Store direction from ADR 0006).
- **Why not chosen:** heavier abstraction than the problem requires; pulls in CDK as a layout dependency.

## Decision

Chosen: **Option A — Tailwind 4 responsive variants + container queries + CSS variables**. Decision driven by criterion 13 (CSS-driven density is the path of least resistance; parallel component trees are forbidden by C22), criterion 12 (CSS-variable mechanism reuses the whitelabel pattern from ADR 0014), and criterion 7 (zero JS for layout; nothing to debug at runtime). Container queries handle the "same component in different containers" case (sidebar vs main; embedded vs full-page) without coupling to viewport size, which is the right abstraction for the flight-edit form inside a tablet/laptop split-screen workflow.

## Consequences

- **Positive:**
  - One convention locked: every screen, every primitive, every form uses the same breakpoint set + density-toggle mechanism. Reviewers have a single concept to enforce.
  - Single responsive component per screen ([C22](../02-vision-and-constraints.md#3-hard-constraints)): same business logic, same form-state store, two layouts via utility classes.
  - Touch-target enforcement encodable as ESLint / Tailwind plugin rule ("`button` without `min-h-[44px]` on `<lg` is an error").
  - Reuses the [ADR 0014](0014-per-tenant-theming.md) CSS-variable mechanism for the density-toggle — one runtime injection pattern instead of two.
  - SSR-safe: utility-driven layouts render correctly on the server without a viewport guess.
  - Storybook (S-008 primitives kit) shows each primitive at all 4 breakpoints with no extra wiring.

- **Negative:**
  - Tailwind's utility-density on the template can be visually noisy. Mitigation: extract per-breakpoint variants into `@apply` directives or component-level classes once a pattern is repeated 3+ times.
  - Touch-target rule enforcement depends on a lint convention; without the rule, density mistakes slip through. Listed as a follow-up story.
  - Container queries require Tailwind's `@tailwindcss/container-queries` plugin enabled in `tailwind.config.ts` — small operational dependency.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** establish `next/web/tailwind.config.ts` with the overridden breakpoint values + container-queries plugin + the density-toggle CSS variable. Inherited by S-007 (forms) + S-008 (primitives).
  - **Story:** ESLint / Tailwind plugin rule enforcing touch-target sizing convention (≥44px on `<lg`, ≥28px on `≥lg` for icon-only secondary).
  - **Story (S-008):** primitives kit ships each primitive at all 4 breakpoints with Storybook stories; the kit's CONVENTIONS doc cites this ADR.
  - **Story (S-007):** forms convention ships the dense-desktop grid + sticky-save-bar mobile layout as paired reference forms in Storybook.
  - **Story (S-062b/c):** flight-list + flight-edit consume this convention; their refinement already cites the C21-C24 invariants but they will now also cite this ADR explicitly.
  - **Story (S-109):** Playwright projects parametrized by viewport (`mobile=375x667`, `tablet=768x1024`, `desktop=1280x800`, `desktop-dense=1920x1080`); existing T3 smoke (S-110) extended to run at all four.
  - **Story:** decide where the density-toggle CSS variable lives (route-level vs app-level vs per-component) when a non-flight-edit/list screen wants to opt in. Defer until the second screen wants it; YAGNI until then.
