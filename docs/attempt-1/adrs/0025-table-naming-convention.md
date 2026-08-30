# 0025 — Table naming convention: `t_` prefix

- **Status:** Accepted
- **Date:** 2026-05-27
- **Scope:** Every table created by an AlpenFlight Flyway migration in the
  `public` schema. Flyway-managed bookkeeping tables (`flyway_schema_history`)
  are out of scope.

## Context

Postgres reserves several identifiers that are common in a glider-club
domain (`user` is the most painful). [S-052](../stories/implemented/S-052-users-crud.md)
worked around the collision by naming the table `t_user` instead of
double-quoting `"user"` — quoting works but ripples into every JPA
annotation, every native SQL string, and every `regclass` cast, and is
easy to get wrong. The operator chose the `t_` prefix as a project-wide
convention; S-170 swept the convention across every remaining table so
the schema reads consistently.

[ADR 0022](0022-modernization-primary-directives.md) Directive 1 (working
software over comprehensive documentation) prefers an automated guardrail
to a remembered convention. A standalone ADR keeps the naming decision
greppable as one file rather than a clause buried in 0022's primary-
directives manifesto.

## Decision

Every domain or reference table created by an AlpenFlight Flyway
migration carries a `t_` prefix on the table identifier:

- `t_user`, `t_club`, `t_aircraft`, `t_flight`, … — domain tables.
- `t_country`, `t_language`, `t_club_state`, … — reference tables.
- `t_mutation_audit_event` — cross-cutting infrastructure tables that
  AlpenFlight migrations own.

**Exception:** `flyway_schema_history` keeps its Flyway-default name —
renaming it would break Flyway's own resume semantics on any database
that has already migrated past V1.

**Scope of `t_`:** the prefix applies to **table identifiers only**.
Constraint names (FKs / UQs / CKs), index names, and column names keep
their existing semantic shape (`fk_aircraft_owner_club_id`,
`ux_article_club_number`, `ix_flight_club_id_started_on`,
`club_id`, `created_on`). Renaming these would ripple through ~17
exception-translation `causeMessage.contains("fk_…")` call sites and
buys little; visible inconsistency (prefixed table, unprefixed FK) is
accepted as the cost-vs-consistency tradeoff.

## Consequences

- **Positive:**
  - No quoting / no Postgres reserved-word surprises in any future
    domain table.
  - JPA `@Table(name = "...")` annotations + native SQL strings are
    greppable for a table by its full identifier.
  - A new `@Entity` class without an explicit `@Table(name = "t_…")`
    fails the build via the `NamingRulesTest` ArchUnit rule — the
    convention is enforced, not documented.
  - `TableNamingConventionTest` (DB-side) catches drift at runtime;
    `FixtureTableNamingConventionTest` catches it in raw-JDBC test
    fixtures.

- **Negative:**
  - One-time cost of the S-170 sweep (in-place amend of V1..V13 +
    all `@Table` annotations + ~30 IT fixtures). Manageable because no
    Flyway migration had shipped externally.
  - Visible inconsistency: tables are `t_`-prefixed but FK / UQ / IX
    names are not. Operator weighed and accepted.
  - Developers must drop+recreate local dev DBs the first time they
    pull S-170; `~/.bashrc` DATASOURCE workflow handles this in one
    command.

- **Follow-ups:**
  - **Done in S-170**: V1..V13 migrations renamed in place; `@Table`
    annotations updated; native SQL in `platform.tenancy` JDBC aisle +
    audit + me modules updated; ArchUnit `NamingRulesTest` rule landed;
    `TableNamingConventionTest` + `FixtureTableNamingConventionTest`
    landed.
  - The redundant `app_meta` sentinel table (a hand-maintained mirror of
    `flyway_schema_history`) was deleted in S-170 — Flyway's own
    catalog is the canonical source for schema generation.
