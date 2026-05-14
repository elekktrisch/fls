---
id: S-051
title: Persons CRUD + PersonClub many-to-many
epic: E-06
status: todo
depends_on: [S-048, S-047]
acceptance:
  - `Person` (cross-tenant) + `PersonClub` (M:N junction with `member_number`, `member_state_id`, role flags, notification prefs) ported.
  - The add-person modal works (creates a Person + a PersonClub row for the current club in one transaction).
  - A Person can belong to multiple clubs via multiple PersonClub rows; the Person edit screen shows all club memberships.
  - Spec `13-persons-add-modal.spec.ts` passes.
estimate: L
adr_refs: [0005, 0008]
parity_test: tests/masterdata/13-persons-add-modal.spec.ts
---

## Context
**Sacred-cow shape** — Person/PersonClub split underpins multi-club pilot rosters. Get this wrong and the system breaks at multi-club glider sites.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `Person` entity *without* `@TenantId` (cross-tenant per S-011).
- [ ] `PersonClub` entity *with* `@TenantId` on `club_id`.
- [ ] Query patterns: list persons in this club; add existing person to this club; create new person + add to this club.
- [ ] Person edit screen showing all clubs the person is in (system admin only sees all; club admin only sees own club's data on that person).
- [ ] Add-person modal preserves the legacy UX.
- [ ] Spec verification.

## Notes
This is L because the cross-tenant aspect requires careful query design. The leakage CI test (S-024) must pass; PersonClub is tenant-scoped, but Person is the gray-area where careful query design matters.

A query like "who are the pilots for this club" returns `Person` rows joined through `PersonClub` filtered by tenant — works correctly because the join's filtering happens via PersonClub's `@TenantId`.
