---
id: S-105
title: Expand Playwright depth — glider↔tow link integrity + cascade
epic: E-13
status: todo
depends_on: []
acceptance:
  - Specs cover: partial update on glider while tow is referenced; cascade on glider delete; orphan tow flights; tow flight without a glider; concurrent edit of glider and tow.
  - Green on legacy.
estimate: M
adr_refs: []
parity_test: self
---

## Context
R14: glider-tow link integrity is untested.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Catalog the edge cases.
- [ ] Per case → spec.
- [ ] Verify on legacy.

## Notes
This story drives the cascade-decision in S-063 (delete vs unlink).
