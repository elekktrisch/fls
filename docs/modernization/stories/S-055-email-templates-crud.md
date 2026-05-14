---
id: S-055
title: Email templates CRUD
epic: E-06
status: todo
depends_on: [S-048, S-082]
acceptance:
  - `EmailTemplate` entity ported (per-club override + system default).
  - List + edit screen with a syntax-highlighted template editor (or plain textarea acceptable).
  - The actual rendering uses Thymeleaf (S-082); EmailTemplate stores the template source for clubs that override the default.
  - Audit-log entries on mutations.
estimate: M
adr_refs: [0013]
parity_test: none
---

## Context
Per-club email customization. Templates ship as Thymeleaf in `src/main/resources/templates/email/` by default; the DB row exists to override per-club without a redeploy.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entity + mapping + controller + DTO.
- [ ] Wire EmailTemplate consult-then-fallback into the Thymeleaf rendering path (Spring's TemplateResolver chain).
- [ ] SPA store + screens.

## Notes
Decide: is the per-club override a full template, or just substitution variables? Recommend **full template** for flexibility, with a "reset to default" button.
