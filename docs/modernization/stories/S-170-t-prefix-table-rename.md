---
id: S-170
title: Apply `t_` prefix to all tables for naming consistency
epic: E-04
status: todo
estimate: M
parity_test: none
depends_on: [S-052]
integration_base: integration/users-suite
adr_refs: [0022]
refined: false
origin: scope-split
origin_story: S-052
---

## Context

S-052 introduced the `t_user` table name (instead of relying on quoting
`"user"`) because `user` is a Postgres reserved word and the unquoted
identifier requires a JPA workaround that's easy to get wrong. The
operator then chose the `t_` prefix convention as a project-wide
direction; S-052 only applied it where the reserved-word collision
forced it (the `user` table).

This story sweeps the convention across every remaining table so the
schema reads consistently.

## Acceptance criteria

- Every domain / reference table in every shipped Flyway migration
  carries the `t_` prefix: `t_club`, `t_person`, `t_person_club`,
  `t_country`, `t_language`, `t_club_state`, `t_start_type`,
  `t_length_unit_type`, `t_elevation_unit_type`,
  `t_counter_unit_type`, `t_extension_type`, `t_member_state`,
  `t_person_category`, `t_club_extension`, `t_email_template`,
  `t_extension_value`, plus every V3+ table (flight, aircraft,
  location, flight_type, …, mutation_audit_event).
- Every JPA `@Table(name = ...)` annotation matches.
- Every native SQL query (`@Query(nativeQuery = true)`, raw JDBC,
  ArchUnit string checks) is updated.
- `IdentityBaselineIntegrationTest` and any other migration-shape test
  expectations updated.
- `flyway_schema_history` keeps the legacy name (Flyway-owned, not
  ours; would break Flyway resume on existing databases).
- An ADR amendment to ADR 0022 (or a new ADR) pins the convention so
  future tables land prefixed.

## Notes

- No Flyway migrations have been shipped yet, so amending V1..V13 in
  place is acceptable (same precedent S-052 used for `t_user`).
- OpenAPI snapshot regenerates from the live spec — local Postgres
  via `DATASOURCE_URL` is the supported path (matches the dev compose
  / `~/.bashrc` defaults).
