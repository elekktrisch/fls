---
id: S-057
title: Translations migrated to bundled TS modules
epic: E-06
status: in_progress
started_at: 2026-05-20
depends_on: [S-005]
github_issue: 86
acceptance:
  - All translation keys/values from legacy `LanguageTranslation` table exported into per-locale TypeScript modules at `alpenflight/web/src/i18n/<locale>.ts` — one entry per locale, all conforming to the shared `Translations` type derived from `de.ts` (S-005). A missing key in any other locale is a `tsc` compile error.
  - The new server **does not** implement `/api/v1/translations` (closes C15).
  - The legacy admin UI for editing translations is **not** ported — translation changes now go through PR + deploy.
  - SPA renders correctly in `de`, `fr`, `it`, `en` for all ported screens.
estimate: M
adr_refs: [0004]
parity_test: none
refined: true
refined_at: 2026-05-20
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer]
---

## Context
C15 closes the server-loaded i18n. This story does the one-time content migration into the TS-module shape S-005 set up.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Export script: read legacy `LanguageTranslation` table, write per-locale `<locale>.ts` files conforming to the `Translations` type from `src/i18n/de.ts`.
- [ ] Reconcile: keys that no longer appear in any ported screen are dropped; keys that appear in screens but not in legacy DB get added (with German placeholders).
- [ ] Verify each ported screen renders in all four locales.

## Notes
Some keys may be club-specific in legacy. We're collapsing to system-wide here (per C15 — translations bundled into the SPA). If a club-specific override is needed for a niche term, that's a separate concern (out of scope unless the operator flags it).

<!-- modernize-refine: start -->

## Design notes

**Pure content migration.** S-057 only populates `src/i18n/{de,fr,it,en}.ts`. The loader, providers, picker, key-coverage spec, and the `Translations` type stay exactly as S-005 shipped them.

**Key renaming.** Baseline rule: legacy `ALL_CAPS_UNDERSCORE` → lowercase + dotted (`AIRCRAFT_MODEL` → `aircraft.model`). On top: the export script carries a per-key override table that *buckets* keys into screen / aggregate trees so the result reads like S-005's `landing.*` example (e.g. `FIRST_NAME` → `person.list.firstName`, not `first.name`). The override table is the script's source of truth; reviewable inline.

**HTML in values — strip-at-export.** Legacy ran `useSanitizeValueStrategy(null)` so values *could* carry HTML; sample shows ~1% of keys do. Script strips tags + decodes entities, logs every change as `{locale, key, before, after}`, and fails the run if any value still matches `/<[a-z][^>]*>/i` after reconciliation. `bypassSecurityTrust*` / `[innerHTML]` are off-limits per CLAUDE.md §6 + §10; runtime sanitization is **not** an option here.

**Locale completeness — German fallback.** Where legacy has no `en`/`fr` value (or any `it` value at all — legacy doesn't ship Italian), the non-canonical locale gets the German string verbatim. Real translations land iteratively in content follow-ups; not gated by this story. The `Translations` type forces structural parity at compile time regardless.

**Empty/null legacy values** count as missing → trigger the German fallback. Never emit a literal `""` into `de.ts`.

**Reconciliation rule at land-time.** Drop legacy keys not referenced by `alpenflight/web/src/**/*.{ts,html}`. Keys referenced in code but absent from the dump get added to `de.ts` with a placeholder. `i18n-key-coverage.spec.ts` catches the typo direction; "unused-key" isn't gated.

**Export script.** `alpenflight/web/scripts/migrate-translations.ts` (TS, `pnpm tsx scripts/migrate-translations.ts <legacy-dump.json>`). Operator runs the ad-hoc SELECT to produce the JSON dump (re-runnable from the legacy DB; no committed dump per `[[feedback-re-runnable-over-frozen-docs]]`). Output: all four locale files overwritten. Script + override-table stay in-tree post-merge for re-runs.

**Cross-story contracts.**
- Consumes **S-005:** `Translations` type and the four file paths. No S-005 code touched.
- Produces for **S-051+ / future parity-port stories:** populated `de.ts`; the type compels en/fr/it parity automatically.

**Out of scope.** ICU plurals (`@jsverse/transloco-messageformat`) — defer further unless a legacy key surfaces ICU (sample says no; surface in Open Qs). Keycloak hosted-login copy (S-019 / S-134). Backend retirement of `LanguageTranslation` — AlpenFlight never implemented it; nothing to delete on the new side.

**ADR 0022 directive 2:** N/A — no schema, no DB-side logic.

## Edge cases & hidden requirements

- **Duplicate-key vs. missing-locale joining** — canonical key set is the UNION of `(TranslationKey)` rows across locales (not the intersection). Iterate `DISTINCT TranslationKey`, then join per locale.
- **TS-shape preservation** — `de.ts` keeps `export type Translations = typeof de`; `en/fr/it.ts` keep `const X: Translations = {…}`. The export script emits this idiom verbatim — don't refactor S-005's contract.
- **`i18n-key-coverage.spec.ts` must still pass** post-import; vitest in the same PR is the gate.
- **No new npm dep needed** — transloco + `tsx` are already on dev or trivially installable.
- **Override-table convention rot risk** — once S-057 lands, downstream stories add keys directly to `de.ts` without consulting the script. The rename convention (flat-dotted lowercase, domain-bucketed) must be documented in `alpenflight/web/CLAUDE.md` §i18n in the same PR so contributors don't drift.

## Security plan

S-057 is bundled-content migration; almost all OWASP rows are N/A.

- **A03 (XSS) — load-bearing.** Translation values render via Angular interpolation (auto-escapes). The legacy stack's `useSanitizeValueStrategy(null)` is intentionally NOT carried forward. Mitigation: strip-at-export per Design notes; PR review rejects any `bypassSecurityTrust*` (CLAUDE.md §10), any `[innerHTML]` consumer (§6), or any runtime sanitiser shortcut.
- **A05 (security misconfiguration) — regression guard.** PR review rejects re-introduction of `/api/v1/translations` controller or HTTP-loader-backed Transloco config.
- **Input validation on the export script.** Validate UTF-8 + key regex `^[a-z][a-zA-Z0-9_.]*$` (post-rename); reject malformed rows with non-zero exit.
- **Tenant / PII** — N/A; translations are system-wide UI strings, no `clubId`, no member data. Defensive grep the dump for `clubId` and fail if found.
- **Audit** — N/A; mutations are PR-time edits, git is the audit trail.

## Test plan

`6 vitest · 2 e2e · 0 integration · 0 parity`. S-005's gates (TS type parity + `i18n-key-coverage.spec.ts`) already cover most of the risk; S-057 owns the migration tool + a smoke that the populated files render.

**Unit (vitest, `scripts/migrate-translations.spec.ts`)**
- `mapLegacyToBundle()` rename rule applied to a fixture slice (`AIRCRAFT_MODEL` → `aircraft.model`; one override-table entry exercised).
- Drops legacy keys with no source-tree reference.
- German fallback for untranslated `en/fr/it`.
- HTML path: strip + log; throws if any `<`/`>` survives.
- Emits all 4 locales even when the legacy dump only carries 3 (`it` mirrors `de`).
- Lint-style guard spec: walks the populated `de/en/fr/it.ts` and asserts no value contains `<`/`>` (catches translator-pasted HTML post-migration).

**Playwright e2e** (`alpenflight/web/e2e/tests/i18n/`)
- Landing smoke extended to assert several migrated keys render in `de`.
- Locale switch flips the same set across `de/fr/it/en` (German-fallback strings stay as German, by design).

**Fixtures.** `alpenflight/web/scripts/__fixtures__/legacy-translations.json` — ~10 synthetic rows covering plain text, HTML-laden, mixed-locale, missing-`en`, one orphan key. Shared by all migration specs.

**Deferred.** Per-feature key surface coverage (S-051+ per parity-port story); Keycloak hosted-login (S-019 / S-134); translator-content QA (PR review).

**Risks.** (1) Override-table drift after one-shot — mitigated by documenting the convention in CLAUDE.md §i18n. (2) HTML survival via translator paste — the lint-style guard spec catches it.

## Performance plan

(N/A — bundle-size delta is the only signal; total content KB scale even with full content migration. Subsumed in S-002's existing `angular.json` budget; revisit only if the populated bundles push the initial-bundle warning.)

## Open design questions

1. **Source of the legacy JSON dump.** Options: fresh `bcp`/SELECT from the operator's prod legacy DB; the seeded `fls-e2e` test DB; `flsweb/server/mock-data/translations.json` (only 304 keys — known incomplete vs. prod). Implementer needs an operator-confirmed source.
2. **ICU plurals plugin (`@jsverse/transloco-messageformat`)** — S-005 deferred this to S-057. Sample dump shows no plural-shaped legacy values; if the prod dump confirms none, defer further. Re-evaluate at import-time once the dump is in hand.
3. **Bucketing strictness in the override table.** Naive segment-split (`AIRCRAFT_MODEL` → `aircraft.model`) is mechanical but flat; domain-bucketed renames (`FIRST_NAME` → `person.list.firstName`) need a manual judgment per key. Operator decision: how strict on domain bucketing for the initial import? Strict-bucketing makes the tree readable but adds reviewer load. Naive-split + opportunistic bucketing per-feature-story is the lower-touch path.

<!-- modernize-refine: end -->
