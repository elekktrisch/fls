---
id: S-156
title: Install @lucide/angular + wire `<af-icon>` atom
epic: E-01
status: in_progress
started_at: 2026-05-19
github_issue: 77
github_pr: 78
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

Closes the `<af-icon>` atom that S-008 deliberately JIT-deferred (see `implemented/S-008-component-primitives-kit.md` §Module layout — line marked `af-icon/ … [JIT — defer]`). ADR 0024 pins Lucide line icons for author surfaces with ng-zorro internals untouched; this story installs the package and wires the atom + a central registry.

## Acceptance criteria

- [x] `@lucide/angular` added to `next/web/package.json` and installed; version pinned at `^1.16.0`.
- [x] `<af-icon>` atom under `next/web/src/app/shared/ui/atoms/af-icon/` — standalone, signal-input, zoneless.
- [x] Public API: `<af-icon name="plane" />` with `size` (default 24) and `strokeWidth` (default 1.5) overrides.
- [x] Color follows `currentColor`; Tailwind text utilities control fill/stroke.
- [x] Touch-target rule honored — atom does NOT impose hit area; the icon-button parent does per ADR 0017.
- [x] Tree-shaking verified — `primitives-showcase-page` chunk holds at ~135 kB (would be MBs if the full ~1700-icon Lucide set leaked). Bundle-size CI assertion deferred — see Notes.
- [x] `next/web/CLAUDE.md` updated: §1 entry cites ADR 0024 + Lucide; new §11 "Visual conventions" summarizes ADR 0024 (boyscout-folded).
- [x] Walking-skeleton smoke — `<af-nav-bar>` mobile header carries a brand-mark `<af-icon name="plane" />`.

Storybook is not present; the `/dev/primitives` showcase fills the same role (size / stroke / `currentColor` inheritance + labelled variant).

## Notes

- **Package name:** AC #1 originally referenced the unscoped `lucide-angular` — that's the deprecated predecessor. The current scoped package `@lucide/angular` is what shipped.
- **Directive class:** the dynamic-name directive is `LucideDynamicIcon`. `LucideIcon` is a type-only export in v1.16.0; importing it as a component fails at build time.
- **Registry growth:** feature stories add named Lucide imports to `next/web/src/app/core/icons/icon-registry.ts`. ESLint rule banning direct `@lucide/angular` imports outside that file + the atom is deferred until feature code starts drifting.
- **Unknown-name handling:** atom injects `LUCIDE_ICONS` and `console.error`s in dev when a name doesn't resolve — production renders an empty SVG without throwing. RTL non-mirroring for `chevron-*` / `arrow-*` is documented in the registry comment + CLAUDE.md §11.
- **Future hardening:** bundle-size CI assertion (refined §Test plan §Bundle-size smoke) is deferred — operator-defined "implementing only" scope. Pick up when bundle growth becomes a concern.
- **Boyscout fold from ADR 0024 §Follow-ups:** `--radius-md: 0`, semantic surface tokens (`--color-surface-bg` / `-fg` / `-muted` / `--color-border`), `.tabular` utility, and CLAUDE.md §11 "Visual conventions" landed in this PR. Boyscout queue cleared.
