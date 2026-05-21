---
id: S-097
title: Landing page port + nav-bar mechanism (closes R12)
epic: E-12
status: in_progress
started_at: 2026-05-21
depends_on: [S-002, S-008]
acceptance:
  - `/` (the public landing) renders with the legacy content.
  - Nav-bar visibility is controlled by a route flag (`data: { publicLayout: true }`) or a layout slot — *not* by a boolean expression in code.
  - A test asserts that the nav-bar is hidden on `/`, `/discovery-flight`, `/scenic-flight` (closes R12).
  - Page is reachable without authentication.
estimate: S
adr_refs: [0004]
parity_test: tests/public/landing.spec.ts
refined: true
refined_at: 2026-05-21
refined_specialists: [requirements-engineer, solution-architect, qa-engineer]
github_issue: 96
---

## Context
R12 (the `||` tautology bug) is a vibe-level bug — replace the broken mechanism with a real one.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Implement the public layout pattern (Angular route data + layout component).
- [ ] Port the landing page content.
- [ ] Spec verification (and a new test specifically for nav-bar hiding).

## Notes
Choose: route flag (`data: { publicLayout: true }`) is the cleanest. The layout component checks the flag from the activated route.

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (C21 mobile-first whole-app, including public surfaces) requires:

- **AC-DIR-1 (mobile-first landing + nav-bar).** The landing page renders correctly and usably at viewport ≥ 360 × 640 portrait. The nav-bar mechanism collapses to a hamburger / overflow menu at `<md`; on `≥md` it renders inline. Same component, breakpoint-driven layout per C22.
- **AC-DIR-2 (touch targets on landing CTAs).** Primary CTAs (trial-flight, passenger-flight, login) meet ≥ 44 × 44 CSS px hit area on `<md`. (§2 NFR "touch targets".)
- **AC-DIR-3 (whitelabel splash works at all breakpoints).** The per-club splash photo (C19) renders correctly and proportionally at every breakpoint — `object-fit: cover` with breakpoint-aware focal-point hints, not a fixed pixel size. Same for the per-club logo in the nav-bar.

**Refinement status flag:** Story is unrefined. Fold the above into the AC list when `/modernize-refine S-097` runs.

<!-- amendment-2026-05-15b: end -->

<!-- amendment-2026-05-21a: start -->

## Amendment 2026-05-21a — Post-auth language picker

The `<af-lang-picker>` molecule shipped by S-005 is wired only on the public `/landing` page. Operators logged into the post-auth shell currently have no way to switch language — the cold-start order (`?lang=` → `navigator.language` → `de`) is the only knob, and reloading with a query param is not a discoverable affordance. Reported 2026-05-21.

- **AC-DIR-4 (post-auth language picker).** The nav-bar / top-bar (the same chrome S-097 controls via `data: { publicLayout: true }`) renders `<af-lang-picker>` for authenticated users. The molecule already drives `LocaleService.set()`, which is the single switch for transloco + ng-zorro + `<html lang>` — no new wiring needed beyond placement. Visible at every breakpoint per AC-DIR-1 (collapses into the hamburger / overflow menu at `<md`).
- **AC-DIR-5 (picker affords the public side too).** The public landing's existing inline picker stays (`landing.component.ts:64`); the nav-bar picker is additive for the post-auth case. If S-097's nav-bar shows on `/landing` (currently hidden by `publicLayout`), the inline picker is the source of truth and the nav-bar one is suppressed — single picker per surface.

**Refinement status flag:** still unrefined. Fold both AC-DIRs into the canonical AC list when `/modernize-refine S-097` runs. No new dependency edges — S-097 already `depends_on: [S-002, S-008]`, both of which transitively ship `LocaleService` + `AfLangPickerComponent`.

<!-- amendment-2026-05-21a: end -->

<!-- modernize-refine: start -->

## Design reference

Implementer fetches the externally-supplied design bundle before coding:

- **URL:** `https://api.anthropic.com/v1/design/h/Dauu4bAkrdM-8MphyTKaHg?open_file=AlpenFlight.html`
- **Procedure:** fetch the bundle, read its `README` first for context + asset inventory, then implement the relevant surfaces from `AlpenFlight.html` (the landing-page reference). Map the design to existing primitives (`<af-button>`, `<af-icon>`, `<af-lang-picker>`, Tailwind v4 tokens) — do **not** introduce new atoms or fork the design-system tokens to match pixel-perfect deltas (per `alpenflight/web/CLAUDE.md` §3 + ADR 0024). If the design needs a token that doesn't exist in `@theme`, promote it as a one-line addition to `src/styles.css` rather than inline arbitrary values.
- **Scope:** landing (`<af-landing>`) is the in-scope surface. The `<af-nav-bar>` chrome shown in the design reference may inform AC-DIR-4 picker placement; chrome work outside that AC stays out of scope.

## Design notes

Schema: (none — frontend-only story). DTOs / API: (none — only the existing OIDC redirect with `ui_locales`).

### Mechanism
Route-data flag `data: { showNavBar: false, publicAccess: true }` is the existing convention (`app.component.ts:69-82`, default-deny via `session.guard.ts`). New `/discovery-flight` (port of legacy `/tryflight` — Schnupperflug) and `/scenic-flight` (port of legacy `/passengerflight` — Mitflug/Passagierflug) register at the top level with the same shape as `landing`. No new mechanism, no guard changes. The rename to industry-standard English nouns is intentional — legacy URLs are not preserved.

### Picker placement (AC-DIR-4)
Replace the user-menu dropdown's inline 4-button language section in `<af-nav-bar>` with `<af-lang-picker>`. The molecule already owns the layout + `LocaleService` wiring; the menu currently hand-rolls the same surface with a `(localeChange)` output. One picker shape, one source of truth. The mobile drawer keeps the user-menu inline below md — no separate placement decision needed. Rejected: rendering the molecule directly in the bar above md (costs horizontal real estate, duplicates the dropdown affordance).

### Single picker per surface (AC-DIR-5)
Landing has `showNavBar: false`, so `<af-nav-bar>` is not in the DOM on `/`. The "two pickers visible at once" conflict is structurally impossible today — no suppression logic to write; a one-line comment near the picker block citing AC-DIR-5 + the route flag is sufficient. Future authenticated surfaces that want to embed an inline picker get that decision in their own story.

### Whitelabel splash (AC-DIR-3)
Ship a passthrough placeholder slot — single `<af-landing-splash>` component on landing rendering the AlpenFlight default image with `object-fit: cover` + a `object-position` class hook for future per-image focal points. No `WhitelabelService`, no per-club lookup, no API — public landing has no tenant context, and the whitelabel store is a future story. The slot's typed input lets a future story swap defaults for per-club assets without re-shaping the landing.

### Touch targets (AC-DIR-2)
`<af-button>` from the S-008 kit already ships `min-h-11` (44 CSS px). Sign-in CTA, try-demo CTA, and the inline lang-picker buttons inherit it. No per-call class additions required — verify in test rather than re-encode in template.

### Public-flow stubs
`/discovery-flight` + `/scenic-flight` ship as **minimal placeholder components** ("Coming soon" copy + back-to-landing link) — porting the legacy public booking forms is out of scope for S-097 and belongs in its own story. Stubs exist only so the routes resolve with `showNavBar: false` and the nav-bar-hiding test has targets.

## Edge cases & hidden requirements

- **R12 regression guard:** the structural fix is done — the regression risk is a developer omitting `showNavBar` on a future public route. Default is `false`, so omission is safe; the test should fail loudly if a public route ever sets `true`, not just verify the current state.
- **Authenticated landing:** deep-link to `/` while authenticated keeps current behavior (landing renders anyway). Swapping the Sign-in CTA to "Continue as …" is a future-story concern, not S-097.
- **`tryDemo` CTA:** stubbed TODO targeting vision §8 demo-mode (separate track). This story leaves the existing stub in place — wires no real route — and does not hide the CTA.
- **CTA failure modes:** Sign-in CTA triggers a redirect. No spinner / disabled state required; if `OidcSecurityService.authorize()` rejects synchronously, surface a non-blocking error toast and keep the button live.
- **Splash focal-point default:** without a per-image hint store, hardcode `object-position: center 35%` (sky-biased) on the default; per-image override is a future-story concern.
- **SSR / no-JS:** zoneless Angular is client-rendered. Out of scope; SEO meta-tags via `Title`/`Meta` are sufficient for landing.

## Test plan

Layer rule per `alpenflight/web/CLAUDE.md` §8: all DOM / routing / viewport / touch-target assertions go Playwright. Unit (Vitest) is reserved for any pure helper if extracted (none expected here).

- **AC original (nav-bar visibility by route):** new `e2e/tests/nav-bar.spec.ts`, parametrized over the route set (`/`, `/discovery-flight`, `/scenic-flight` ⇒ hidden; `/clubs` ⇒ visible). Locator presence, not signal reads.
- **AC-DIR-1 (responsive 360×640 + hamburger collapse `<md`):** Playwright in `nav-bar.spec.ts`, sweep three viewports (360, 768, 1280). Existing landing spec's 375×667 case stays as-is.
- **AC-DIR-2 (≥44 px touch target):** `boundingBox()` width AND height ≥ 44 on both landing CTAs + the four inline lang-picker buttons at 360×640. Extend `landing.spec.ts`.
- **AC-DIR-3 (splash renders proportionally):** smoke only — image present, `naturalWidth > 0`, `object-fit: cover` via computed style. Per-club asset upload + focal-point parity is a future-story coverage gap, called out explicitly.
- **AC-DIR-4 (authenticated nav-bar picker + drawer collapse):** Playwright in `nav-bar.spec.ts`; depends on the auth fixture below.
- **AC-DIR-5 (single picker on landing):** assert exactly one lang-picker control on `/`.

**Auth fixture (load-bearing, new):** AC-DIR-4 needs an authenticated session without Keycloak. Expose a `window.__test__seedSession()` hook in dev/test builds and have the Playwright fixture call it via `page.addInitScript` before navigation; route-stub any session-bootstrap network call the nav-bar's init touches. This fixture is the first one of its kind — design for reuse by every future post-auth e2e spec; avoid coupling to `SessionStore`'s internal storage shape.

**Stubs:** landing keeps the existing C15 invariant (zero `/api/v1/*`, zero `/i18n/*`). Authenticated nav-bar path stubs whatever the implementer's wiring actually touches — don't pre-stub speculatively. Disable CSS animations via `prefersReducedMotion: 'reduce'` to avoid hamburger-transition flake.

**Parity:** explicitly excluded. Frontmatter `parity_test: tests/public/landing.spec.ts` is a placeholder — legacy landing is an AngularJS template this story replaces wholesale (R12 modernization, not behavior parity). The e2e suite IS the oracle.

## Security plan
(N/A — frontend-only public landing + route-data flag; no authz boundaries, no PII surfaces, no tenant data. AC-DIR-4 only changes where an existing in-process picker is rendered; `LocaleService.set()` is the same call.)

## Performance plan
(N/A — estimate S, no query / cache / hot-path concerns; landing is a static-shape page with zero backend calls and a single OIDC redirect; nav-bar already lazy-instantiates the drawer.)

<!-- modernize-refine: end -->
