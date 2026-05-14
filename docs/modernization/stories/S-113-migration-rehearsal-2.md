---
id: S-113
title: Data-migration rehearsal #2 (full timing inside 6 hr)
epic: E-14
status: todo
depends_on: [S-017, S-112]
acceptance:
  - End-to-end rehearsal of the cutover runbook against a production-shaped staging environment.
  - Wall-clock duration measured and ≤ 6 hours (C6).
  - Verification automation produces zero-delta report.
  - All anomalies fixed in S-016 and re-tested.
estimate: M
adr_refs: [0010]
parity_test: none
---

## Context
Final rehearsal. Vision §5 calls for two rehearsals; S-017 is the first, this is the second.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Provision staging.
- [ ] Execute the full runbook (not just the migration script — DNS, IdP setup, smoke check, the works).
- [ ] Time.
- [ ] Capture issues.
- [ ] Iterate.

## Notes
This is the last chance to find bugs in the cutover process. Don't rush it; schedule a full day.
