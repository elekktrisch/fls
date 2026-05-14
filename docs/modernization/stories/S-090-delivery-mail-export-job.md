---
id: S-090
title: Port DeliveryMailExportJob (POI + ZipOutputStream)
epic: E-10
status: todo
depends_on: [S-082, S-094, S-078]
acceptance:
  - Job bundles `DeliveryPrepared` deliveries into per-recipient Excel files using SXSSF (streaming POI).
  - Per-recipient Excel files are zipped via `java.util.zip.ZipOutputStream`.
  - Zip attached to one email per club (or per recipient — confirm legacy behavior).
  - Affected deliveries marked `IsFurtherProcessed=true` (or equivalent flag).
  - No e2e in legacy (R13); add a smoke test that confirms a zip attachment with one Excel per recipient lands in Mailpit.
estimate: L
adr_refs: [0009, 0012, 0013]
parity_test: tests/jobs/delivery-mail-export-smoke.spec.ts (new)
---

## Context
The biggest scheduled job by I/O volume. Combines POI streaming + ZipOutputStream + email attachment. Closes R13's gap.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Job class.
- [ ] Group deliveries by recipient.
- [ ] Per-recipient: build Excel via SXSSF; write to a `ZipOutputStream`.
- [ ] Email with the zip attached.
- [ ] Mark deliveries as exported.
- [ ] Smoke test.
- [ ] Run S-096 parity harness against a fixture.

## Notes
L because the streaming + zipping + email-with-attachment chain has real wiring. Tasks split it.

The "IsFurtherProcessed" semantics — confirm. Some jobs in legacy clear and re-run; this one shouldn't, but verify.
