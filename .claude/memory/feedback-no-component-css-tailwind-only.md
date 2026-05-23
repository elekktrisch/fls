---
name: feedback-no-component-css-tailwind-only
description: "Avoid CSS entirely in alpenflight/web — Tailwind utility classes in templates, no component styles arrays. styles.css holds only token definitions + unavoidable ng-zorro/CDK overrides."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: c0a3f26e-f788-4ff4-871a-8813bcc19c73
---

The alpenflight/web styling discipline is **utility-classes only**.

**Why:** Operator stated 2026-05-19 after the UI v1 polish landed. The
ng-zorro override block + per-component `styles: [...]` arrays added up
to ~400+ lines of CSS that was hard to scan, hard to maintain, and pulled
in directions the design tokens didn't capture. Tailwind utilities keep
the styling intent visible at the point of use; extracted components
absorb repetition where it appears.

**How to apply:**

- `styles: [...]` arrays in components: **don't write them.** Even
  one-line rules. Use Tailwind utility classes directly on the template
  elements.
- `:host { display: block }` (or other host styling): use the
  `host: { class: 'block ...' }` metadata field on the Component, NOT a
  styles array.
- `styles.css` keeps:
  - design-token definitions inside `@theme { ... }` + `:root { ... }`
  - the ng-zorro / Angular CDK override block (radius, motion,
    button padding, etc.) — unavoidable because ng-zorro internals
    can't take Tailwind utilities
  - global body baseline (font-family, color, background)
  - `::view-transition-*` pseudo-element rules (no template surface to
    attach utilities to)
- **No** `.af-page` / `.af-h1` / `.af-*` utility classes in `styles.css`
  for things consumers could express via Tailwind.
- When the same Tailwind class chain appears 3+ times across templates,
  **extract a component** (`af-page`, `af-section`, etc.) rather than
  promoting the chain to a named CSS class.
- Arbitrary value Tailwind utilities (e.g. `text-[var(--text-h1)]`) are
  fine when the value comes from a token — keeps the token discipline
  while staying in Tailwind syntax.

**Anti-patterns:**
- `<div class="af-page">` with `.af-page { ... }` in styles.css
  → use `<af-page>` wrapper component, or inline Tailwind.
- Component `styles: [\`.foo { padding: 1rem; }\`]` for a one-off look
  → just put `class="p-4"` on the element.
- Per-component CSS variable definitions inside `styles: [...]`
  → variables go in styles.css `@theme` or `:root`.

**Inevitable exceptions (don't argue, just do):**
- Overriding ng-zorro internal classes (`.ant-btn`, `.ant-dropdown-*`,
  `.ant-drawer-*`) — Tailwind can't target them; the override block
  in styles.css is the right home.
- `@keyframes` definitions — no template surface to attach utilities.
- View Transitions API pseudo-elements.
