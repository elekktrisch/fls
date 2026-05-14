# 0003 — Schema migration tooling

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): credible migration story · solo-operator operability · mature ecosystem

## Context

Production schema today is driven by hand-rolled `database/FLS/Updates/DBUpdate_v*.sql` scripts ([R7](../01-current-state.md#r7--hand-rolled-sql-migration-baseline), 11 scripts on top of a 40-table base alter file). EF Code First migrations exist in the source tree but are not the runtime driver. The new stack ([ADR 0001](0001-backend-language-and-framework.md) Spring Boot, [ADR 0002](0002-database-engine.md) Postgres) needs a versioned, checksummed, automation-friendly tool. The tool also has to support a one-shot baseline-load step where we ingest production data on cutover ([C11](../02-vision-and-constraints.md#3-hard-constraints)).

## Options considered

### Option A — Flyway 10/11
- **Capabilities:** SQL-first versioned migrations (`V<n>__<name>.sql`), Java-based migrations for tricky cases, repeatable migrations (`R__*.sql`), baseline-on-migrate for legacy schemas, callbacks. Spring Boot autoconfig ships standard; one dependency, one property to opt in.
- **Fit to criteria:** mature ecosystem ✓, solo-operator operability ✓ (mental model is "files in a folder, run on startup"), credible migration story ✓ (the team already writes raw SQL, so Flyway's V-files are the same artifact with a stricter naming convention).
- **Migration cost:** low. The existing `DBUpdate_v*.sql` scripts inform the new schema design but don't transfer 1:1 — the rewrite reshapes the schema ([C9](../02-vision-and-constraints.md#3-hard-constraints)). New project starts with `V1__baseline.sql` defining the target schema.
- **Ecosystem risk:** low. OSS Community edition has lost some features to paid tiers over the years (undo, multi-database orchestration) but the SQL-versioning core is unchanged and our needs sit in the free tier.
- **Escape hatch:** migrations are plain SQL files — re-runnable by hand or under any other tool that can apply versioned SQL.

### Option B — Liquibase
- **Capabilities:** XML/YAML/SQL changesets, contexts, preconditions, rollback DSL, change tracking. More features than Flyway, more concepts to learn.
- **Fit to criteria:** mature ecosystem ✓, solo-operator operability ~ (the abstraction tax is real if all you need is "apply these SQL files in order"), credible migration story ✓.
- **Migration cost:** medium — the team would learn the changeset model and pick a serialization format.
- **Ecosystem risk:** low.
- **Escape hatch:** can export changesets to SQL.

### Option C — Hibernate `hbm2ddl` (auto-DDL)
- **Capabilities:** Hibernate generates / applies schema from JPA entity annotations.
- **Fit to criteria:** prod-schema mutation by ORM auto-DDL is industry-rejected for good reasons (no atomic data migrations, no version tracking, no rollback). Acceptable for `create-drop` in tests; not for prod.
- **Rejected up front** — but listed so the rejection is recorded.

### Option D — Bare psql + Makefile scripts
- **Capabilities:** apply SQL files in a chosen order via `psql -f`.
- **Fit to criteria:** matches the current `DBUpdate_v*.sql` model. Loses checksumming, history tracking, atomic out-of-order detection.
- **Rejected up front** — repeats R7 verbatim.

## Decision

Chosen: **Option A — Flyway**. SQL-first matches the team's existing relationship with the schema (they already write raw SQL); Spring Boot autoconfig makes adoption a one-line dependency + one property; the feature ceiling matches our needs without learning a changeset DSL. Liquibase's extra ergonomics (rollback DSL, preconditions) aren't worth the extra concept count for a solo operator.

## Consequences

- **Positive:**
  - Migrations live as plain `.sql` files in version control — diff-friendly, reviewable, runnable outside the app.
  - Boot-time `flyway migrate` makes deploys idempotent.
  - The cutover-data-migration step becomes a normal Flyway migration (or a Flyway callback) — no special-case tooling.
  - Java-based migrations (`V<n>__Name.java`) available as an escape hatch for data backfills too complex for SQL.

- **Negative:**
  - Once a versioned migration is applied to any environment its file content is checksum-locked; fixing a botched migration means writing a new migration on top. Standard discipline.
  - Out-of-order migration handling (`flyway.outOfOrder`) must be configured deliberately; default is strict.
  - The Community OSS edition's feature ceiling is below the paid one (no native undo, no multi-DB orchestration). We don't need either, but if a future ADR introduces a second DB the limitation should be revisited.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** scaffold `next/database/` (or a `db/migration` folder inside `next/server/`) with `V1__baseline.sql` defining the target schema.
  - **Story:** establish a parity baseline (table list, column types, constraints) extracted from the current SQL Server DB; use it as the spec for `V1__baseline.sql`.
  - **Story:** write the one-shot data-migration step that loads production data into the new schema; rehearse against a production-shaped staging DB at least twice ([C6](../02-vision-and-constraints.md#3-hard-constraints) ≤6 hr budget).
  - **Story:** wire `flyway:validate` and `flyway:info` into CI so drift between branches is caught at PR time.
  - **Story (test infra):** decide on test-DB strategy — Testcontainers Postgres + clean migration on every test class, vs. transactional rollback per test, vs. shared schema with truncation. Phase-4 task.
