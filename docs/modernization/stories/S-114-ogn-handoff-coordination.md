---
id: S-114
title: OGN maintainer handoff coordination
epic: E-14
status: todo
depends_on: [S-066]
acceptance:
  - Contact established with the OGNAnalyser maintainer (sgacond on GitHub).
  - Maintainer commits to either: (a) changing OGNAnalyser to call the new POST endpoint at cutover, or (b) being available to coordinate a schema-compatible fallback.
  - Date + time slot reserved for the cutover-day lockstep flip.
  - Test integration completed: OGNAnalyser writes one flight via the new POST endpoint against a staging server; data lands correctly.
estimate: M
adr_refs: []
parity_test: none
---

## Context
C8 + R9. The OGN side is out of repo scope but in modernization scope — coordination must happen externally.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Outreach (email / GitHub issue).
- [ ] Negotiate scope: API path change on their side.
- [ ] Test against staging.
- [ ] Schedule the cutover-day handover.

## Notes
**Book this early.** If the maintainer is unreachable or unwilling, the fallback (schema-compatible direct DB writes) imposes constraints on E-02's schema reshape — knowing this early lets us adjust.
