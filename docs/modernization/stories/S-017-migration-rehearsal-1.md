---
id: S-017
title: Data-migration rehearsal #1 (production-shaped staging)
epic: E-02
status: todo
depends_on: [S-016]
acceptance:
  - The migration from S-016 runs end-to-end against a SQL-Server-restored-from-prod-snapshot DB into a fresh Postgres.
  - Verification report is zero-delta (or all deltas are documented as known/intentional).
  - Wall-clock duration is recorded and is < 6 hours (C6).
  - A post-rehearsal report lists every issue found (script bugs, schema bugs, ambiguous data) and the resolution.
estimate: M
adr_refs: [0002, 0003]
parity_test: none
---

## Context
First of two rehearsals. C6 requires the cutover fit in 6 hours; this rehearsal is how we know whether S-016 actually does.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Provision a staging environment (a copy of the production VPS, or local hardware sufficient for the dataset).
- [ ] Restore a production-shaped snapshot into SQL Server.
- [ ] Run S-016's migration script; time it.
- [ ] Run verification automation; produce the report.
- [ ] Capture all anomalies; file fixes against S-016.
- [ ] Tear down staging.

## Notes
Rehearsal #2 (S-113) happens close to the cutover date — this one is a few months earlier and exists to flush out the bugs.
