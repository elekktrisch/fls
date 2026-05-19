---
id: S-005
title: Pick + wire i18n library + bundled JSON shape
epic: E-01
status: in_progress
started_at: 2026-05-19
depends_on: [S-002]
github_issue: 84
acceptance:
  - i18n library chosen: `@angular/localize` (built-in) or transloco. Decision documented.
  - Translation files live as bundled JSON under `alpenflight/web/src/i18n/<locale>.json` — *not* loaded from the server (C15).
  - Default locale `de`; placeholder `en` and `fr` files exist (matching legacy languages).
  - A sample component renders a translated string in `de`; switching locale rerenders in real time.
  - The `/api/v1/translations` endpoint is **not** implemented on the new server (closes C15).
estimate: S
adr_refs: [0004]
parity_test: none
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements-engineer, solution-architect, qa-engineer]
context7_last_checked: 2026-05-19
---

## Context
ADR 0004 noted i18n as a sub-decision. C15 in the vision pinned the move from server-loaded to bundled JSON. This story executes both.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Evaluate `@angular/localize` vs. transloco: build-time vs. runtime locale switching, pluralization, ICU messages, lazy locale loading.
- [ ] Recommend: **transloco** — runtime locale switch is the closer behavioral match to `angular-translate`, and `@angular/localize`'s build-time model adds friction for a multi-tenant app where users pick languages at runtime.
- [ ] Wire chosen library; create `de.json`, `en.json`, `fr.json` skeletons.
- [ ] Add a sample translation key + worked example in a component.
- [ ] Define the migration pattern: at parity-port time, each domain's translation keys land in `i18n/<locale>.json`. Stories in E-06..E-09 inherit this pattern.

## Notes
Legacy stores translations in DB (`LanguageTranslation` table) — content migrates to bundled JSON. One-time export script could populate the initial JSON files from the legacy DB; consider as a task in S-057.

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (C21 mobile-first whole-app) implies one small adjustment to this story:

- **AC-DIR-1 (mobile-friendly language picker).** The language picker is reachable from a mobile-friendly entry point — not buried in a hover-only nav menu. Typical placements: nav-bar overflow menu (hamburger) item; user-profile drawer item; footer link. The reference component demonstrates the pattern at `<md` viewport.
- **AC-DIR-2 (locale switching does not break offline cache).** Lazy locale loading (if chosen) must work offline — i.e. all configured locales are served by the PWA service worker (C17 / ADR 0014); switching locale while offline succeeds without a network request.

**Refinement status flag:** Story is unrefined. Fold the above into the AC list when `/modernize-refine S-005` runs.

<!-- amendment-2026-05-15b: end -->

<!-- modernize-refine: start -->

## Design notes

**Library.** `@jsverse/transloco` (operator confirmed — prior experience).

**Bundling posture (C15).** Translations are **compiled into the JS bundle**, never fetched from a server endpoint and never served as static `public/` assets. Per-locale chunks via dynamic `import()` — esbuild emits one chunk per locale, the SW (ADR 0015) naturally pre-caches them as part of the deploy artifact.

**`app.config.ts` wiring.** Add `provideTransloco({ config, loader: TranslocoBundledLoader })` only. **No `provideHttpClient` in this story** — transloco doesn't need it; the first HTTP-calling story (S-006 / S-021) wires it.

**Loader.** Inline import map; statically analyzable so esbuild splits cleanly:
```ts
const loaders = {
  de: () => import('./i18n/de.json'),
  en: () => import('./i18n/en.json'),
  fr: () => import('./i18n/fr.json'),
} satisfies Record<string, () => Promise<{ default: Record<string,string> }>>;

@Injectable({ providedIn: 'root' })
export class TranslocoBundledLoader implements TranslocoLoader {
  getTranslation(lang: string) {
    return from(loaders[lang]().then(m => m.default));
  }
}
```
Files live at **`alpenflight/web/src/i18n/<locale>.json`** (matches AC2 verbatim). The existing `public/i18n/de.json` stub is **deleted** in this story — wrong location for build-time imports.

**Config.** `availableLangs: ['de','en','fr']`, `defaultLang: 'de'`, `fallbackLang: 'de'`, `reRenderOnLangChange: true`, `missingHandler: { useFallbackTranslation: true, allowEmpty: false, logMissingKey: !environment.production }`.

**TS support.** `tsconfig.app.json` already has `resolveJsonModule: true` (Angular CLI default). Verify `"esModuleInterop": true`; if missing, add.

**Active-lang persistence: none.** Cold start: `?lang=` query param → `navigator.language` mapped into available set (`de-CH` → `de`) → `de`. In-memory thereafter. Do **not** install `@jsverse/transloco-persist-lang` (writes `localStorage`, forbidden by CLAUDE.md §10).

**`<html lang>` sync.** `effect()` in the app shell consumes `translocoService.langChanges$` via `toSignal()` and writes `document.documentElement.lang`.

**Key convention.** Flat dotted, lowercase, domain-first (`flight.edit.save`, `common.actions.cancel`). Locked so S-051+ inherits.

**Picker.** New `<af-lang-picker>` molecule under `alpenflight/web/src/app/shared/ui/molecules/af-lang-picker/` (ng-zorro `nz-dropdown`, Tailwind-only). Mount on the landing route in this story; permanent home is the nav-bar overflow in S-097 (mobile-friendly entry per AC-DIR-1).

**AC-DIR-2 (offline).** Falls out for free under the bundled model — locale chunks are part of the SPA build, so the SW's standard precache list (ADR 0015) covers them. No special directive needed; the locale chunks live alongside the other lazy chunks.

**Cross-story contracts.**
- Consumes **S-002:** `<html lang="de">` pin; `src/i18n/` is **new** (not the `public/i18n/` slot S-002 reserved — that gets deleted).
- Produces for **S-057:** key-naming convention + populated `de.json` skeleton (S-057 owns per-entity keys + legacy-row migration).
- Produces for **S-097:** `<af-lang-picker>` molecule.
- Produces for **S-051+:** flat-dotted key convention; `*transloco` directive / `translate` pipe usage.
- Forward dep **ADR 0015:** locale chunks ride the SW's standard precache (no S-005-specific directive).

**Out of scope.** Translation content beyond one sample key; Keycloak realm-theme i18n (S-019 / S-134); legacy `LanguageTranslation` row migration (S-057); cross-session lang persistence; `provideHttpClient` wiring.

## Edge cases & hidden requirements

- **Zoneless runtime switch** — sample component must read via `*transloco` directive or the signal-bridged API. Imperative `translocoService.translate(...)` calls break AC4 silently.
- **C15 closure** — Playwright spec aborts `**/api/v1/translations**` *and* `**/i18n/**` and asserts the page still renders correctly (proves there's no server-side translation surface AND no static-file fetch — locales are in the JS bundle).
- **`src/i18n/de.json`** carries the AC4 sample key + `<af-lang-picker>` label keys; the obsolete `public/i18n/de.json` stub is deleted.
- **Picker scope** — in-app entry point ships with S-097; this story only mounts it on the landing demo route. Keycloak hosted-login picker is owned by S-019 / S-134.

## Security plan

(N/A — bundled static JSON, no API call, no auth, no PII.)

## Test plan

Pyramid: 2–3 vitest · 1 e2e file · ~4 e2e cases · 0 integration · 0 parity.

**Unit (vitest, logic-only per CLAUDE.md §8 — no TestBed/DOM)**
- `LangResolver`: pure fn; resolution chain `query-param → browser-exact → browser-base (de-CH → de) → 'de'`; covers unknown/empty/malformed `navigator.language`.
- `LangSync` effect: mocked `document`; `documentElement.lang` writes track `langChanges$` emissions.
- `missingHandler` smoke: missing `en` key resolves to the `de` value; dev-mode warn fires.

**Playwright e2e** (`alpenflight/web/e2e/tests/i18n/`)
- Happy: `/` renders sample heading in `de`; `<html lang="de">`.
- Switch: picker → `en`; heading re-renders; `<html lang>` flips; URL stable; no full reload.
- AC-DIR-1: at `mobile` viewport, picker is visible + clickable on the landing demo (nav-bar integration defers to S-097).
- C15 closure: `page.route('**/api/v1/translations**', r => r.abort())` AND `page.route('**/i18n/**', r => r.abort())`; page still renders all locales — proves locales come from the JS bundle, not any HTTP fetch.

**Fixtures.** `src/i18n/{de,en,fr}.json` each carry `sample.title`; `en` + `fr` deliberately omit one key for the fallback spec.

**Deferred** — offline locale-switch (AC-DIR-2, ADR 0015 impl); real content (S-057); Keycloak login i18n (S-019 / S-134). Leave a `test.skip` stub for the SW-offline case with the contract pre-written.

## Performance plan

(N/A — locale JSONs are KB-scale, load once, SW pre-caches per design notes. Bundle weight subsumed in S-002's `angular.json` budget; revisit only if transloco + the ICU plugin push the initial-bundle warning.)

## Open design questions

1. **Locale count: 3 (AC3 `de/en/fr`) or 4 (add `it` — S-002 reserved the slot; multilingual-CH market posture per amendment 2026-05-15b)?**
2. **ICU plugin (`@jsverse/transloco-messageformat`) now, or defer to S-057?** Legacy `flsweb` uses `angular-translate-messageformat-interpolation`, so some legacy keys are ICU. Wire now = S-057 inherits cleanly; defer = smaller S-005.
3. **AC-DIR-1 / AC-DIR-2 promotion** to first-class ACs — refine can't edit ACs; operator promotes via `/modernize-decompose` or leaves the implementer to treat them as load-bearing from the amendment text.

<!-- modernize-refine: end -->
