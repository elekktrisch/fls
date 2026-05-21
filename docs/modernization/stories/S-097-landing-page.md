---
id: S-097
title: Landing page port + nav-bar mechanism (closes R12)
epic: E-12
status: todo
depends_on: [S-002, S-008]
acceptance:
  - `/` (the public landing) renders with the legacy content.
  - Nav-bar visibility is controlled by a route flag (`data: { publicLayout: true }`) or a layout slot — *not* by a boolean expression in code.
  - A test asserts that the nav-bar is hidden on `/`, `/trialflight`, `/passengerflight` (closes R12).
  - Page is reachable without authentication.
estimate: S
adr_refs: [0004, 0022, 0024]
parity_test: tests/public/landing.spec.ts
refined: true
refined_at: 2026-05-21
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer]
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

## Design notes

**Reality check — most of S-097 already shipped piecemeal.** Audit the code before writing more spec:
- AC1 landing content — `alpenflight/web/src/app/features/landing/landing.component.ts` (CTA + tryDemo + footer + inline `<af-lang-picker>`).
- AC2 nav-bar visibility flag — `app.component.ts:69-82` reads `route.snapshot.data['showNavBar']` on `NavigationEnd`. Closes R12 structurally. *Story body still says `publicLayout: true` — never landed; the actual flag is `showNavBar`. AC text needs the rename.*
- AC4 unauthenticated reachability — `core/session/session.guard.ts:25` honours `publicAccess: true`; landing carries `{ showNavBar: false, publicAccess: true }`.
- AC-DIR-4 post-auth picker — already nested in user-dropdown (`af-nav-bar.component.ts:153-175`). AC-DIR-5 single-picker-per-surface — `/` hides nav-bar, inline picker is sole source.

**The remaining S-097 work:**
1. **i18n the nav-bar chrome** (root cause of operator's 2026-05-21 "language switching doesn't work" report). `BASE_SECTIONS` labels in `app.component.ts:11,53` (`'Clubs'`, `'Locations admin'`) + every user-menu / drawer / aria-label literal in `af-nav-bar.component.ts` are literal English. Switching locale fires correctly + flips transloco active lang + sets `<html lang>` — but no chrome text is wrapped in `t(...)`, so nothing visible changes. Add keys `nav.sections.{clubs,locationsAdmin}`, `nav.user.{profile,settings,signOut,menu,language}`, `nav.mobile.{open,close,title,primary,primaryMobile}`. Per `web/CLAUDE.md` §8b add to `de.ts` first — fr/it/en omissions become tsc errors and get filled in the same commit.
2. **Replace inline locale picker with `<af-lang-picker>` molecule** (`af-nav-bar.component.ts:153-175`). One source of truth, satisfies AC-DIR-5 "molecule already drives `LocaleService.set()`."
3. **Whitelabel splash CSS slot** (AC-DIR-3 / C19). `<section class="splash">` backed by `--af-landing-splash` CSS custom property, default to a bundled neutral photo under `alpenflight/web/src/assets/landing/`. `object-fit: cover`, no overlay (ADR 0024 quiet-chrome). **No JS, no per-club fetch.** Per-club asset URL deferred to S-025 (tenant-from-URL) + S-133 (whitelabel asset endpoint).
4. **AC3 cleanup** — drop `/trialflight` + `/passengerflight` references. Routes are S-098/S-099 territory; stubbing placeholders here invites drift. AC3 reduces to nav-bar-hidden assertion on `/`.

**Cross-story contracts.**
- Consumes from S-002: route shape, default-deny `authGuard` + `publicAccess` opt-out, `AppComponent` shell with `showNavBar` signal.
- Consumes from S-005: `LocaleService.set()`, transloco `t(...)`, four per-locale TS modules, `<af-lang-picker>` molecule.
- Consumes from S-008: ng-zorro bridge tokens, `<af-icon>` (Lucide), `af-nav-bar` organism, drawer.
- Produces for S-098/S-099: their public routes inherit the `{ showNavBar: false, publicAccess: true }` shape. For S-133: the splash slot contract (one CSS custom property to set; one bundled fallback to override). For S-025: the read site that will eventually swap the bundled splash for the per-club asset.

**ADR 0024 conformance the implementer must respect.** Brand-500 only on the sign-in CTA + active-section underline + focus rings — never on nav-bar background or drawer chrome. Sharp corners (`--radius-md: 0`) on CTAs and drawer. Sentence case in all new transloco keys (`"Sign out"`, not `"Log out"` or `"SIGN OUT"`). Lucide icons exclusively via `<af-icon>` (`chevron-down` already used). Motion: `opacity 120ms ease-out` only — drawer open/close must NOT slide. Terse Swiss-impersonal copy in new keys.

**ADR 0022 directive 2 check.** Frontend-only story; no schema touch. No deviation.

**Out of scope (explicit).** Per-club splash asset endpoint (S-133), tenant-from-URL parsing (S-025), demo-mode CTA wiring (`landing.component.ts:101` TODO stands — S-133), "Migrate from legacy FLS" CTA copy + wiring (S-133), `/trialflight` + `/passengerflight` routes (S-098/S-099), Keycloak hosted-UI theme (S-019/S-134), the legacy AngularJS landing-footer 1:1 port (see Q2).

## Edge cases & hidden requirements

- **"Doesn't work" — confirmed root cause is literal-English chrome, not broken wiring.** Comment at `app.component.ts:43-46` already concedes nav-bar i18n is debt. The picker emits + `LocaleService.set()` flips transloco active lang + `<html lang>` correctly (verified by `landing.spec.ts`). Fix is the i18n keys, not the wiring.
- **`af-lang-picker` not currently consumed by `af-nav-bar`** — the inline block is a divergence; one source of truth aligns with AC-DIR-5.
- **Drawer at `<md` is untested.** Component exists (`af-nav-bar.component.ts:194-223`); no test asserts hamburger appears + sections collapse into the drawer at narrow viewport. Pairs with the i18n work — translated labels should render in both inline and drawer.
- **Whitelabel without tenant context.** Public landing has no tenant until S-025 ships URL-based resolution. S-097 ships the *CSS+layout slot* only — default to the AlpenFlight-brand bundled asset. Per-club override is the receiving-story's read site (must NEVER default to the last-rendered tenant's photo on `/` — that leaks tenant identity to a probing visitor; flag for S-025).
- **Cross-tab locale sync — explicitly N/A** per `web/CLAUDE.md` §8b "no `localStorage` for the active locale, in-memory thereafter."
- **`ui_locales` to Keycloak** already wired (`landing.component.ts:96,106`). Note only: Keycloak honours that param only if the realm has the locale enabled — operator-facing risk, not code work.
- **Discoverability of post-auth picker** — buried under 32px avatar with no chevron at `<md` reads decoratively. Operator decision Q1 below.

## Security plan

- **No new routes.** Landing's `{ showNavBar: false, publicAccess: true }` stays. The picker placement (AC-DIR-4) does not introduce a route; `LocaleService.set()` touches no tokens, no API.
- **Audit finding (forward-dep, not S-097 blocker):** `authGuard` is **opt-in per feature** (each `*.routes.ts` declares `canActivate: [authGuard]`), not enforced at the root. Today this works because every feature opts in correctly; tomorrow it's one missed import away from a public-by-mistake leak. Either (a) S-097 adds a root `canActivateChild: [authGuard]` to `app.routes.ts` so `publicAccess: true` is the *only* opt-out (preferred — moves invariant from convention to structure), OR (b) defer to S-021 with a Playwright/vitest spec that walks `Router.config` and fails on any leaf carrying neither `publicAccess: true` nor a guard. **Operator decision Q3 below.**
- **Whitelabel splash slot — CSS injection forward-dep for S-025/S-133.** Today bundled-only, no attacker surface. When per-club URL becomes tenant-admin-controllable, the **read site** (the Angular component that writes the `style.setProperty('--af-landing-splash', ...)`) MUST reject `javascript:`, raw `data:` (whitelist image MIME), embedded `)` / `;` that break the `url(...)` token, and `expression(...)`. Single chokepoint in code, not DB. Flag in S-025 + S-133 receiving notes.
- **i18n key safety.** New transloco values are template interpolations only; Angular auto-escapes. `[innerHTML]` forbidden (`web/CLAUDE.md` §10) + gated by `no-html-in-translations.spec.ts`. No new surface.
- **OWASP applicability.** A01 / A05 covered by the audit finding above. Others: no S-097 surface.
- **Cross-tenant + audit events.** N/A — no per-tenant fetch, no mutations.

## Test plan

**Pyramid.** Unit (Vitest): none new — no new logic classes; `LocaleService` + `session.guard` already covered. Per `web/CLAUDE.md` §8, no `*.component.spec.ts` DOM assertions — nav-bar / picker rendering belongs in Playwright. i18n parity already gated by `i18n-key-coverage.spec.ts` + the `Translations` compile-time type — no new CI spec needed, just add the keys.

**Specs to add** (under `alpenflight/web/e2e/tests/`):

- `nav-bar-i18n.spec.ts` — mock-auth on a protected route (e.g. `/clubs`), switch picker DE→FR, assert section labels + user-menu items + drawer aria-label re-render in FR. Once more in IT to catch third-locale drift. EN omitted (the compile-time `Translations` type is the gate).
- `nav-bar-mobile.spec.ts` — Playwright project `mobile` (viewport 375×812 pinned in `playwright.config.ts`); on auth'd route assert hamburger visible, sections collapsed, click opens drawer, sections present inside drawer, ESC closes. Touch-target: hamburger `boundingBox()` ≥ 44×44 (no axe — WCAG/axe target rescinded 2026-05-20d).
- `nav-bar-picker-reuse.spec.ts` — assert nav-bar picker uses molecule's hooks (`[data-testid="af-lang-de|fr|it|en"]`) and no legacy inline `<select>` remains. The whole point of the refactor.
- Extend `landing.spec.ts` (don't fork): assert `[data-testid="af-landing-splash"]` exists, computed `background-image` resolves to bundled default (non-empty URL, 200), `--af-landing-splash` overridable via inline `<html style="...">` (whitelabel slot works). Plus `expect(nav).toHaveCount(0)` on `/` (R12 + AC3 narrowed scope).

**Mock-auth fixture (load-bearing).** Helper `e2e/support/auth-mock.ts`: `page.addInitScript` seeds access_token + minimal profile into the auth store's storage key + `page.route('**/api/v1/me', ...)` stubs the user with `clubId`. Each auth'd spec calls `await mockAuth(page, { locale: 'de' })` before `goto`. Reused when real-auth e2e lands (S-022 chain) by swapping the helper internals.

**Parity.** `parity_test: none` — greenfield surface, no legacy oracle. AC3 cleanup verified inline (`expect(page.locator('a[href*="trialflight"]')).toHaveCount(0)`).

**Risks.**
- *i18n key drift across locales* — mitigated by shipped `i18n-key-coverage.spec.ts` + `Translations` compile-time parity.
- *Mobile-viewport flake* — dedicated Playwright project + `aria-hidden` waits over fixed timeouts.
- *Mock-auth bit-rot* — centralized helper.

**Coverage gaps (deferred).** Per-club splash asset endpoint test → S-133. Demo-mode CTA wiring test → S-133. Real-auth nav-bar i18n (Keycloak round-trip) → S-022. Default-deny route-walker spec → S-097 if Q3 lands as (a), else S-021.

## Performance plan

(N/A — frontend story, no DB, no new network calls. Locale switch must remain in-memory + zero network — covered by existing landing.spec assertions that no `/api/v1/translations` exists per C15. Nav-bar i18n keys ride the JS bundle.)

## Open design questions

1. **Discoverability of post-auth language picker.** Current spec leaves the picker inside the user dropdown (architect rec, aligns with ADR 0024 quiet-chrome — locale is not a primary action). Requirements flagged that at `<md` the user-button hides its chevron (`af-nav-bar.component.ts:118`), so the avatar reads as decorative rather than actionable. **Options:** (a) keep in user dropdown (no work) — operator accepts discoverability cost; (b) surface a visible `[DE ▾]` button left of the avatar in chrome — adds an explicit chrome surface (and an ADR 0024 amendment for "locale switcher is a recognized chrome control"). **Recommend (a).**
2. **Footer port.** Current landing footer (`landing.component.ts:67-83`) is a fresh design (© + privacy + imprint). AC1 says "renders with the legacy content" — could be read as "1:1 port of legacy footer (legal links, version, build)." **Options:** (a) keep current design, edit AC1 wording from "port" → "render"; (b) port legacy footer 1:1. **Recommend (a)** — current design is already shipped + matches ADR 0024 quiet-chrome.
3. **Root-level `canActivateChild: [authGuard]`** (security audit finding). **Options:** (a) apply in S-097 — moves the public-by-mistake invariant from convention (per-feature opt-in) to structure (root opt-out via `publicAccess: true`); adds maybe 15 lines + 1 test. (b) Defer to S-021 with a `Router.config`-walker spec. **Recommend (a)** — cheap, makes the security posture robust for every future feature route, lives in the same `app.routes.ts` S-097 already edits.

<!-- modernize-refine: end -->
