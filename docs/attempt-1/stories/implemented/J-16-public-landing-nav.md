---
id: J-16
title: Public landing + nav (migration CTAs + wordmark)
epic: E-12
status: done
started_at: 2026-07-22
done_at: 2026-07-22
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
  - "[edge] Renders across the four ADR-0017 breakpoints (360/768/1024/1440); CTAs stack below lg and sit side-by-side at ≥lg (1024) — the md 2-col hero narrows the CTA column, so true side-by-side lands at lg, not md; every CTA hits ≥44×44 CSS px at <md."
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

## Tasks (shipped)

- [x] **T-01** — Landing spec stub + J-16 proof-gallery scaffold. *(boyscout: `landing.spec.ts` moved from top-level to `e2e/tests/landing/` — it had lived where chromium `testMatch` never collected it, a pre-existing false-green.)*
- [x] **T-02** — Per-push gate scoped to J-16 (`parity_test`/`mock_test` frontmatter drives the ci.yml derive) + `real-idp/public-routes.spec.ts` heavy gate spec.
- [x] **T-03** — Wordmark SVG brand assets (`public/brand/{wordmark-full,wordmark-compact,favicon}.svg`, ≤4 KB, outlined roboto paths) + favicon `<link>` in both index files.
- [x] **T-04** — Shared `af-wordmark` atom consumed in landing topbar + `af-nav-bar` (no spec asserted the old text wordmark → no locator edits needed).
- [x] **T-05** — Hero CTA pair (migrate/demo primary + request-access tertiary) + footer + funnel telemetry (real `FunnelEvent` shape).
- [x] **T-06** — `/demo` coming-soon stub route (resolves, no catch-all redirect / console error).
- [x] **T-07** — [NIGHTLY-TALLY-FLAKY-LABEL] tally verdict `⚠️ PASS (N flaky-recovered)` in nightly.yml + alpenflight-e2e-real-idp.yml.
- [x] **T-08** — Thicken landing + nav-bar specs to full assertions; verify + clear [FRAME-ANCESTORS-HEADER].

## Decisions (load-bearing)
1. Hero primary pair = `Migrate from legacy FLS` + `Try demo`; `Request access` kept as a tertiary link (design-ref carried it; preserves the join funnel).
2. `af-wordmark` shared atom (DRY full≥md / compact<md across landing + nav).
3. Funnel emission grounded in the real `emitFunnelEvent` `{event_id,timestamp,properties}` shape — the AC-body `{event,cta_id}` is shorthand; `cta_id` lives in `properties`.
4. Migrate side-path asserted via the real `resolveSignupIntent`→`postSignupLandingPath` chain (post-login redirect `/migrate/start` vs `/join`), not the URL alone.
5. **[FRAME-ANCESTORS-HEADER] cleared** — no `frame-ancestors` in either index (zero-console-error green); the real clickjacking response-header is S-041's scope (`S-041-reverse-proxy.md`), not this journey.
6. `/demo` (J-20 sandbox) is unbuilt — the CTA lands on a coming-soon stub; the AC asserts the route resolves, not a working sandbox.

## Outcome
Shipped in **#244**. Mock inner-loop green (landing 21/21, nav-bar 9/9, web unit 648/648) + real-idp gate spec `public-routes.spec.ts` green on the final sha (CI clean-seed proof + cross-journey dashboard/profile proofs). Two gap-hunters cleared the green (2/2). Migration N/A → no fanout. No mocked seams beyond the standard mock-idp inner loop. Riders shipped: [FRAME-ANCESTORS-HEADER], [NIGHTLY-TALLY-FLAKY-LABEL].
