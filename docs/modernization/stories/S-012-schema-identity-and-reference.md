---
id: S-012
title: V1__baseline part 1 — identity + reference data
epic: E-02
status: todo
depends_on: [S-009, S-010, S-011]
acceptance:
  - Tables defined: `club`, `club_extension`, `club_state`, `user`, `role`, `user_role`, `person`, `person_club`, `country`, `language`, `member_state`, `person_category`, `length_unit_type`, `elevation_unit_type`, `counter_unit_type`, `start_type`, `email_template`, `extension_type`, `extension_value`.
  - PK/FK constraints, NOT NULL where required, indexes on FKs and hot filter columns.
  - `club_id` discriminator column present on tenant-scoped reference tables per S-011 catalog.
  - Flyway migration succeeds against a fresh Postgres in Testcontainers; smoke test asserts table list matches expectation.
estimate: M
adr_refs: [0002, 0003, 0008]
parity_test: none
---

## Context
First chunk of V1__baseline. Identity (User/Person/PersonClub/Club triad) is sacred-cow shape — see seed and ADR 0008.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Define `club` and `club_state`.
- [ ] Define `user` with `club_id` FK (a User is scoped to exactly one club).
- [ ] Define `person` *without* `club_id` (cross-tenant; see S-011).
- [ ] Define `person_club` many-to-many with `member_number`, `member_state_id`, role flags (`is_pilot`, `is_instructor`, `is_trainee`, `is_pax`), notification prefs.
- [ ] Define reference tables (`country`, `language`, `member_state`, `person_category`, unit types, `start_type`).
- [ ] Define `email_template` (per-club template overrides — likely tenant-scoped).
- [ ] Define `extension_type` + `extension_value` for `club_extension`.

## Notes
The `User` ↔ `Person` distinction is sacred. Resist any urge to collapse them. See current-state §5 and seed.

The Keycloak user ID also needs a home — likely a `user.keycloak_sub` column (UUID) for the OIDC subject claim mapping. Decided in S-052 but reserve the column now.
