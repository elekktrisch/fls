---
id: S-062i
title: Flight-edit keyboard polish — Ctrl+D save+copy, 1–5 quick-select, slide-in focus jump
epic: E-07
status: todo
depends_on: [S-062c]
acceptance:
  - **Ctrl+D = save+copy** (AC-DIR-3b): saves the current flight via the paired-create chain from S-062c, then navigates to `/flights/copy/<savedId>` so the user lands on a pre-filled copy ready for the next flight. Linux Firefox bookmark collision handled via `preventDefault` + a browser-target Playwright test asserting no bookmark dialog opens.
  - **Number keys 1–5** (AC-DIR-3b): when focus is OUTSIDE text inputs / textarea / native select, select the corresponding entry in the club's flight-type recents list (Step 2 Glider). Fires only on Step 2; no-op elsewhere.
  - **Slide-in focus jump** (AC-DIR-13): when a dependent field group appears (e.g. tow step unlocks because Step 1 picked Aerotow; instructor reveals because the picked flight-type sets `InstructorRequired`), 150 ms slide-in honors `prefers-reduced-motion`; focus moves to the first **empty required** field of the new group (skips draft-restored populated fields per AC-DIR-13 wording).
  - Keyboard shortcut help: focusable "?" or hint surface listing Tab/Enter/Esc/Ctrl+D/1–5 — hidden by default, surfaced via Shift+? (parity with conventional web-app keymap discovery).
  - Extends `04d-keyboard-only.spec.ts` (or adds `04d2-keyboard-polish.spec.ts`) covering: Ctrl+D save+copy round-trip; 1–5 only fires outside text inputs; slide-in focus jump lands on first empty required field; Shift+? opens help.
  - Cross-browser: Playwright matrix runs Chromium + Firefox + WebKit for the Ctrl+D test (the bookmark-collision is browser-specific).
estimate: S
adr_refs: [0017, 0024]
parity_test: none
split_from: S-062c
---

## Context
Carved out of S-062c so the parity slice ships with first-pass keyboard nav (Tab/Enter/Esc, AC-DIR-3a). This story adds the "operator velocity" polish — Ctrl+D save+copy and number-key flight-type quick-select were the original ask in vision §F4 "avoid too much mouse clicks", plus the AC-DIR-13 slide-in focus jump that wasn't load-bearing for the happy path.

Vision NFR "keyboard-only completion" originally required these shortcuts only on the dense-desktop variant; the dense variant was dropped 2026-05-25 (single-column wizard at all sizes), so these shortcuts now apply universally.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Coordinator: `keydown` handler at the wizard-shell level — Ctrl+D, number keys, Shift+? — with the "focus outside text input" guard for number keys.
- [ ] Wire Ctrl+D to the same save flow as Submit, then route to `/flights/copy/<savedId>` on success.
- [ ] Flight-type recents list ordering — already from S-008 `RecentlyUsedService`. Bind 1–5 to indices in that list.
- [ ] `prefers-reduced-motion` media query check on the slide-in animation.
- [ ] Focus-jump logic on conditional-section reveal — walks the new group, skips populated fields, lands on first empty required.
- [ ] Shift+? keyboard help surface — a small `<af-dialog>` listing shortcuts (reuses the dialog primitive from S-062c).
- [ ] Playwright extension covering all four behaviors. Cross-browser matrix for Ctrl+D.

## Notes
**Estimate calibration (S):** one keydown handler + one route handler + one focus-jump helper + dialog content + 1 spec (+ cross-browser variant). All infrastructure (paired-create save, recents service, dialog primitive, route handling) reused from S-062c.

**Why split from S-062c:** Linux Firefox Ctrl+D bookmark collision testing alone needs its own cross-browser matrix; bundling it with the parity slice's Playwright run extends CI for every change to the wizard. Isolating polish here keeps S-062c's CI lane fast.

**Validates** S-110-t3-smoke AC-DIR-2 still passes against the **first-pass** keyboard scope from S-062c (Tab/Enter/Esc, no mouse). S-062i is **not** a blocker for S-110.

**Out of scope:**
- Macro/sequence recording.
- Configurable shortcut remapping.
- Vim-style modal editing.
