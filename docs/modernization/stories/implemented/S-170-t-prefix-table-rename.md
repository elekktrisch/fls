---
id: S-170
title: Apply `t_` prefix to all tables for naming consistency
epic: E-04
status: done
started_at: 2026-05-27
done_at: 2026-05-27
merged: true
merged_at: 2026-05-27
estimate: M
parity_test: none
depends_on: [S-052]
integration_base: integration/users-suite
adr_refs: [0022, 0025]
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements-engineer, solution-architect, qa-engineer]
github_issue: 141
github_pr: 142
origin: scope-split
origin_story: S-052
---

## Context

S-052 introduced `t_user` to dodge the Postgres reserved-word collision
on `user`. The operator chose the `t_` prefix as a project-wide
convention; this story swept the convention across every remaining
table so the schema reads consistently. The convention itself is pinned
in [ADR 0025](../adrs/0025-table-naming-convention.md).

## Acceptance criteria

- Every domain / reference table in V1..V13 carries the `t_` prefix.
- `@Table(name = ...)` on every entity matches.
- `flyway_schema_history` keeps its Flyway-default name.
- FK / UQ / CK / IX names + column names stay legacy-shaped (ADR 0025
  cost-vs-consistency decision).
- ArchUnit `NamingRulesTest` fails the build on a future `@Entity`
  without `@Table(name = "t_…")`.

## Operator decisions captured at refine + mid-implementation

- `app_meta` was **dropped entirely** (mid-implementation) rather than
  renamed to `t_app_meta`. It was a hand-maintained mirror of
  `flyway_schema_history`; Flyway's own catalog is the canonical
  source for schema-generation tracking. `FlywayBootstrapIntegrationTest`
  now reads `flyway_schema_history` directly.
- Constraint / index / column names left legacy-shaped (`fk_aircraft_…`,
  `ux_article_…`, `ix_*`, `club_id`, `created_on`). Renaming would
  ripple to ~17 exception-translation `causeMessage.contains("fk_…")`
  call sites; visible "prefixed table, unprefixed FK" inconsistency
  accepted as the lower-cost path.
- New ADR 0025 (not amendment to 0022) — keeps the convention as a
  self-contained, greppable file rather than buried in the primary-
  directives manifesto.

## Follow-ups

- `LeakageSweepIT` strips the `t_` prefix when reconstructing the legacy
  FK name string — if a future story sweeps FK names too, that's one of
  the change points.
- `MigrationFolderConventionsTest` lost `v1_baseline_is_non_empty`
  (V1 is intentionally empty post-`app_meta`-drop).
