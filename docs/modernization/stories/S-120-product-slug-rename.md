---
id: S-120
title: Product slug + next/ → final-name folder rename
epic: E-14
status: todo
depends_on: []
acceptance:
  - Final user-facing product slug chosen (e.g. retain "FLS", or rebrand).
  - All `next/server/`, `next/web/`, `next/database/`, `next/auth/`, `next/ops/` folders renamed to the final slug in one atomic commit.
  - All references in docs and ADRs updated (search-replace).
  - Build, tests, deployment still work after the rename.
estimate: S
adr_refs: []
parity_test: none
---

## Context
Vision §8 final open item: the `next/` slug is a placeholder. This story closes it.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Decide the final name.
- [ ] Atomic rename (one commit).
- [ ] Search-replace in docs.
- [ ] CI rerun to confirm nothing breaks.

## Notes
Schedule near cutover — earlier means more references accumulate to update. Could also stay on `next/` indefinitely if "FLS-next/" or "FLS Next" is acceptable as a final name.
