---
id: S-005
title: Pick + wire i18n library + bundled JSON shape
epic: E-01
status: done
started_at: 2026-05-19
done_at: 2026-05-20
depends_on: [S-002]
github_issue: 84
github_pr: 85
acceptance:
  - i18n library chosen — `@jsverse/transloco`.
  - Translation files live as bundled JSON under `alpenflight/web/src/i18n/<locale>.json` — *not* loaded from the server (C15).
  - Default locale `de`; `en`, `fr`, `it` files exist (4 locales matching what S-097 already ships).
  - A sample component renders a translated string in `de`; switching locale rerenders in real time.
  - The `/api/v1/translations` endpoint is **not** implemented on the new server (closes C15).
  - AC-DIR-1 (amendment 2026-05-15b) — language picker reachable from a mobile-friendly entry point.
  - AC-DIR-2 (amendment 2026-05-15b) — locale switching does not break offline cache.
estimate: S
adr_refs: [0004]
parity_test: none
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements-engineer, solution-architect, qa-engineer]
context7_last_checked: 2026-05-19
---

## Context

C15 in the vision pinned the move from server-loaded to bundled JSON. Translations are **compiled into the JS bundle** (per-locale dynamic-`import()` chunks) — no server `/api/v1/translations`, no static `/i18n/*` fetch.

## Cross-story contracts

- **Consumes S-002:** `<html lang="de">` pin.
- **Consumes S-008:** the `TRANSLATION_ADAPTER` seam in `@shared/ui/locale/`. `LocaleService` remains the single switch for ng-zorro locale + `<html lang>` + transloco's active lang.
- **Produces for S-057:** key-naming convention (flat-dotted, lowercase, domain-first); populated `de.json` skeleton for landing surfaces. S-057 owns per-entity keys + the legacy `LanguageTranslation` row migration.
- **Produces for S-097 (or any later consumer):** `<af-lang-picker>` molecule under `@ui/molecules/af-lang-picker` (button-row variant). Today's nav-bar dropdown picker (S-097) is unchanged; can refactor onto the molecule later.
- **Forward dep on ADR 0015:** locale chunks ride the SPA build, so the SW's standard precache covers them — no S-005-specific directive needed.

## Out of scope

- Translation content beyond the landing surface — S-051+ own per-aggregate keys; S-057 owns the legacy `LanguageTranslation` row migration.
- ICU plurals plugin (`@jsverse/transloco-messageformat`) — deferred to S-057; no key in S-005 needs it. Legacy `flsweb` uses ICU, so S-057 may need to enable it.
- Keycloak hosted-login i18n — owned by S-019 / S-134.
- Cross-session lang persistence — none, by design (no `localStorage` per CLAUDE.md §10).
