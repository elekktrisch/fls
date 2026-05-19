---
id: S-158
title: Tenant-branding seven-surface preview component
epic: E-06
status: todo
estimate: S
parity_test: none
depends_on: [S-008, S-156]
adr_refs: [0024, 0014]
refined: false
origin: adr-followup
origin_adr: 0024
---

## Context

Follow-up from [ADR 0024 — Visual design system & tone](../adrs/0024-visual-design-system-and-tone.md) §Follow-ups. ADR 0024's chosen visual stance (Option A — neutral chrome with brand color on actions/state only) means the per-tenant primary color from [ADR 0014](../adrs/0014-per-tenant-theming.md) appears on a **specific set of seven surfaces**:

1. Primary button background.
2. Link text.
3. Focus ring on focusable elements.
4. Active nav-item underline.
5. Selected-row left-edge accent in lists.
6. Progress indicator fill.
7. Brand glyph in the wordmark.

When a club admin picks a primary color in the (future) branding admin UI, they need to *see* all seven surfaces simultaneously before saving — a poor color pick (low contrast, garish, hue clash) is hard to predict from a color swatch alone. This story builds the reusable preview component that renders all seven surfaces given a primary-color input.

The component is **independent of the branding admin form** — it's a controlled component that takes `primaryColor` as a signal input and renders the surfaces. The consuming branding admin CRUD UI (per ADR 0014 §Follow-ups — story not yet written) wires the form's color value to the preview.

## Acceptance criteria

- [ ] `<af-branding-preview>` molecule exists under `alpenflight/web/src/app/shared/ui/molecules/af-branding-preview/`; standalone, signal-based, zoneless-compatible.
- [ ] Single signal input: `primaryColor: string` (OKLCH or hex acceptable — the component does not validate the format; consuming form is responsible).
- [ ] Renders all seven brand-color surfaces in a single compact panel:
  - A primary button (with the input color as background, white text, ADR 0024-locked sharp corners).
  - A link example ("Example link" with input color text + underline).
  - A focused input field showing the focus ring in the input color.
  - A nav-item bar with one active item carrying a 2px underline in the input color.
  - A two-row list with the second row selected (left-edge 3px accent in the input color, slate-50 row tint).
  - A progress bar at ~60% in the input color.
  - The AlpenFlight wordmark with the glyph colored using the input color.
- [ ] Layout works at all four ADR 0017 breakpoints — collapses cleanly under 360px (stacks the seven surfaces vertically); shows 2×4 or 3×3 grid at md+.
- [ ] Surface placeholders use slate-* neutrals per ADR 0024 — no actual data, no actual nav state, no actual progress. Purely visual.
- [ ] Reactive: changing `primaryColor` input updates all seven surfaces synchronously (signal-driven, no manual re-render).
- [ ] Contrast hint (optional, scope-cap): a small WCAG-AA contrast indicator under the primary-button surface signals "Low contrast vs. white text" if the input color fails the 4.5:1 ratio against white. Implementation: a 10-line OKLCH luminance check. Defer if it bloats the story; mark as JIT.

## Tasks

- [ ] Create `alpenflight/web/src/app/shared/ui/molecules/af-branding-preview/` (component + spec + index barrel).
- [ ] Render the seven surfaces; use `<af-icon>` for the wordmark glyph (S-156) and `<af-button>` (S-008) for the primary button.
- [ ] Wire signal input + reactive recomputation.
- [ ] Responsive layout tested at 360 / 768 / 1024 / 1440.
- [ ] Standalone demo route (`/dev/branding-preview`) for visual inspection — same dev-route pattern as S-002's smoke-screen.
- [ ] If contrast hint is in scope: add OKLCH luminance util in `shared/util/`.

## Notes

- **Future dependency:** the branding admin CRUD UI is a separate ADR 0014 follow-up that has NOT been written yet (this story does not write it). When that story lands, it imports `<af-branding-preview>` and wires the form's color value to the input.
- This story produces a *standalone* molecule + a dev route — enough to verify the visual at all four breakpoints without the admin form context.
- Per ADR 0024 §Consequences (Negative): the seven surfaces are the *entire* brand exposure in the new UI. Any future surface that should also adopt the tenant primary color is a deviation that should be flagged in the consuming story.
- The wordmark glyph in surface #7 mirrors S-157's brand-glyph; if S-157 lands first, this story consumes the same SVG via `<af-icon>` with a color override. If S-156/157 are not yet landed, a placeholder `<svg>` is acceptable.
