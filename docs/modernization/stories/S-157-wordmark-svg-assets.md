---
id: S-157
title: Wordmark v1 SVG assets (full, compact, favicon)
epic: E-01
status: todo
estimate: S
parity_test: none
depends_on: [S-128, S-156]
adr_refs: [0024]
refined: false
origin: adr-followup
origin_adr: 0024
---

## Context

Follow-up from [ADR 0024 — Visual design system & tone](../adrs/0024-visual-design-system-and-tone.md) §Follow-ups. The product needs a wordmark on the top bar (every authed screen), the landing page (public flows), and the favicon. ADR 0024 §Decision pins the v1 stance as a **typeset wordmark + small Lucide-style glyph** — no custom logo design work, no rasterization. "AlpenFlight" in Roboto Medium slate-900 with a glyph in brand-500 to the left.

The rebrand (S-128, done) already established AlpenFlight as the product name and locked the brand-500 OKLCH(0.62 0.18 254.6) sky blue. Roboto is loaded via `@fontsource/roboto`. This story produces the actual SVG files that consuming surfaces import.

## Acceptance criteria

- [ ] Three SVG assets exist under `alpenflight/web/public/brand/`:
  - `wordmark-full.svg` — glyph + "AlpenFlight" typeset, single-line; target use: top bar, landing hero.
  - `wordmark-compact.svg` — glyph + "AF" compact form; target use: mobile top bar, narrow contexts.
  - `favicon.svg` — glyph only on brand-500 square (or transparent if better); target use: `<link rel="icon">`.
- [ ] Glyph is a Lucide-style line icon — either `plane` (paper-plane / aviation) or `mountain` / `mountain-snow` (alpine). Pick one; record in `## Notes`. 1.5px stroke, 20×20 viewBox, brand-500 stroke (`oklch(0.62 0.18 254.6)`).
- [ ] Wordmark text is Roboto Medium 500, slate-900 (`#0F172A`), set at a reference size of 18px in `wordmark-full.svg`; text is converted to outlines so the SVG renders without the Roboto webfont (loaded asynchronously elsewhere).
- [ ] All SVGs are hand-authored or exported clean — no editor metadata, no embedded raster, no inline base64 fonts, no inline `style` blocks with hardcoded hex if a CSS variable name is more honest. Stroke/fill colors stay as resolved OKLCH or hex (CSS variables don't resolve inside `<link rel="icon">`).
- [ ] `alpenflight/web/index.html` (and `index.prod.html` if it diverges) references the favicon: `<link rel="icon" type="image/svg+xml" href="/brand/favicon.svg" />`.
- [ ] `<af-nav-bar>` organism (kit) consumes `wordmark-full.svg` on `≥md`, `wordmark-compact.svg` on `<md`; visible at all four ADR 0017 breakpoints.
- [ ] Landing page (S-097 done; S-133 in-flight) consumes `wordmark-full.svg` in the hero.
- [ ] Asset sizes: each SVG ≤ 4 KB (sanity guard against bloat from outlined glyphs).

## Tasks

- [ ] Pick glyph (plane vs mountain). Record decision in `## Notes`.
- [ ] Author `wordmark-full.svg`, `wordmark-compact.svg`, `favicon.svg`.
- [ ] Drop into `alpenflight/web/public/brand/`.
- [ ] Wire `<link rel="icon">` in `index.html` + `index.prod.html`.
- [ ] Update `<af-nav-bar>` to consume the assets at the right breakpoint.
- [ ] Update landing page consumer.
- [ ] Visual smoke: open at 360 / 768 / 1024 / 1440 widths; capture screenshots.

## Notes

- v1 stance per ADR 0024 — NOT a designed logotype. A v2 logo refresh is allowed as a separate story later; the asset path lets it swap in cleanly.
- The decision between `plane` and `mountain` glyph is small enough not to grill the operator. **Default: `plane` (paper-plane / aviation)** — directly invokes flight, recognizable at favicon scale, less symbolic ambiguity than a mountain. If the operator prefers `mountain` they can swap a single icon reference.
- Per-tenant theming (ADR 0014) does NOT swap the AlpenFlight wordmark — the wordmark stays AlpenFlight; the *tenant logo* renders alongside it (separate slot in the top bar, per the Q11 nav mockup in the ADR 0024 grilling).
- Compact form "AF" is a transitional concept; if a designer later proposes a different compact (e.g. just the glyph), swap the file content without changing the path.
