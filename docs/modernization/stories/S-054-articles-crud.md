---
id: S-054
title: Articles CRUD
epic: E-06
status: todo
depends_on: [S-048]
acceptance:
  - `Article` entity ported (per-club). Articles are referenced by `DeliveryItem.article_id` — pre-req for E-09.
  - List + edit screens.
  - Audit log entries on mutations.
estimate: S
adr_refs: [0005, 0008]
parity_test: none
---

## Context
No e2e spec exists for articles in legacy (per current-state §2). This story adds the surface; depth coverage comes via E-13.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entity + mapping + controller + DTO.
- [ ] SPA store + screens.

## Notes
Don't conflate Article with AccountingRuleFilter — Article is a price-list row (article number, name, unit price); rules engine picks an Article to produce a DeliveryItem.
