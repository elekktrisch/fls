---
id: S-112
title: Cutover runbook (draft)
epic: E-14
status: todo
depends_on: [S-017, S-043]
acceptance:
  - `next/ops/runbooks/cutover.md` exists with step-by-step instructions, time estimates per step, who's required, rollback decision points.
  - The runbook fits within the 6-hour window (C6) — total estimated time ≤ 6 hours including buffer.
  - At least one experienced operator (other than the author, if possible) has read + critiqued the runbook.
estimate: M
adr_refs: [0010]
parity_test: none
---

## Context
The runbook is the executable artifact for cutover day. Quality bar: a non-author should be able to execute it.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Draft each step.
- [ ] Time-estimate each.
- [ ] Add decision points (e.g. "if verification fails, decide: continue, rollback, or pause-and-debug").
- [ ] Peer review.

## Notes
Mid-modernization first draft; updated as later stories surface details.
