---
id: S-057
title: Translations migrated to bundled JSON
epic: E-06
status: todo
depends_on: [S-005]
acceptance:
  - All translation keys/values from legacy `LanguageTranslation` table exported to `alpenflight/web/src/i18n/<locale>.json`.
  - The new server **does not** implement `/api/v1/translations` (closes C15).
  - The legacy admin UI for editing translations is **not** ported — translation changes now go through PR + deploy.
  - SPA renders correctly in `de`, `en`, `fr` for all ported screens.
estimate: M
adr_refs: [0004]
parity_test: none
---

## Context
C15 closes the server-loaded i18n. This story does the one-time content migration.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Export script: read legacy `LanguageTranslation` table, write per-locale JSON.
- [ ] Reconcile: keys that no longer appear in any ported screen are dropped; keys that appear in screens but not in legacy DB get added (with German placeholders).
- [ ] Verify each ported screen renders in all three locales.

## Notes
Some keys may be club-specific in legacy. We're collapsing to system-wide here (per C15 — bundled JSON). If a club-specific override is needed for a niche term, that's a separate concern (out of scope unless the operator flags it).
