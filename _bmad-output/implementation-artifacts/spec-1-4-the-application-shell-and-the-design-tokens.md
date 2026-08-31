---
title: 'Story 1.4: The application shell and the design tokens'
type: 'feature'
created: '2026-08-30'
status: 'done'
review_loop_iteration: 0
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md', '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-fls-2026-08-24/DESIGN.md', '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-fls-2026-08-24/EXPERIENCE.md']
baseline_commit: '8770e4d09ef9c16b2269090b27c8b54c270da78d'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The client has no design tokens and no application shell. `app.config.ts` has no
router, `styles.css` is empty, and nothing enforces the dark-only, zero-radius, tabular-numeral,
7:1-contrast token system `DESIGN.md` defines. Every later story needs a shell to mount into.

**Approach:** Publish `DESIGN.md`'s tokens as global CSS custom properties, add Angular routing
with a route per destination, and build a shell component that hosts the router outlet plus the
four-destination nav: a bottom tab bar under 768px, a top bar at 768px and above.

## Boundaries & Constraints

**Always:** token values match `DESIGN.md`'s frontmatter exactly (color, spacing, typography,
motion, radius). Every corner is square except the account portrait (not built this story, so no
rounded corner appears yet). The active nav destination carries both a text color and a rule, never
color alone. The focus ring is `2px solid {live}`, `1px` offset, and is never removed.

**Ask First:** any token value that diverges from `DESIGN.md`. Any destination beyond
Home/Operate/Plan/Records/Admin.

**Never:** feature content inside a destination placeholder — that is stories 1.5+. A CSS or
component library beyond plain CSS custom properties. A JS-based (`matchMedia`) breakpoint switch
where a CSS media query does the job.

</frozen-after-approval>

## Code Map

- `alpenflight/client/platform/src/styles.css` -- empty; add the tokens and global resets
- `alpenflight/client/platform/src/app/app.routes.ts` -- new; Home + four destination routes
- `alpenflight/client/platform/src/app/app.config.ts` -- no `provideRouter` yet -- add it
- `alpenflight/client/platform/src/app/shell/shell.ts` (+html+css) -- new; nav + `<router-outlet>`
- `alpenflight/client/platform/src/app/shell/destination-placeholder.ts` (+html) -- new stand-in
- `alpenflight/client/platform/src/app/home/home.ts` (+html) -- new; hosts the status card
- `alpenflight/client/platform/src/app/app.ts`, `app.html` -- renders the card directly today --
  replace with `<app-shell />`; delete now-empty `app.css`
- `alpenflight/client/features/system-status/system-status-card.ts` -- existing, unchanged; the
  `httpResource`/standalone/`OnPush` pattern the new components follow

## Tasks & Acceptance

**Execution:**
- [x] `alpenflight/client/platform/src/styles.css` -- add every `DESIGN.md` color/spacing/typography/
  motion custom property on `:root`, `color-scheme: dark`, background/color reset from
  `surface-base`/`ink-primary`, a `.value` class (`{typography.value}` + `tabular-nums`), and
  `:focus-visible { outline: 2px solid var(--live); outline-offset: 1px }` -- the one token source
  every component below draws from
- [x] `alpenflight/client/platform/src/app/shell/destination-placeholder.ts` (+html) -- standalone,
  `OnPush`, renders `route.snapshot.data['label']` inside `<main>` -- minimal stand-in
- [x] `alpenflight/client/platform/src/app/home/home.ts` (+html) -- standalone, imports
  `SystemStatusCard`, template moved verbatim from current `app.html` -- Home stays the app-open
  surface, not a fifth tab
- [x] `alpenflight/client/platform/src/app/app.routes.ts` -- `Routes` array: `''` to `Home`,
  `operate`/`plan`/`records`/`admin` to `DestinationPlaceholder` with `data: { label }` -- the
  routing seed later stories' feature slices attach to
- [x] `alpenflight/client/platform/src/app/app.config.ts` -- add `provideRouter(routes)` -- wires
  the table into the app
- [x] `alpenflight/client/platform/src/app/shell/shell.ts` (+html+css) -- standalone; `<nav>` with
  one `routerLink` per destination, `routerLinkActive` for the current one; CSS media query at
  768px swaps `tabbar` (bottom, `{spacing.tabbar-h}`) for `topbar` (top, `{spacing.topbar-h}`); the
  active link's rule and text color both come from `{colors.live}` -- the one shell every route
  mounts inside
- [x] `alpenflight/client/platform/src/app/app.ts`, `app.html` -- template becomes `<app-shell />`;
  delete `app.css` -- the app root becomes pure composition
- [x] `alpenflight/client/platform/src/app/shell/shell.spec.ts` -- `TestBed` + `RouterTestingHarness`;
  asserts the four destination links render with the routes from `app.routes.ts`, and the active
  link carries `routerLinkActive`'s class -- proves the nav stays in sync with the route table

**Acceptance Criteria:**
- Given a viewport narrower than 768px, when the shell renders, then the four destinations appear
  in a bottom tab bar and no top bar is present.
- Given a viewport of 768px or wider, when the shell renders, then the four destinations appear in
  a top bar and no bottom tab bar is present.
- Given a destination route is active, when its nav link renders, then it shows both `{colors.live}`
  text and the top-edge/border rule, never color alone.
- Given the app bootstraps, when it navigates to `/`, then `Home` renders and hosts the existing
  system status card with its output unchanged from today.
- Given any focusable element in the shell, when it receives keyboard focus, then a visible 2px
  `{colors.live}` ring appears, at `1px` offset, never removed.

## Design Notes

**One generic placeholder, not four.** The four destinations have no content until stories 1.5+
build their own slices. One `DestinationPlaceholder` fed by route `data.label` avoids four
near-identical files; each destination's real story swaps its own route's `component`, no shell
change needed.

**CSS custom properties, not a framework.** `client/platform` pulls in no CSS library today, and
`DESIGN.md` already gives concrete values, not utility classes. `:root` custom properties are the
smallest step that makes every token available to every component, with no new dependency.

**The nav breakpoint is one judgment call.** `EXPERIENCE.md`'s table names only Phone (< 768px) and
Pointer device (≥ 1200px) for navigation, with no documented 768–1199px nav treatment. This story
switches once, at 768px, matching `DESIGN.md`'s own layout breakpoint — flag this at checkpoint as
worth confirming, not a silent assumption.

## Verification

**Commands:**
- `cd alpenflight && ./gradlew :client:platform:ngTest` -- expected: `shell.spec.ts` and any
  existing specs pass
- `cd alpenflight && ./gradlew :client:platform:ngBuild` -- expected: the Angular build succeeds
  with the new routes and shell
- `cd alpenflight && ./gradlew build` -- expected: full CI-equivalent build stays green

**Manual checks (if no CLI):**
- Resize the running app (`npm start --workspace=platform`) across 768px: confirm the nav visually
  swaps from bottom tab bar to top bar with no layout shift in the routed content, and that the
  active destination's cyan text and rule both appear.

## Suggested Review Order

**Routing seed**

- The one source of truth: destination path/label list, and the route table built from it plus the wildcard fallback.
  [`app.routes.ts:12`](../../alpenflight/client/platform/src/app/app.routes.ts#L12)

- Wires the table into the real app.
  [`app.config.ts:10`](../../alpenflight/client/platform/src/app/app.config.ts#L10)

**Design tokens**

- `DESIGN.md`'s full token set published as CSS custom properties on `:root`.
  [`styles.css:3`](../../alpenflight/client/platform/src/styles.css#L3)

- The global, never-removed focus ring every focusable element inherits.
  [`styles.css:140`](../../alpenflight/client/platform/src/styles.css#L140)

**Shell nav and breakpoint**

- Imports `DESTINATIONS` rather than re-declaring it; maps to absolute paths for the nav.
  [`shell.ts:3`](../../alpenflight/client/platform/src/app/shell/shell.ts#L3)

- `aria-current` and `aria-label` alongside the color+rule active state, never color alone.
  [`shell.html:1`](../../alpenflight/client/platform/src/app/shell/shell.html#L1)

- The single 768px breakpoint that swaps bottom tab bar for top bar — the judgment call flagged in Design Notes.
  [`shell.css:44`](../../alpenflight/client/platform/src/app/shell/shell.css#L44)

**Routed content**

- Minimal stand-in for the four destinations, reads its label from route `data`.
  [`destination-placeholder.ts:10`](../../alpenflight/client/platform/src/app/shell/destination-placeholder.ts#L10)

- Home stays the app-open surface, hosting the pre-existing status card unchanged.
  [`home.ts:2`](../../alpenflight/client/platform/src/app/home/home.ts#L2)

**App root composition**

- `App` collapses to pure composition; the shell is now the one thing it mounts.
  [`app.ts:2`](../../alpenflight/client/platform/src/app/app.ts#L2)

**Tests**

- Proves the nav renders from the route table, marks the active link, and that routed content — not just the nav — matches.
  [`shell.spec.ts:20`](../../alpenflight/client/platform/src/app/shell/shell.spec.ts#L20)

- Smoke test: the app creates and the shell's four links render.
  [`app.spec.ts:24`](../../alpenflight/client/platform/src/app/app.spec.ts#L24)

- Home's status-card behavior, unchanged from before the shell existed.
  [`home.spec.ts:21`](../../alpenflight/client/platform/src/app/home/home.spec.ts#L21)
