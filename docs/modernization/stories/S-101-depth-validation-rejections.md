---
id: S-101
title: Expand Playwright depth — validation rejection paths
epic: E-13
status: todo
depends_on: []
acceptance:
  - New specs added under `e2e/tests/` (legacy suite) cover validation rejection paths *against the legacy system first*: invalid crew composition, missing required fields, out-of-range timestamps, conflicting glider/tow assignments, reservation overlaps.
  - All new specs are green on the legacy system before being merged.
  - Specs run unchanged against the new system once E-07/E-08 land, and pass.
estimate: L
adr_refs: []
parity_test: self
---

## Context
R14 callout — legacy specs are happy-path only. Validation depth is unprobed. Closing this gap pre-cutover is the only way to guarantee parity on rejection behavior.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Catalog the rejection paths from `FlightService.cs` and other validators.
- [ ] Write one spec per rejection class.
- [ ] Verify green on legacy.
- [ ] Re-run against new system as it lands.

## Notes
This story runs *concurrently with feature ports*, not after. The depth tests target legacy first — they describe the system's existing behavior. The new system implements parity to that behavior.

L because the rejection-path catalog is broad. Tasks split it; can be parallelized by domain (flights, reservations, planning).
