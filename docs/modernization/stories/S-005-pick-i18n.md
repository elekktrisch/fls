---
id: S-005
title: Pick + wire i18n library + bundled JSON shape
epic: E-01
status: todo
depends_on: [S-002]
acceptance:
  - i18n library chosen: `@angular/localize` (built-in) or transloco. Decision documented.
  - Translation files live as bundled JSON under `next/web/src/i18n/<locale>.json` — *not* loaded from the server (C15).
  - Default locale `de`; placeholder `en` and `fr` files exist (matching legacy languages).
  - A sample component renders a translated string in `de`; switching locale rerenders in real time.
  - The `/api/v1/translations` endpoint is **not** implemented on the new server (closes C15).
estimate: S
adr_refs: [0004]
parity_test: none
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
