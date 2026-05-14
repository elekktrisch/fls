---
id: S-043
title: Restore runbook + dry-run drill
epic: E-05
status: todo
depends_on: [S-042]
acceptance:
  - A runbook under `next/ops/runbooks/restore.md` documents step-by-step recovery from a backup.
  - A quarterly drill restores the most recent backup into a parallel VPS; T3 smoke (see S-110) passes against it.
  - The drill produces a timestamped log entry in the runbook recording wall-clock time + any issues found.
estimate: M
adr_refs: [0010]
parity_test: none
---

## Context
A backup that hasn't been restored is theory. The drill is what makes it real.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Draft the runbook from S-042's mechanism.
- [ ] Provision a temp VPS, run the runbook against it.
- [ ] Time it.
- [ ] Iterate on the runbook until a non-author can follow it.

## Notes
The drill should be calendared quarterly — make it a scheduled task on the operator's side.
