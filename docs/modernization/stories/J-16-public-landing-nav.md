---
id: J-16
title: Public landing + nav (migration CTAs + wordmark)
epic: E-12
status: in_progress
started_at: 2026-07-22
journey0: false
carved: true
depends_on: [J-0]
rolls_up: [S-133, S-157]   # S-097 (landing + nav-hide mechanism) is implemented/ — re-asserted, not rebuilt
acceptance:
  - "[happy] `/` renders the public landing unauthenticated: wordmark, hero headline/value-prop, the CTA pair, stats row, footer — no auth redirect."
  - "[happy] Above-the-fold CTA pair: primary `Migrate from legacy FLS` routes to `/signup?intent=migrate`; secondary `Try demo` routes to `/demo`. `intent=migrate` resolves to the migrate side-path (resolveSignupIntent), not the join default."
  - "[happy] Nav-bar is HIDDEN on public routes (`/`, `/discovery-flight`, `/scenic-flight`, `/signup`) and VISIBLE on a post-auth route (`/start`) — driven by route-data `showNavBar`, never a code boolean (R12 closed)."
  - "[happy] Wordmark SVG renders in the landing hero + topbar (`wordmark-full.svg` at ≥md, `wordmark-compact.svg` at <md); `favicon.svg` wired via `<link rel=icon>`."
  - "[happy] Each CTA click emits `emitFunnelEvent({ event: 'landing.cta_click', ... cta_id })` with `cta_id ∈ {migrate, demo}`."
  - "[edge] Renders + CTAs stack (<md) / sit side-by-side (≥md) across the four ADR-0017 breakpoints (360/768/1024/1440); every CTA hits ≥44×44 CSS px at <md."
  - "[edge] `Try demo` target `/demo` does not exist yet (J-20) — it lands on a coming-soon stub, not a hard 404/console error; the route resolves."
  - "[safety] Zero console errors on landing load (the suite-wide guard) — no browser-ignored `<meta>` CSP `frame-ancestors` warning."
screen: "/ (public landing) — replacing legacy flsweb `main/` (main.html + MainController.js)"
headless_pulled_in: none (funnel telemetry is the existing client-side `emitFunnelEvent` seam; no backend)
migration: N/A — greenfield marketing copy + brand assets (no legacy landing equivalent; R12 modernizes the AngularJS template)
parity_test: alpenflight/web/e2e/tests/real-idp/public-routes.spec.ts   # per-push real-idp proof (landing renders + nav-hide) — greenfield, no legacy pairing; the mock inner-loop is landing/ + shell/nav-bar (see mock_test)
mock_test: alpenflight/web/e2e/tests/(landing/landing|shell/nav-bar)   # per-push mock-e2e runs ONLY J-16's own two specs; prior journeys' mock specs run at the §4 gate + nightly
adr_refs: [0004, 0017, 0018, 0024]
---

## Context
The public landing is AlpenFlight's front door and the funnel head for J-17/18/19 (public
flows) and J-20/21 (demo / migrate wizard). The screen + the route-data nav-hide mechanism
already exist (S-097, done, PR #97). J-16 extends it with the marketing CTA pair that funnels
visitors into migrate-vs-demo (S-133) and swaps the placeholder text wordmark for the real brand
SVG assets (S-157) — the product's first branded public surface. This is a **thin-extend** journey:
its feature half (CTA pair + wordmark + telemetry wiring) is small, so `/do-ship` should fold the
surface-touching debt riders below to fill the 60/40 sprint.

## Spec must assert
The contract the green Playwright run proves, grounded in the existing code + the S-133/S-157 ACs:

- **Landing renders unauthenticated at `/`** — the `features/landing` component loads without an
  auth redirect (route data `publicAccess: true`). Design STRUCTURE is the pixel oracle
  `design-reference/screens-public.jsx` `Landing`: topbar (wordmark + Sign-in), hero (caps eyebrow →
  display headline → value-prop paragraph → CTA row → stats row of three tabular figures), splash
  slot (per-tenant placeholder, ADR-0014), footer. Match its type scale / spacing / button treatment.
- **CTA pair (S-133) — the net-new hero content.** The design ref hero shows `Sign in` + `Request
  access`; S-133 (vision amendment 2026-05-17c) supersedes the hero primary pair with **`Migrate from
  legacy FLS`** (primary, `data-variant=primary data-size=lg`) → `/signup?intent=migrate` and **`Try
  demo`** → `/demo`. Keep the topbar `Sign in`. Whether `Request access` (→ join default) survives as
  a tertiary is a `/do-ship` call — note the design ref carried it.
- **Intent funnel already wired** — `/signup?intent=migrate` is consumed by the existing
  `resolveSignupIntent` (`features/signup/signup-intent.ts`: `migrate` opts out of the `join`
  default; anything else → join; guarded against open-redirect). The spec asserts the migrate CTA
  reaches the migrate side-path, the demo CTA reaches `/demo`.
- **Nav-hide mechanism (R12 closed, re-assert S-097)** — `app.component.ts:56-64` reads the leaf
  route's `data.showNavBar`. Assert nav ABSENT on `/`, `/discovery-flight`, `/scenic-flight`,
  `/signup` (all `showNavBar:false, publicAccess:true` today) and PRESENT on `/start`. This is the
  legacy `main/` bug fix: legacy `flsweb/src/index.js` used an `||` tautology that left the nav
  visible on public flows (R12). The oracle is the e2e suite, not a legacy screenshot.
- **Wordmark SVGs (S-157) — net-new assets** (no `public/brand/` exists). Author
  `wordmark-full.svg` / `wordmark-compact.svg` / `favicon.svg` per ADR-0024 (plane glyph default,
  brand-500 `oklch(0.62 0.18 254.6)`, Roboto Medium outlined text, each ≤4 KB). Replace the current
  text wordmark (`landing.component.ts` `<span>AlpenFlight</span>` + `af-nav-bar`); wire the favicon
  in `index.html` (+ `index.prod.html`). Assert the SVG renders at ≥md (full) / <md (compact).
- **Funnel telemetry** — the `emitFunnelEvent` seam exists (`features/signup/funnel-telemetry.ts`,
  S-147 placeholder). Wire both CTAs to emit `landing.cta_click` with `cta_id`; assert emission
  (spy on the seam / assert the placeholder sink) — this mirrors `migrate-handshake.page.ts:251`'s
  usage. Do NOT invent new telemetry plumbing.
- **Breakpoints + touch targets (AC-DIR-1/2, S-133)** — CTAs stack <md / side-by-side ≥md across
  360/768/1024/1440; ≥44×44 CSS px at <md.
- **Zero-console-error guard** — landing is a public page hit by the suite-wide guard; ensure no
  browser-ignored `<meta>` `frame-ancestors` warning fires (see FRAME-ANCESTORS-HEADER rider).

## Notes

**Migration: N/A.** Greenfield marketing surface. No mapper, no fanout parity step. The green run is
a **mock-idp inner-loop** spec (`landing.spec.ts` + `nav-bar.spec.ts`) with the **real-idp
`public-routes.spec.ts`** confirming at the gate.

**Design reference reconciliation (bake into the build the first time).** `screens-public.jsx`
`Landing` is the LOOK oracle; its hero CTAs (`Sign in` / `Request access`) predate S-133's
migrate/demo pair. Build the S-133 CTA content INTO the design ref's hero layout + button styling —
don't build the design-ref pair and then redesign to S-133.

**Already-built, do NOT rebuild (re-assert only):** landing component + route (`path:''`), the
`showNavBar` route-data mechanism, `signup-intent.ts` (join/migrate resolution), the
`migrate-handshake` feature, the `emitFunnelEvent` funnel seam.

**Likely task seams (non-binding, seam-granular for `/do-ship`):**
- `features/landing/landing.component.ts` — hero CTA pair + below-fold value-prop/3-step + telemetry wiring.
- `public/brand/*.svg` + `index.html`/`index.prod.html` favicon + `af-nav-bar` wordmark consumption.
- `e2e/tests/landing.spec.ts` (extend: CTA routing + telemetry) + `shell/nav-bar.spec.ts` (nav-hide re-assert).

**Boyscout riders to fold (surface-touching — this is the ≤40% debt half; `/do-ship` sizes them):**
- **[FRAME-ANCESTORS-HEADER]** — public-page CSP: `frame-ancestors` is browser-ignored in a `<meta>`
  and trips the zero-console-error guard. A grep of `index.html`/`index.prod.html` shows no
  `frame-ancestors` today → likely already removed; `/do-ship` VERIFIES it's clean on the landing
  load and CLEARS the rider (real header belongs at the S-041 proxy, out of J-16's scope). *(seam: index.html meta CSP)*
- **[NIGHTLY-TALLY-FLAKY-LABEL]** — small nightly step-summary display fix (`⚠️ PASS (N flaky-recovered)`
  vs `❌ FAIL` when `flaky>0` but job is green); rides any gate. *(seam: nightly.yml / e2e-real-idp tally jq)*
- **e2e prettier/tsc per-touch** — normalize the landing/nav specs J-16 edits (not a repo-wide sweep).
- **[COMMENT-STRIP]/[HISTORY→GIT]** per-touch on any landing files edited (why-only comments; contract-only).

## Tasks

Ordered checklist (durable state). Fresh-context worker per `T-NN`, sequential on `integration/J-16`.
Migration N/A → no backend batch, no fanout; frontend-batch boundary runs full `pnpm test` + mock-e2e before §4.

- [x] **T-01 — Landing spec stub + J-16 proof-gallery scaffold.** Extend `e2e/tests/landing.spec.ts` with the CTA-routing + telemetry test STRUCTURE (thin): data-testids `landing-cta-migrate` / `landing-cta-demo` / `landing-cta-request-access`, thin nav assertions. Scaffold the per-journey proof-gallery page (renders J-16 AlpenFlight captures + pass video; tag captures `journey:J-16`) linked from the persistent index. *(seam: landing.spec.ts + gallery page)*
  - Boyscout: the landing spec lived at `e2e/tests/landing.spec.ts` (top level) where the chromium project `testMatch` (`tests/!(real-idp|profile)/**/*.spec.ts`) never collected it — it silently ran nowhere. Moved to `e2e/tests/landing/landing.spec.ts` so the inner-loop spec is actually collected/run.
- [x] **T-02 — Scope per-push gate to J-16 + real-idp public-routes spec.** Only J-16's spec runs real-idp per push (prior journeys mock-idp; full regression → nightly + §4 gate). Create/extend `e2e/tests/real-idp/public-routes.spec.ts` as J-16's heavy spec (landing renders + nav-hidden on public routes / visible on `/start`, under real idp). *(seam: ci/e2e project scoping + public-routes.spec.ts)*
- [x] **T-03 — Wordmark SVG brand assets + favicon (S-157).** Author `public/brand/wordmark-full.svg`, `wordmark-compact.svg`, `favicon.svg` per ADR-0024 (plane glyph, brand-500 `oklch(0.62 0.18 254.6)`, Roboto Medium outlined text, ≤4 KB each); wire `favicon.svg` via `<link rel=icon>` in `src/index.html` + `src/index.prod.html`. *(seam: brand assets + favicon)*
- [x] **T-04 — Wordmark component consumption (S-157).** Shared `af-wordmark` atom (full ≥md / compact <md) consumed in `landing.component.ts` topbar + `af-nav-bar.component.ts`, replacing `<span>AlpenFlight</span>`. **Shared surface:** grep specs asserting the `AlpenFlight` text wordmark and update them. *(seam: wordmark rendering across landing + nav)*
  - Shared-surface grep found NO spec asserting the wordmark as a DOM locator (no `getByText('AlpenFlight')` / `getByRole('img',{name})`); `landing.spec.ts` `toHaveTitle(/AlpenFlight/i)` is a page-title check, and `landing-topbar` / `af-nav-brand` testids are used only as click/container parents — all stay valid. No spec edits needed. `af-nav-bar` `title` input kept (flows to the wordmark `alt`/aria name; sole consumer `app.component.ts` uses the default).
- [x] **T-05 — Landing hero CTA pair + footer + telemetry (S-133).** `landing.component.ts`: hero primary pair `Migrate from legacy FLS` (`data-variant=primary data-size=lg`) → `/signup?intent=migrate` + `Try demo` → `/demo`; demote `Request access` to a tertiary link → `/signup`; add footer (copyright + Status/Documentation/Imprint per design ref); wire both primary CTAs to `emitFunnelEvent({ event_id:'landing.cta_click', timestamp, properties:{ cta_id } })` — the REAL `FunnelEvent` shape, `cta_id ∈ {migrate,demo}`. Repoint `tryDemo` from `authorize(login_hint:demo)` to a `/demo` routerLink. *(seam: landing hero content)*
- [ ] **T-06 — `/demo` coming-soon stub route.** New `DemoStubComponent` + route `{ path:'demo', data:{ showNavBar:false, publicAccess:true } }` rendering a coming-soon message; the route RESOLVES (no catch-all redirect / 404 / console error); `Try demo` reaches it. *(seam: /demo route + stub component)*
- [ ] **T-07 — [NIGHTLY-TALLY-FLAKY-LABEL] tally verdict fix.** CI tally jq: distinct `⚠️ PASS (N flaky-recovered)` verdict when `flaky>0` but job green (display-only). Delete the rider bullet from `_BOYSCOUT.md`. *(seam: nightly.yml + alpenflight-e2e-real-idp.yml tally jq)*
- [ ] **T-08 — Thicken specs + clear FRAME-ANCESTORS.** Thicken `landing.spec.ts` (CTA routing incl. `intent=migrate`→migrate side-path + `/demo` resolves; telemetry emission real shape; breakpoints 360/768/1024/1440 stack/side-by-side + ≥44×44 CSS px; wordmark full/compact; footer; zero-console-error) + `nav-bar.spec.ts` (nav hidden on `/signup`, visible on `/start`). Verify no `frame-ancestors` console error → clear FRAME-ANCESTORS rider bullet. e2e prettier/tsc + COMMENT-STRIP per-touch on edited specs. *(seam: spec thicken)*

**Decisions (load-bearing):** (1) hero primary pair = migrate + demo; `Request access` kept as tertiary link (design-ref carried it, preserves join funnel). (2) `af-wordmark` shared atom (DRY full/compact across landing + nav). (3) FRAME-ANCESTORS confirmed clean at recon (no `frame-ancestors` in either index) — T-08 just verifies + clears. (4) Funnel emission grounded in the real `{event_id,timestamp,properties}` shape, not the AC shorthand.

## Assumptions made
1. `/demo` (J-20 sandbox) is unbuilt — the `Try demo` CTA lands on a coming-soon stub; the AC asserts
   the route resolves (no hard 404 / console error), not a working sandbox.
2. J-16 stays one journey (not split): landing + nav is one coherent public screen; the CTA pair +
   wordmark are one visible result.
3. Greenfield → `parity_test: none` in the S-097/S-133 sense; the green gate is the mock-idp landing/nav
   spec + the real-idp public-routes confirm, not a legacy-vs-next side-by-side.
