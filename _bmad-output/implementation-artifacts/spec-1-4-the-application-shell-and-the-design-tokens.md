---
title: 'Story 1.4: The application shell and the design tokens'
type: 'feature'
created: '2026-08-30'
status: 'done'
review_loop_iteration: 3
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

**Amendment 2026-08-31, sprint change proposal:** add Tailwind v4 to `client/platform`, publishing
the same tokens through its `@theme` directive instead of a plain `:root` block. `@theme` still
emits a real CSS custom property per token, under the same names already in use
(`--color-surface-base` and the rest), so no component built against this story changes. This
reopens a `done` story because story 1.5 needs the token pipeline in place before it lands
`ng-zorro-antd`; see the sprint change proposal for the full rationale.

## Boundaries & Constraints

**Always:** token values match `DESIGN.md`'s frontmatter exactly (color, spacing, typography,
motion, radius). Every corner is square except the account portrait (not built this story, so no
rounded corner appears yet). The active nav destination carries both a text color and a rule, never
color alone. The focus ring is `2px solid {live}`, `1px` offset, and is never removed.

**Ask First:** any token value that diverges from `DESIGN.md`. Any destination beyond
Home/Operate/Plan/Records/Admin.

**Never:** feature content inside a destination placeholder — that is stories 1.5+. A component
library this story — Tailwind is a utility/build layer, not a component library, and story 1.5
still owns that decision. A JS-based (`matchMedia`) breakpoint switch where a CSS media query does
the job. A hand-written component's existing CSS rewritten into Tailwind utility classes this
story — the amendment lands the token pipeline only, never a stylesheet rewrite of already-reviewed
code.

</frozen-after-approval>

## Code Map

- `alpenflight/client/platform/src/styles.css` -- empty; add the tokens and global resets; amendment:
  restructure the token block from a plain `:root { ... }` into `@import 'tailwindcss'; @theme { ... }`
  -- see "Amendment, review pass 2" in Design Notes for the exact `@theme` shape (`static`, default-theme
  reset, `--radius`, typography placement) this review loop requires
- `alpenflight/client/platform/package.json` -- amendment: add `tailwindcss` and
  `@tailwindcss/postcss`
- `alpenflight/client/platform/.postcssrc.json` -- amendment: new; wires `@tailwindcss/postcss` into
  the Angular build
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

**Amendment 2026-08-31:**
- [x] `package.json` -- add `tailwindcss` and `@tailwindcss/postcss`
- [x] `.postcssrc.json` -- new; `{"plugins": {"@tailwindcss/postcss": {}}}`
- [x] `styles.css` -- `@import 'tailwindcss/theme' layer(theme); @import 'tailwindcss/utilities' layer(utilities);`
  (never the bare `@import 'tailwindcss'` -- that pulls in Preflight, see Design Notes), then
  `@theme static { ... }` (never bare `@theme` -- see Design Notes on tree-shaking) with every
  existing token name and value unchanged, plus: a bare `--radius: 0;` alongside `--radius-default`
  so Tailwind's own `rounded` utility is also square; every `--font-<role>-*` typography property
  (micro/body/value/value-lead/heading/display -- 4 properties each) moved out of `@theme` into the
  existing plain `:root { color-scheme: dark; }` block, because they were never meant to be
  individual Tailwind utilities and the bare `--font-*` namespace is reserved for font-family; and,
  as the first line inside `@theme static`, a single blanket `--*: initial;` (never a hand-picked
  list of namespaces -- review pass 2's `--color-*`/`--spacing-*`/`--radius-*`/`--text-*` list
  missed the bare `--spacing`/`--font-*`/`--font-weight-*` slots and others; `--*: initial` is
  Tailwind v4's own documented way to discard the entire default theme at once) before declaring
  this story's own values, so no theme-derived stock Tailwind class (`bg-red-500`, `text-xl`,
  `rounded-lg`, `font-bold`, `tracking-wide`, etc.) survives alongside the closed `DESIGN.md` set
- [x] `styles.css` -- add `@source` scoped to `alpenflight/client/platform/src` (never rely on
  Tailwind's automatic project-root detection walking up to the monorepo's `.git` root, which would
  reach `flsserver/`/`flsweb/`) -- implemented as `@source './';` plus `source(none)` on the
  utilities import, since `@source` alone does not disable the automatic `.git`-root walk
- [x] `styles.css` -- declare `@layer theme, base, utilities;` at the top of the file and wrap the
  hand-authored global resets (`*, *::before, *::after`, `html, body`, `body`, `.value`,
  `:focus-visible`) in `@layer base { ... }` -- an unlayered rule always wins over a `layer(...)`
  rule regardless of source order or specificity, so left unlayered these resets would silently
  block any future component from overriding them with a Tailwind utility class
- [x] `shell.spec.ts`, `app.spec.ts`, `home.spec.ts` -- rerun unchanged; the amendment must not
  require a test edit, because no consuming component's output changes
- [x] Verification -- rebuild, then grep the compiled `dist/platform/browser/styles-*.css` for every
  token name declared in `@theme static` and confirm all are present (not only the ones already
  referenced elsewhere) -- the concrete check that closes the tree-shaking gap review pass 1 found
- [x] Verification -- rebuild, then confirm none of `bg-red-500`, `text-xl`, `rounded-lg`,
  `font-bold`, `tracking-wide`, `shadow-lg` compile to a rule carrying Tailwind's stock value in the
  built CSS (the check `--*: initial` exists to guarantee, across every namespace, not the four
  review pass 2 hand-picked)

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
- Given the token pipeline after the amendment, when a component references
  `var(--color-surface-base)` unchanged from before, then it resolves to the same value as before,
  and a new component may use the Tailwind utility `bg-surface-base` for the same token.

## Spec Change Log

- **2026-09-01, bad_spec loopback (review pass 1 -> pass 2).** Three independent review layers
  (blind-hunter, edge-case-hunter, verification-gap), run against the pass-1 implementation,
  converged on the same root cause: a bare `@theme { ... }` block plus a bare `@import 'tailwindcss'`
  does not behave the way the amendment's own comment claimed. Confirmed against a real build
  artifact: `@theme` (non-`static`) tree-shook ~40 of the 62 declared tokens out of the compiled
  CSS -- only tokens some existing rule already referenced via `var(...)` survived. Two reviewers
  independently found the same two further defects: `--radius-default` never drives Tailwind's own
  `rounded` utility (that reads the unsuffixed `--radius`), and every `--font-<role>-*` typography
  property collides with Tailwind's reserved `--font-*` (font-family) namespace, registering as
  bogus font-family utility candidates. A fourth finding: `@theme` merges on top of Tailwind's stock
  theme rather than replacing it, so `bg-red-500`/`text-xl`/`rounded-lg` etc. stay available despite
  `DESIGN.md`'s closed, exact-match token set. A fifth: the bare `@import 'tailwindcss'` also pulls
  in Preflight's global margin/border reset, unaccounted for by this story's own "pixel-identical"
  acceptance criterion. **Known-bad state avoided:** shipping a token pipeline where roughly
  two-thirds of `DESIGN.md`'s tokens silently do not exist in the built CSS until some other rule
  happens to reference them first, and where the design system's own "every corner square, no
  stock color" invariants have no enforcement. **What was amended:** Code Map and the Amendment
  task list now specify `@theme static`, an explicit default-theme reset, a bare `--radius: 0`,
  moving typography sub-properties out of the `--font-*` namespace into the existing plain `:root`
  block, an explicit `@source` scope, and a Preflight-free import shape
  (`tailwindcss/theme` + `tailwindcss/utilities`, not bare `tailwindcss`). **KEEP:** the overall
  approach -- same custom-property names and values, `.postcssrc.json` wiring
  `@tailwindcss/postcss`, `color-scheme: dark` staying in its own plain `:root` rule because
  `@theme` only accepts custom-property declarations -- was correct in pass 1 and is unchanged here;
  only the internals of the `@theme` block and the import statement change. Findings not caused by
  this change (pre-existing test-coverage gaps, token-value duplication with `DESIGN.md`, no
  Tailwind class-sorting Prettier plugin, the new Tailwind v4 browser floor) are not part of this
  loopback; they will be triaged again after pass 2's review, once it is clear they still apply.

- **2026-09-01, bad_spec loopback (review pass 2 -> pass 3).** Review pass 2 fixed pass 1's
  tree-shaking gap (verified: all 72 declared tokens now compile) and confirmed Preflight is gone,
  but two review layers, cross-verified against a real rebuilt `dist/` artifact, found the
  hand-picked `--color-*`/`--spacing-*`/`--radius-*`/`--text-*` reset list still incomplete and one
  new defect: the bare `--spacing` slot (Tailwind's calc()-based numeric-utility multiplier) and the
  `--font-*`/`--font-weight-*`/`--tracking-*`/`--leading-*`/`--shadow-*` namespaces, among others,
  were never reset, so their stock values remain reachable; separately, the file's hand-authored
  global resets (`body`, `.value`, `:focus-visible`, etc.) are unlayered CSS, which always outranks
  `layer(utilities)` regardless of source order, silently blocking any future component from
  overriding them with a Tailwind utility. **Known-bad state avoided:** continuing to fix this
  namespace-by-namespace, finding one missed slot per review round. **What was amended:** the
  hand-picked reset list is replaced with a single blanket `--*: initial;` -- Tailwind v4's own
  documented mechanism for discarding the entire default theme at once, closing every namespace in
  one line instead of enumerating them; and the file now declares `@layer theme, base, utilities;`
  with the hand-authored resets moved into `@layer base { ... }`. **KEEP:** every fix from pass 2
  (`@theme static`, the Preflight-free import shape, `@source`, the `--radius`/`--font-*`
  placement, the same token names and values) -- none of it regresses, this pass only replaces the
  reset mechanism and adds explicit layering. **Also noted, not actioned:** one reviewer found two
  stock, non-token utility classes (`.absolute`, `.table`) already compile into the current build
  from incidental word matches in comments and test names (Tailwind v4's automatic content scanner
  is a plain text scan, not class-attribute-aware), not from any `class="..."` usage anywhere in the
  app. Structural utilities like `position`/`display` keywords are not theme-namespaced, so no
  `-*: initial` reset can remove them; they are inert unused CSS unless a template actually
  references them as a class, which none currently do. This is a known, accepted characteristic of
  Tailwind v4's automatic scanning against a codebase containing ordinary English prose -- not a
  `DESIGN.md` violation, since `DESIGN.md` governs color/spacing/radius/typography, not layout
  primitives -- and is not part of this loopback; see Design Notes.

- **2026-09-01, patch (review pass 3, no further loopback).** Pass 3's own review, run against
  the `--*: initial`/`@layer` fix, confirmed all three prior defects closed (52/52 tokens compile;
  `bg-red-500`/`text-xl`/`rounded-lg`/`font-bold`/`tracking-wide`/`shadow-lg` absent; layering
  verified) and surfaced two small, unambiguous hardenings, applied directly without another
  loopback since neither touches intent or carries design ambiguity: (1) `@source not
  './generated/**/*'` and `@source not './**/*.spec.ts'`, excluding files that carry no real
  template markup from Tailwind's class scanner -- one reviewer empirically traced the
  `.absolute`/`.table` incidental-match leak to two exact words in `shell.ts`'s and
  `shell.spec.ts`'s comments; the `.spec.ts` exclusion removes `.table`'s source, the
  `shell.ts` comment stays (real component file, legitimately scanned) so `.absolute` still
  compiles -- expected and accepted, documented in the file's own `@source` comment. (2)
  `--radius: var(--radius-default);` (was a second independent `0` literal) so the two can never
  drift apart on a future `DESIGN.md` radius change. Verified: rebuild, full token/absence checks
  repeated, `ngTest` and `./gradlew build` green. Findings not caused by this change (no CSS
  regression test, no `prefers-reduced-motion` handling, no Preflight form-control normalization,
  `shell.css`'s untokenized `z-index`/breakpoint literals) are logged in `deferred-work.md`, not
  actioned here.

## Design Notes

**One generic placeholder, not four.** The four destinations have no content until stories 1.5+
build their own slices. One `DestinationPlaceholder` fed by route `data.label` avoids four
near-identical files; each destination's real story swaps its own route's `component`, no shell
change needed.

**CSS custom properties, not a framework.** `client/platform` pulls in no CSS library today, and
`DESIGN.md` already gives concrete values, not utility classes. `:root` custom properties are the
smallest step that makes every token available to every component, with no new dependency.
*(Superseded by the 2026-08-31 amendment below — kept for the record, not the current state.)*

**Amendment: Tailwind lands as the token pipeline, not a styling rewrite.** Roman is used to
Tailwind and attempt 1 proved the `@theme` + `ng-zorro-antd` `--ant-*` bridge combination works.
`@theme` is additive to what this story already built: it emits the same custom-property names,
so `shell.css`, `destination-placeholder`, and `home` need no change. The only new surface is the
build pipeline (`tailwindcss`, `@tailwindcss/postcss`, `.postcssrc.json`) and the token block's own
syntax. Story 1.5 is the first story that gets to spend a Tailwind utility class.

**Amendment, review pass 2: why `@theme static`, a default-theme reset, and a Preflight-free
import.** Tailwind v4's plain `@theme { ... }` only compiles a declared variable into the shipped
CSS when something else already references it -- fine for a component library where unused tokens
are dead weight, wrong for a design-token source every future story is meant to draw from
unconditionally. `@theme static { ... }` forces every declared variable to always emit, matching
what the pre-amendment plain `:root` block already did. Tailwind v4 also *merges* a custom `@theme`
on top of its own stock theme rather than replacing it, so without an explicit reset
(`--color-*: initial;` etc. before this story's own declarations) Tailwind's built-in palette,
spacing scale, and radius scale stay reachable from any component -- directly against `DESIGN.md`'s
closed, exact-match token set and the zero-radius invariant this story exists to enforce. Tailwind's
own default-radius slot is the *unsuffixed* `--radius`, not `--radius-default`, so both are set to
`0`. Typography's four-part tokens (`--font-<role>-size/weight/line-height/letter-spacing`) were
never meant to be individual Tailwind utilities -- they compose into semantic classes like `.value`
-- so they move to the existing plain `:root` block, out of the reserved `--font-*` (font-family)
namespace, with no change to their names or values. Finally, `@import 'tailwindcss'` is really three
imports (theme, preflight, utilities) collapsed into one; importing only `tailwindcss/theme` and
`tailwindcss/utilities` skips Preflight's global reset, honoring "pixel-identical to before" without
needing a second stylesheet to undo Tailwind's own defaults.

**Amendment, review pass 3: a blanket reset, explicit layering, and the scanner's known limit.**
`@theme static { --*: initial; ... }` is Tailwind v4's documented way to discard its entire default
theme in one line, rather than naming namespaces one at a time and missing some (pass 2 missed the
bare `--spacing` multiplier and the `--font-*`/`--font-weight-*`/`--tracking-*`/`--leading-*`/
`--shadow-*` families, among others). Declaring `@layer theme, base, utilities;` and moving the
hand-authored resets into `@layer base` matters because CSS cascade layers give any unlayered rule
priority over every layered rule regardless of specificity or source order -- left unlayered, these
resets would silently out-rank any future Tailwind utility a component reaches for. Separately:
Tailwind v4's automatic content scanner is a plain text scan for utility-class-shaped tokens across
every scanned file, not an HTML/template `class="..."` parser -- it does not distinguish a class
attribute from a code comment or a test name. Two non-token, non-theme-namespaced utilities
(`.absolute`, `.table` -- structural `position`/`display` keywords, not driven by any `--*` custom
property) already compile from incidental word matches (a comment saying "an absolute path", a test
named `...FromTheRouteTable`) even though no template applies either class. No `-*: initial` reset
can remove these, because they don't derive from a theme namespace to reset. They are inert, unused
CSS bytes unless some future template actually writes `class="absolute"` or `class="table"` -- not a
`DESIGN.md` violation, since `DESIGN.md`'s closed set governs color, spacing, radius, and typography,
not layout primitives. Accepted as a known characteristic of Tailwind v4's scanner running across a
codebase full of ordinary English prose, not something this story's `styles.css` can fix.

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
- After the amendment: confirm the shell renders pixel-identical to before — the Tailwind pipeline
  changes how tokens publish, never their values. Confirm this with Preflight excluded (the
  `tailwindcss/theme` + `tailwindcss/utilities` import shape), not the bare `@import 'tailwindcss'`.
- After the amendment: grep the built `dist/platform/browser/styles-*.css` for every custom
  property declared in `@theme static` and confirm every one is present, not only tokens some
  existing rule already references — this is what `static` and the default-theme reset exist to
  guarantee.
- After the amendment: confirm `rounded` (Tailwind's bare radius utility, unused today but
  available to any future component) resolves to `0`, and that a stock Tailwind class outside this
  story's token set (e.g. `bg-red-500`) has no effect — proof the default-theme reset actually
  closed the token set.

## Suggested Review Order

**Routing seed**

- The one source of truth: destination path/label list, and the route table built from it plus the wildcard fallback.
  [`app.routes.ts:12`](../../alpenflight/client/platform/src/app/app.routes.ts#L12)

- Wires the table into the real app.
  [`app.config.ts:10`](../../alpenflight/client/platform/src/app/app.config.ts#L10)

**Design tokens**

- `DESIGN.md`'s full token set published through Tailwind v4's `@theme static` -- the entry point
  for the whole amendment.
  [`styles.css:34`](../../alpenflight/client/platform/src/styles.css#L34)

- The global, never-removed focus ring every focusable element inherits, now inside `@layer base`.
  [`styles.css:187`](../../alpenflight/client/platform/src/styles.css#L187)

**Amendment: the Tailwind token pipeline**

- Preflight-free import shape and explicit layer order -- why an unlayered reset would silently
  beat a future Tailwind utility.
  [`styles.css:8`](../../alpenflight/client/platform/src/styles.css#L8)

- The blanket `--*: initial` reset -- discards Tailwind's entire default theme in one line, closing
  off every stock class (`bg-red-500`, `rounded-lg`, etc.) at once, after two prior review passes
  each found the hand-picked namespace list incomplete.
  [`styles.css:35`](../../alpenflight/client/platform/src/styles.css#L35)

- `--radius` derives from `--radius-default` so Tailwind's bare `rounded` utility and this token
  set's own name can never drift apart.
  [`styles.css:73`](../../alpenflight/client/platform/src/styles.css#L73)

- Typography's four-part tokens moved out of the reserved `--font-*` namespace into a plain
  `:root` block -- they were never meant to be individual Tailwind utilities.
  [`styles.css:115`](../../alpenflight/client/platform/src/styles.css#L115)

- `@source` scoped to this workspace, excluding generated code and spec files -- narrows, though
  cannot fully close, Tailwind's incidental-word-match surface (documented, accepted residual risk).
  [`styles.css:23`](../../alpenflight/client/platform/src/styles.css#L23)

- `.postcssrc.json` wires the PostCSS plugin into the Angular build.
  [`.postcssrc.json:1`](../../alpenflight/client/platform/.postcssrc.json#L1)

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
