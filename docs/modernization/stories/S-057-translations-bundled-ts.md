---
id: S-057
title: Translations migrated to bundled TS modules
epic: E-06
status: in_progress
started_at: 2026-05-20
depends_on: [S-005]
github_issue: 86
github_pr: 87
acceptance:
  - All translation keys/values from the legacy `LanguageTranslation` table land at `alpenflight/web/src/i18n/<locale>.ts` via the orphan-aware migration script. Every locale conforms to the shared `Translations` type derived from `de.ts` (S-005) — a missing key in any locale is a `tsc` compile error.
  - The new server **does not** implement `/api/v1/translations` (closes C15).
  - The legacy admin UI for editing translations is **not** ported — translation changes go through PR + deploy.
  - SPA renders correctly in `de`, `fr`, `it`, `en` for all ported screens.
estimate: M
adr_refs: [0004]
parity_test: none
refined: true
refined_at: 2026-05-20
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer]
---

## Context

C15 closes the server-loaded i18n. This story ships the one-shot migration tool (`alpenflight/web/scripts/migrate-translations/`) that parses the legacy `LanguageTranslations` SQL seed into the TS-module shape S-005 set up. The tool is kept in-tree for re-runs against future prod dumps; the operator-supplied source for the initial run was the fls-e2e seed at `flsserver/database/FLSTest/3 insert/10 insert internationalisation values.sql` (467 rows, German only).

## Cross-story contracts

- **Consumes S-005:** the `Translations` type derived from `de.ts` + the four file paths. No S-005 runtime code touched.
- **Produces for S-051+ / future parity-port stories:** populated `de.ts`; the compile-time type compels en/fr/it parity. Re-running the migration after a future story adds new code references back-fills any matching legacy translations automatically.

## Out of scope

- Translation content beyond what the legacy seed + ported screens already cover — feature-port stories continue adding keys directly to `de.ts`.
- ICU plurals plugin (`@jsverse/transloco-messageformat`). No legacy value sampled needs it; revisit if a prod dump surfaces ICU shapes.
- Keycloak hosted-login copy (S-019 / S-134).
- The legacy admin UI for editing translations.

## Notes for re-runs

- The script `alpenflight/web/scripts/migrate-translations/migrate.ts` preserves existing entries — re-running over hand-edited bundles will not wipe screen-author content. Drops legacy keys whose renamed path is not referenced in source AND not already a suffix of an existing canonical path.
- Locale files are alphabetically sorted by the emit serializer (pinned in `alpenflight/web/CLAUDE.md` §8b); hand-edits should follow the same order so re-runs produce zero diff.
- HTML content is stripped at export-time + fails the run if any survives. Angular interpolation auto-escapes, so the rule is also enforced at runtime by the `no-html-in-translations.spec.ts` guard.
