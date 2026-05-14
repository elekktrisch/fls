---
id: S-011
title: Catalog tenant-scoped vs cross-tenant entities
epic: E-02
status: todo
depends_on: [S-010]
acceptance:
  - A reference doc `next/database/tenant-catalog.md` lists every entity in two columns: tenant-scoped (carries `club_id`) vs. cross-tenant (no `club_id`).
  - The doc explains the rationale per entity (especially the gray-area ones: `Person`, `Aircraft`, `Location`).
  - Public-flow targets (TrialFlightRegistration, PassengerFlightRegistration) have a documented tenant-derivation strategy (URL slug → club_id allowlist).
estimate: S
adr_refs: [0008]
parity_test: none
---

## Context
ADR 0008 + the cross-tenant-Person edge case (a Flight's crew can reference a Person from another club via PersonClub) make this classification non-trivial. Getting it wrong here causes either: (a) leaks (R1), or (b) breaks multi-club pilot rosters (sacred cow).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Read `01-current-state.md §5` carefully — note the cross-cluster constraints.
- [ ] Classify every entity from S-010's baseline.
- [ ] For gray-area entities (Person, Aircraft if shared, Location if shared), document the rule: which queries are tenant-scoped, which are cross-tenant by design.
- [ ] Flag the cross-tenant references that ride through tenant-scoped entities (e.g. `Flight.PersonId → Person` where Person has no `club_id` — Flight's `club_id` is the operative tenant).
- [ ] Cross-check the catalog with [S-024](#) (the leakage CI test will run against this list).

## Notes
The legacy convention is "every entity has `club_id` and every query filters on it." The reality is more nuanced: reference data (Country, LanguageTranslation), `User` (single `ClubId` per user, not a tenant attribute on rows), and `Person` (cross-tenant via `PersonClub`) all sit outside the tenant-scoped rule. Get this catalog right or downstream stories break.
