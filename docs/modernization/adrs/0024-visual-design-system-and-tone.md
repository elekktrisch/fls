# 0024 — Visual design system & UI tone

- **Status:** Accepted
- **Date:** 2026-05-19
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)):
  2. Team-familiar stack
  7. Solo-operator operability
  8. Enables fast feature dev on the new stack
  12. Supports the C17 end-user improvements within the chosen stack — including per-tenant whitelabel branding *(2026-05-15a)*
  13. Supports a single-component responsive mobile-first model with a dense-desktop variant *(2026-05-15b)*

## Context

[ADR 0004](0004-frontend-framework-and-build-tool.md) pins Angular 21 + Tailwind 4; [ADR 0014](0014-per-tenant-theming.md) pins runtime CSS-variable injection for per-tenant theming; [ADR 0017](0017-responsive-breakpoint-density-conventions.md) pins the breakpoint set + density tokens + touch-target rule; the operator directive (2026-05-17) pins ng-zorro-antd as the component-primitives kit and Roboto as the typeface. None of those locks the **aesthetic layer** — mood, neutrals, radius, elevation, typographic stance, icons, motion, copy voice, state personality, navigation pattern, wordmark stance, or how aggressively to override ng-zorro's antd defaults.

Without a single binding choice, every primitive in the kit (S-008), every form (S-007), every feature screen, and the walking-skeleton (S-048) re-litigates these sub-decisions. The result is visual fragmentation across the AlpenFlight default theme and the per-tenant brand overlays — both of which must share one chrome. The cost of *not* deciding is paid story-by-story; the cost of deciding once is paid here. Same shape as ADR 0017 for the responsive layer.

## Directive

Any costumization that is look-and-feel related has to be based on the documentation https://ng.ant.design/llms.txt

## Options considered

### Option A — Swiss-precision + aerospace-pragmatic
- **Capabilities:** Quiet neutral chrome (slate, cool blue-gray); brand color appears only on actions, links, focus rings, active nav indicators, selected-row accents, progress, and the brand glyph; sharp corners (`--radius-md: 0`); flat / borders-only elevation on author code (drop-shadow reserved for modal/command-palette overlay); austere Swiss typography (Roboto 400 body / 500 headings, scale 1.125, sentence case everywhere, `font-variant-numeric: tabular-nums` on data columns); Lucide line icons on author surfaces (24px / 1.5px stroke), ng-zorro internals untouched; light-only v1 with semantic CSS-variable token structure ready for a v2 dark swap; restrained motion (`opacity 120ms ease-out` only — no slide, no spring); terse + functional state personality (one-line empty states, no illustrations, inline red field errors, 300ms spinner threshold); terse Swiss-impersonal voice (no "we", imperative actions, past-tense done states); top-bar + horizontal-nav layout with hamburger drawer below md; typeset wordmark ("AlpenFlight" in Roboto Medium slate-900 + Lucide-style glyph in brand-500); ng-zorro adoption posture is **bridge-only** — the CSS-variable bridge in `styles.css` (`--ant-primary-color`, `--ant-border-radius-base`, `--ant-font-family`, etc.) drives all primary visuals; antd defaults are accepted everywhere the bridge doesn't reach (focus-ring style, hover-bg tint, dropdown drop-shadow, slide-down animations, table padding).
- **Fit to criteria:** Criterion 7 ✓ (one austere system needs less design judgment per feature; solo-operator can compose screens from a tight token set); criterion 8 ✓ (terse voice + no illustrations + no motion = cheap to author and cheap to translate); criterion 12 ✓ (quiet chrome lets *any* tenant primary color land cleanly — even a poor pick still produces a usable UI; whitelabel UX is robust by construction); criterion 13 ✓ (sharp / flat / terse compresses cleanly under 360 × 640 without losing identity; same component, two densities, no parallel trees); criterion 2 ✓ (ng-zorro bridge-only minimizes override tax — solo dev can keep up with antd version bumps).
- **Migration cost:** low. The existing `next/web/src/styles.css` already encodes most of this (slate-free at the moment, brand-500, Roboto, density tokens, ant-bridge). Concrete deltas: `--radius-md: 0`, add semantic surface tokens, add `tabular-nums` utility, install `lucide-angular`, wire `<af-icon>` to Lucide, swap any non-`stone`/`zinc` neutrals to slate.
- **Ecosystem risk:** low. Tailwind 4 + Lucide + ng-zorro are all current and well-maintained; the bridge pattern is documented and stable.
- **Escape hatch:** every aesthetic decision is encoded in CSS variables or a small set of utility/atom files. A future re-skin swaps the token values; structure stays.

### Option B — Standard SaaS polish (Linear / Vercel / Stripe territory)
- **Capabilities:** Cool dark-on-light, semibold headings, scale 1.25 (major-third), tight rhythm, subtle elevation tier (one-step shadow on cards/dropdowns + heavier on modals), 200 ms hover transitions, pulsing skeletons, friendly-professional voice with "we", sidebar primary nav, designed-feeling wordmark.
- **Fit to criteria:** criterion 12 ~ (more elevation + more motion makes tenant primary colors compete with chrome shadows for attention); criterion 13 ~ (sidebar collapses awkwardly to drawer on mobile, wasting precious horizontal space on the airfield form); criterion 7 ~ (more visual richness = more per-feature judgment calls); criterion 8 ~ (slightly higher motion + state surface = slightly more story scope per screen).
- **Migration cost:** medium. Reorganize all neutrals, define multi-tier shadows, build a sidebar shell + drawer, write friendlier copy lib.
- **Ecosystem risk:** low.
- **Why not chosen:** reads professional-but-generic; doesn't earn the precision-tool feel the airfield hot-path (C23) wants; competes for attention with per-tenant brand overlays.

### Option C — Aviation-pro / cockpit
- **Capabilities:** High information density everywhere (not just on hot-path screens), deep saturated palette, dark-first or dark-default, uppercase nav + section labels, monospace-leaning data columns, ATC-console aesthetic.
- **Fit to criteria:** criterion 13 ✗ (cockpit-dense at 360 × 640 is hostile to non-pilot users — admins doing accounting on a phone); criterion 12 ~ (dark-first compounds per-tenant theming contrast review surface — every tenant primary needs validation against two grounds); criterion 7 ✗ (dark-mode doubles design + testing).
- **Migration cost:** high. Dark theme up front; uppercase typography overrides everywhere; data-table custom rendering.
- **Ecosystem risk:** low.
- **Why not chosen:** the airfield workflow is the *hot path*, not the *whole product*. Admin / config / reservation flows benefit from a quieter shell; full cockpit aesthetic crosses into pro-tool credibility at the cost of casual-user friendliness. The chosen Option A keeps the precision feel but doesn't over-correct.

### Option D — Friendly-club / photo-led
- **Capabilities:** Warm photo-led hero treatments on every landing surface, larger type, generous padding, encouraging voice with exclamations, illustration-led empty states.
- **Fit to criteria:** criterion 13 ✗ (large hero photos + generous padding hostile to mobile-first dense-desktop hot-path); criterion 8 ✗ (illustrations are bespoke per state and gate releases); criterion 12 ~ (photo-led chrome competes with per-tenant splash imagery for attention).
- **Migration cost:** high — illustration commissions, photo curation pipeline, padding/typography re-tuning.
- **Ecosystem risk:** low.
- **Why not chosen:** AlpenFlight is a tool for clubs that already exist and already fly. The emotional sell is on the landing/public-flow splash imagery (C19, already in scope via ADR 0014). Inside the authed app, padding and prose get in the way of logging a flight.

## Decision

Chosen: **Option A — Swiss-precision + aerospace-pragmatic**.

Driven by criterion 7 (a quiet austere system is the cheapest path to consistency across features when there's a single developer making per-screen judgment calls), criterion 8 (terse copy + no illustrations + restrained motion compounds into noticeably less per-feature scope), criterion 12 (neutral chrome makes per-tenant primary colors robust — any tenant brand still produces a usable UI, even a poor color pick), and criterion 13 (sharp / flat / terse compresses cleanly across the four locked breakpoints + two locked densities without forking the component tree).

**ng-zorro is bridged, not wrapped.** The CSS-variable bridge already in `styles.css` is the line of demarcation. Author code (atoms, molecules, organisms in `shared/ui/`, feature screens) follows every choice above; ng-zorro components show whatever antd ships outside the bridge — accepted as a deliberate scope cap aligned with the "Working software over comprehensive documentation" primary directive ([ADR 0022](0022-modernization-primary-directives.md)). Re-evaluate if visual fragmentation becomes an operator complaint.

## Consequences

- **Positive:**
  - One coherent visual stance pinned for E-01 foundations and every downstream story; reviewers have a single ADR to check stories against rather than 13 implicit conventions.
  - Tenant primary colors land against a quiet ground. The seven brand-color surfaces (primary button bg, link, focus ring, active nav underline, selected-row left-edge, progress, brand glyph) are the entire brand exposure — bounded and reviewable.
  - Terse voice + no idioms is cheap to translate into DE / FR / IT (C15).
  - Airfield-velocity NFRs respected — no time wasted on illustrations, transitions, or marketing-page energy between flight entries (C23).
  - Light-only v1 is the right call for airfield outdoor use; dark-ready token structure keeps the door open for a v2 swap (~one PR).
  - Lucide line icons consistent with the precision feel; ng-zorro internal glyphs are too small for users to register the inconsistency.

- **Negative:**
  - **Bridge-only ng-zorro creates known dilution zones:** antd focus rings, hover-bg tints, dropdown drop-shadows, slide-down animations, and default table padding stay as antd ships them. Acceptable scope cap; re-evaluate if it bites.
  - Austere typographic discipline (Medium not Bold, scale 1.125, sentence case) reads quieter than competitor SaaS — some marketing-page energy traded for product-page calm. The marketing site (landing) gets the splash photo to compensate.
  - Slate (cool blue-gray) dilutes the "alpine warmth" framing from the mood pick. Final feel reads more aerospace-precision than alpine — coherent, but a small departure from initial intent worth being honest about.
  - No illustrations in empty states means designers/contributors who want to add personality have only typography and color to work with; that's intentional but constrains future "delight" work.
  - Lucide-app + antd-internal icon boundary creates a small visual inconsistency at the seam (e.g. a Lucide chevron on a button next to an antd chevron inside a select).

- **Follow-ups (other stories implied):**
  - **Story (boyscout into next story PR per [[feedback-boyscout-rule-over-clean-prs]]):** update `next/web/src/styles.css` —
    - `--radius-md: 0` (was `0.375rem`)
    - add semantic surface tokens (`--color-surface-bg`, `--color-surface-fg`, `--color-border`) backed by the slate scale; keep the color-named tokens too as the source values
    - add `.tabular` utility (or `font-variant-numeric: tabular-nums` on a token-scoped class) for data columns
    - confirm slate is the active neutral family (replace any stone/zinc usage)
  - **Story:** install `lucide-angular`; wire `<af-icon>` atom to Lucide; document the convention in `next/web/CLAUDE.md`. The kit (S-008) ships every primitive's icon affordance pointing at Lucide.
  - **Story:** wordmark v1 SVG assets — "AlpenFlight" in Roboto Medium slate-900 + Lucide-style glyph in brand-500. Three variants: full (top bar), compact (mobile / favicon source), and favicon (32 × 32). Single SVG file per variant; no rasterization needed.
  - **Story:** add §"Visual conventions" to `next/web/CLAUDE.md` pointing to this ADR; mark it as the source of truth for aesthetic decisions.
  - **Story:** tenant-theming admin UI ([ADR 0014](0014-per-tenant-theming.md) follow-up): the color-picker preview must show all seven brand-color surfaces so an admin can spot a bad color pick before committing it. Contrast check against slate-50 + slate-900 (no dark yet, but pre-staged).
  - **Note for review panel:** maintainability-reviewer + usability-reviewer should cite this ADR when checking layout / typography / motion / state-personality / nav-pattern conformance on story implementations. Touch-target enforcement still cites ADR 0017.
