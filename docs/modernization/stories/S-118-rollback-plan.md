---
id: S-118
title: Rollback plan + pre-cutover snapshot procedure
epic: E-14
status: todo
depends_on: [S-117, S-042]
acceptance:
  - Rollback plan documented: pre-cutover DB snapshot procedure, decision criteria for "rollback vs forward-fix," DNS-flip-back commands.
  - Pre-cutover snapshot procedure rehearsed (taking + restoring an SQL Server snapshot, capturing the user-import-and-send-emails state).
  - Decision tree: "if X fails before users let in → rollback; if Y fails after users let in → forward-fix or accept downtime."
estimate: M
adr_refs: [0010]
parity_test: none
---

## Context
Vision §5: rollback is "if cutover fails before users are let in, restore old system from DNS + DB snapshot." Once users have written data on the new system, forward-fix.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Snapshot procedure for legacy SQL Server.
- [ ] Snapshot procedure for legacy app state (config, user list).
- [ ] Decision-tree document.
- [ ] Drill: simulated rollback against staging.

## Notes
The "rollback" terminology is overloaded. Be explicit in the doc: it's "stop traffic to new, resume traffic to old, restore the snapshot if any new-system DB writes happened." Don't confuse with "delete new-system data."
