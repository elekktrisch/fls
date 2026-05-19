---
id: S-156
title: Install lucide-angular + wire `<af-icon>` atom
epic: E-01
status: in_progress
started_at: 2026-05-19
github_issue: 77
estimate: S
parity_test: none
depends_on: [S-008]
adr_refs: [0024, 0017]
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements, solution, qa]
context7_last_checked: 2026-05-19
origin: adr-followup
origin_adr: 0024
---

## Context

Follow-up from [ADR 0024 — Visual design system & tone](../adrs/0024-visual-design-system-and-tone.md). ADR 0024 pins **Lucide line icons** as the icon system for author surfaces (nav, primary actions, empty-states, table-action, breadcrumb, status), with ng-zorro's internal icons left untouched. S-008 (component-primitives kit, done) deliberately JIT-deferred the `<af-icon>` atom — see `S-008-component-primitives-kit.md` line 16. This story closes that defer by installing the icon library and wiring the atom.

The convention is asymmetric on purpose: ng-zorro components use their own glyphs (chevrons, X, ✓) internally, and overriding those is explicit scope-cap per ADR 0024 §Decision ("Bridge tokens only. Accept antd defaults everywhere the bridge doesn't reach.").

## Acceptance criteria

- [ ] `lucide-angular` added to `next/web/package.json` and installed; version pinned.
- [ ] `<af-icon>` atom exists under `next/web/src/app/shared/ui/atoms/af-icon/` with a barrel `index.ts`; standalone component, signal-based inputs (`input()`), zoneless-compatible.
- [ ] Public API: `<af-icon name="plane" />` where `name` is a Lucide icon kebab-case identifier; default size 24px, default stroke 1.5px; size and stroke overridable via inputs (`size`, `strokeWidth`).
- [ ] Color follows `currentColor` so Tailwind text utilities (`text-slate-700`, `text-[var(--color-brand-500)]`) control fill/stroke without an extra prop.
- [ ] Touch-target rule honored — when wrapped in a button/icon-button, the parent enforces ≥44px on `<lg` and ≥28px on `≥lg` per [ADR 0017](../adrs/0017-responsive-breakpoint-density-conventions.md). The atom itself does NOT impose hit-area; that's the parent's job.
- [ ] Tree-shaking verified — importing `<af-icon>` and using two icons does not pull the full Lucide set into the bundle. Check with `pnpm build` + bundle-size report.
- [ ] `next/web/CLAUDE.md` updated: the §1 "Atomic design taxonomy" entry for `<af-icon>` no longer says "JIT-deferred"; a one-line note cites ADR 0024 and the Lucide source.
- [ ] Storybook (if present) shows the atom at 16/20/24/32px and at default + heavy stroke widths.

## Tasks

- [ ] Install `lucide-angular`; pin version.
- [ ] Create `next/web/src/app/shared/ui/atoms/af-icon/` (component + spec + index barrel).
- [ ] Wire signal inputs; document name-to-Lucide-identifier mapping convention.
- [ ] Update `next/web/CLAUDE.md` §1 entry for `<af-icon>`.
- [ ] Add a single usage site in the walking-skeleton (e.g. nav bar leading glyph, see S-157) as smoke proof.
- [ ] Bundle-size sanity check.

## Notes

- ng-zorro's `nz-icon` continues to be used inside ng-zorro components and is NOT replaced. Story scope is the author-side atom only.
- Lucide naming convention (kebab-case, e.g. `plane`, `calendar`, `chevron-down`) becomes the convention; the atom rejects unknown names at runtime with a clear console error in dev mode.
- A future story may add ESLint rule banning direct `lucide-angular` imports in feature code (everything must go through `<af-icon>`); deferred unless feature code starts drifting.

<!-- modernize-refine: start -->

## Design notes

### Package + registry strategy

Install the scoped package **`@lucide/angular`** (pin a current version). AC #1 and the §Tasks list name the unscoped `lucide-angular` — that's the deprecated predecessor. The implementer installs `@lucide/angular` instead and notes the correction in the PR body. (Per refine skill, AC edits are out of scope here.)

Pick **dynamic-registry pattern via DI** so `<af-icon name="…">` resolves a string against a central registry; the alternative per-icon directive-import pattern leaks `@lucide/angular` into every feature template and defeats the atom's purpose. Registration lives in `next/web/src/app/core/icons/icon-registry.ts` exporting a single `provideLucideIcons(...)` provider consumed by `app.config.ts` (and `app.config.mock.ts` if it diverges — same registry; presentation-only, tenant-agnostic).

Set global defaults once with `provideLucideConfig({ size: 24, strokeWidth: 1.5, absoluteStrokeWidth: true })` — matches ADR 0024's 24px / 1.5px stance. Per-instance `size` / `strokeWidth` inputs override.

### What the atom wraps

Thin standalone wrapper over `<svg [lucideIcon]="name()">` with signal inputs `name` (required), `size` (default 24), `strokeWidth` (default 1.5), `label` (optional — see Edge cases). Color inherits `currentColor`; no `color` input — would invite ad-hoc hex outside the token set. The atom does NOT impose hit area or default aria-label — both are caller concerns (icon-button parents handle touch-target per ADR 0017; aria-label per `next/web/CLAUDE.md` §5).

### Registry growth

New icons land in one file: `core/icons/icon-registry.ts`. Feature stories that need a new glyph add the named import there (one PR, one batch, no per-feature registry sprawl). ESLint `no-restricted-imports` will ban `@lucide/angular` outside `core/icons/**` and the atom itself — feature code routes through `<af-icon>`. The ESLint rule is deferred per §Notes; until then a runtime dev warning catches the gap.

### Tree-shake verification

Build with two registered icons; grep the production bundle for an unregistered Lucide identifier and confirm absent. Esbuild metafile or `--source-map` output preferred over raw grep (minifier renames identifiers).

## Edge cases & hidden requirements

- **Unknown icon name — dev:** loud `console.error` naming the missing identifier; render a 24×24 transparent placeholder so layout doesn't reflow.
- **Unknown icon name — prod:** same transparent placeholder, no console output. Never throw — a missing glyph must not break the page.
- **Dynamic `[name]` rebinding:** signal change swaps `<path>` content in-place; assert the same host `<svg>` DOM node (no remount). Naive `*ngIf`-style swaps would destroy + recreate.
- **Decorative-by-default a11y:** atom emits `aria-hidden="true"` on the SVG by default (icons typically accompany a labelled control). Optional `label` input flips to `role="img"` + `aria-label={label}` and drops `aria-hidden`. Caller never needs to wrestle the SVG directly.
- **`currentColor` pass-through:** atom sets `fill="none"` + `stroke="currentColor"` (Lucide line style); Tailwind text utilities on the parent control hue.
- **Size is independent of density:** `size` defaults 24px regardless of `data-density`. Density governs the icon-BUTTON parent's hit area (per ADR 0017), not the glyph's intrinsic dimensions. Pin in the kit README; reviewers will otherwise request density-coupled icon sizing.
- **RTL / directional icons:** Lucide does not auto-mirror `chevron-*` / `arrow-*`. AlpenFlight ships DE/FR/IT (all LTR per C21) — no-op today; ledger as deferred capability.
- **ng-zorro icon seam:** `nz-icon` continues inside ng-zorro internals. Visual mismatch at the seam is accepted scope-cap per ADR 0024 — the atom is NOT to be used to "fix" antd internals.

## Security plan

(N/A — presentation atom, no auth/tenancy/PII/audit surface.)

## Test plan

### Pyramid

- **Unit (vitest):** none. Atom is presentation-only; no logic class to isolate. Per `next/web/CLAUDE.md` §8, no `*.component.spec.ts` with DOM assertions.
- **Playwright:** primary test layer. Showcase entry added to the S-008 `/dev/primitives` route; exercised by the existing `primitives-snapshots` project at sm/md/lg/xl viewports.
- **Build smoke:** one bundle-size assertion.
- **a11y:** axe-core on the showcase route.
- **Parity:** none.

### Scenarios

- **`currentColor` inheritance** — two instances inside parents with different Tailwind text utilities; assert computed `stroke` flows through. Regresses easily if someone hardcodes a fill prop.
- **Reactive `name` swap** — bind to a signal, toggle; assert host `<svg>` is the same DOM node and `<path>` `d` changed (no remount).
- **Unknown-name behavior** — dev build: visible placeholder + console error; prod build: silent empty render. Two specs against `pnpm preview` vs `pnpm start`.
- **a11y dual mode** — default `aria-hidden="true"`; when `label="…"` is bound, flips to `role="img"` + `aria-label="…"` with `aria-hidden` removed.
- **Default rendering** — size 24 / stroke 1.5 at all four viewports; piggyback on the existing `primitives-snapshots` project — no new Playwright project needed.

### Bundle-size smoke

Register exactly two icons (e.g. `plane`, `calendar`) and assert an unregistered identifier (e.g. `aperture`) is absent from the esbuild metafile. Single script step in CI.

### a11y

axe-core against `/dev/primitives` at the four viewports — zero `serious` / `critical` violations on `<af-icon>` surfaces. No new entries to `axe-known-issues.md` expected.

### Not tested

ng-zorro `nz-icon` (deliberate scope cap per ADR 0024); cross-browser beyond default Chromium (deferred to S-108); print stylesheets; the ESLint registry-rule (deferred per §Notes).

## Performance plan

(N/A — bundle-size verification covered in the Test plan; no hot-path / query / latency-budget concerns.)

<!-- modernize-refine: end -->
