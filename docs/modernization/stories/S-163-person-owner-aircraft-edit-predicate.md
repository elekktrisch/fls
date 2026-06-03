---
id: S-163
rolled_up_into: J-1
title: Extend `AircraftAccess.canEdit` to admit the person matching `aircraft_owner_person_id`
epic: E-07
status: todo
estimate: S
depends_on: [S-052, S-058]
origin: rework-meta
origin_story: S-058
kind: deferred-feature
adr_refs: [0008, 0022]
parity_test: none
refined: false
---

## Context

S-058 ships three Aircraft owner columns:

- `managing_club_id` (NOT NULL) — the operational manager; gates writes
  through `AircraftAccess.canEdit`.
- `owner_club_id` (NULL OK) — physical-owner metadata.
- `aircraft_owner_person_id` (NULL OK) — private-person owner metadata.

The third column carries no edit power today: even when an Aircraft is
owned by a private person, only `managing_club_id`'s CLUB_ADMINISTRATOR (or
SYSTEM_ADMINISTRATOR) can edit it. That gap is intentional at S-058 scope —
the system has no concept yet of "User → Person" identity binding, so we
can't recognize "the caller IS the owning person."

S-052 (Users CRUD) introduces the `User.personId` linkage. Once a logged-in
user can be resolved to a `Person`, `AircraftAccess.canEdit` should admit
the caller when `aircraft_owner_person_id` equals their resolved `personId`.

## Acceptance criteria (placeholder until refined)

- `AircraftAccess.canEdit(id)` returns true when the caller's resolved
  `personId` matches the aircraft's `aircraft_owner_person_id`, in addition
  to the existing managing-club CLUB_ADMINISTRATOR / SYSTEM_ADMINISTRATOR
  paths.
- The predicate has no effect on aircraft where `aircraft_owner_person_id`
  is null.
- A test pins the new path: a USER role caller, no admin role, with
  `personId = aircraft.owner_person` edits successfully; a different USER
  is rejected.
- Schema unchanged; pure SpEL bean extension.

## Notes

- Open at refine: does the predicate also apply to `canOperate` (state /
  counter advance) and `canRegister`? Likely no for `canRegister` (the
  owner registers via the managing club's flow), maybe yes for `canOperate`
  (a private owner advances their own engine counter).
