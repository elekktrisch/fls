---
id: S-097
title: Landing page port + nav-bar mechanism (closes R12)
epic: E-12
status: done
started_at: 2026-05-21
done_at: 2026-05-21
depends_on: [S-002, S-008]
acceptance:
  - `/` renders the public landing without authentication.
  - Nav-bar visibility is controlled by route data `{ showNavBar: false | true }` — not by a boolean expression in code (closes R12).
  - Nav-bar is hidden on `/`, `/discovery-flight`, `/scenic-flight`; visible on post-auth routes — asserted in e2e.
  - **AC-DIR-1** Landing + nav-bar render at ≥ 360 × 640. Nav-bar collapses to hamburger at `<md`; inline section tabs at `≥md`.
  - **AC-DIR-2** Every landing CTA hits ≥ 44 × 44 CSS px at `<md` (topbar Sign-in, primary Sign-in, Request access, Try demo).
  - **AC-DIR-3** Splash slot renders `object-fit: cover` with a focal-point class hook. Per-club asset lookup is deferred — typed `splashUrl` input is the extension seam.
  - **AC-DIR-4** Post-auth nav-bar renders `<af-lang-picker>` in both the user-menu dropdown (`≥md`) and the mobile drawer (`<md`). Single source of truth — drives `LocaleService.set()`.
  - **AC-DIR-5** Single picker per surface — landing uses the inline picker; nav-bar's picker can never co-render there because landing opts out of the nav-bar via route data.
estimate: S
adr_refs: [0004]
parity_test: none
refined: true
refined_at: 2026-05-21
refined_specialists: [requirements-engineer, solution-architect, qa-engineer]
github_issue: 96
github_pr: 97
merged: true
merged_at: 2026-05-22
---

## Context
Closes [R12](../01-current-state.md#r12--tautology-bug-in-nav-bar-visibility-indexjs50) — the legacy `||` tautology bug that left the nav-bar visible on public flows. Folds in vision amendments 2026-05-15b (mobile-first / dense-desktop) and 2026-05-21a (post-auth language picker).

## Decisions worth carrying

- **Public-flow URLs renamed**, not preserved. Legacy `/tryflight` → `/discovery-flight` (Schnupperflug); legacy `/passengerflight` → `/scenic-flight` (Mitflug). Stubs ship as "Coming soon" placeholders; porting the legacy booking forms is a future story.
- **Parity excluded.** Frontmatter `parity_test: none`. Legacy landing was an AngularJS template that R12 explicitly modernizes; the e2e suite is the oracle.
- **AC-DIR-3 partial coverage** — the splash slot ships a passthrough placeholder (default SVG). Per-club asset lookup belongs to whichever story builds the whitelabel store. The `splashUrl` input on `LandingComponent` is the extension seam.
- **Mock-auth seam replaces the proposed `__test__seedSession()` hook.** The refinement test-plan called for a Playwright fixture that primes `SessionStore` via `addInitScript`. The existing `app.config.mock.ts` already boots the SPA as `SYSTEM_ADMINISTRATOR` under the e2e build configuration — no new fixture needed. Future post-auth specs reuse the same seam; the speculative hook will only be relevant if/when the suite needs multi-role coverage in the same run.
