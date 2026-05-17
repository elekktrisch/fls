---
id: S-152
title: Rename `next/` → `alpenflight/` working subtree
epic: E-01
status: todo
depends_on: []
acceptance:
  - All `next/server/`, `next/web/`, `next/database/`, `next/auth/`, `next/ops/` folders renamed to `alpenflight/...` in a single atomic commit.
  - All references in docs, ADRs, scripts, configs, and CI workflows updated.
  - Build, tests, and dev-loop compose still work after the rename (run the full pipeline in CI to confirm).
estimate: S
adr_refs: []
parity_test: none
---

## Context
Vision-doc §8 final naming open item. The technical rebrand (S-128) covered the user-facing name but the working subtree slug is still `next/`. This story closes the loop.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Atomic git mv across the 5 subfolders.
- [ ] Search-replace `next/` → `alpenflight/` in docs / ADRs / CI / scripts.
- [ ] Full CI pass.

## Notes
Fires whenever — no calendar dependency. The earlier it runs, the fewer references accumulate to update.
