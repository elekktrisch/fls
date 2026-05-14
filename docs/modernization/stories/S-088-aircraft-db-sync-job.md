---
id: S-088
title: Port AircraftDatabaseSyncJob (OGN aircraft DB)
epic: E-10
status: todo
depends_on: [S-081, S-050]
acceptance:
  - Job pulls aircraft metadata (FLARM ID, model, competition sign) from the public OGN DDB.
  - Matched aircraft in our DB are updated; unmatched are logged but not auto-created.
  - Network failure handled gracefully (job logs + Sentry, does not crash).
  - No e2e in legacy (R13); add a smoke test against a recorded OGN response.
estimate: M
adr_refs: [0009]
parity_test: tests/jobs/aircraft-db-sync-smoke.spec.ts (new)
---

## Context
Outbound to OGN DDB (public HTTP, no auth). Read-only on their side.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] HTTP client to OGN DDB.
- [ ] Parse response (legacy uses a specific format — confirm).
- [ ] Match by FLARM ID or immat; update fields.
- [ ] Smoke test with recorded fixture.

## Notes
Don't auto-create aircraft from OGN sync — that's a tenancy-violating shape (which club would they belong to?). Match existing only.
