# 0002 — Database engine

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): off-EOL & long-supported · Linux-first · Swiss/EU residency · credible migration story · lower TCO · mature ecosystem

## Context

The current engine is SQL Server (assumed; production engine isn't asserted in this repo per [current-state §6](../01-current-state.md#database)). C1 forbids Windows dependencies and C4 requires Swiss/EU-region hosting; both can be honored by SQL Server on Linux, but doing so re-imports the Microsoft licensing surface we are trying to leave. C9 explicitly allows schema reshape with a one-shot migration — so the engine is free to change. [ADR 0001](0001-backend-language-and-framework.md) chose Spring Boot, which gives us first-class Hibernate dialects for every major engine; engine choice is no longer constrained by the ORM.

This decision sets the dialect for all subsequent queries, the migration baseline ([ADR 0003](.)), the multi-tenancy mechanism's options ([ADR 0008](.)), and the hosting recipe ([ADR 0010](.)).

## Options considered

### Option A — Postgres 17
- **Capabilities:** ANSI SQL + rich extensions (JSONB, full-text search, partial indexes, row-level security, generated columns, pgcrypto). Hibernate dialect is excellent. Streaming replication, logical replication, point-in-time recovery all production-grade. Available as managed service in every Swiss/EU region (Hetzner, Exoscale, Scaleway, AWS eu-central, Azure Switzerland North).
- **Fit to criteria:** off-EOL ✓ (Postgres 17 supported through 2029). Linux-first ✓. Swiss/EU residency ✓ (every Swiss VPS provider offers it). Migration story ✓ — well-documented SQL-Server-to-Postgres paths (pgloader, AWS Schema Conversion Tool, Babelfish-style emulation if needed; we want reshape so a hand-controlled Flyway baseline is the actual path). TCO ✓ (no license fees). Ecosystem ✓ (largest among OSS RDBMSes for JVM).
- **Migration cost:** medium. SQL dialect differences are well-mapped (TOP → LIMIT, GETDATE() → NOW(), NVARCHAR → TEXT/VARCHAR, IDENTITY → GENERATED IDENTITY). Hand-rolled DBUpdate scripts ([R7](../01-current-state.md#r7--hand-rolled-sql-migration-baseline)) need rewriting as Flyway migrations against the new schema, not auto-translated.
- **Ecosystem risk:** low — second-most-used RDBMS overall, growing share, no vendor capture.
- **Escape hatch:** ANSI SQL queries are portable; JSONB and RLS are the lock-in surface (small and well-understood).

### Option B — MariaDB 11 / MySQL 8
- **Capabilities:** OSS, ubiquitous, Linux-native. JSON support exists but less ergonomic than JSONB. No native row-level security (would need to enforce tenancy at the application layer regardless).
- **Fit to criteria:** off-EOL ✓. Linux-first ✓. Residency ✓. TCO ✓. Ecosystem ✓ but slightly less aligned with our specific needs (RLS, complex JSON, generated columns).
- **Migration cost:** similar to Postgres; the dialect translation is comparable.
- **Ecosystem risk:** low.
- **Escape hatch:** ANSI SQL portable.

### Option C — SQL Server on Linux
- **Capabilities:** identical to current; Linux container available.
- **Fit to criteria:** Linux ✓ technically. Residency ✓. TCO ✗ (Microsoft licensing carries forward — Standard edition pricing or Developer-only-for-non-prod). Off-EOL ✓. Ecosystem ✓.
- **Migration cost:** lowest (no SQL dialect change).
- **Ecosystem risk:** medium — re-imports the vendor lock-in we just escaped on the backend side.
- **Escape hatch:** another full migration later, which defeats the rewrite.

### Option D — SQLite
- **Capabilities:** single-file, embedded.
- **Fit to criteria:** Linux ✓, TCO ✓ (free) — but fails on concurrency: OGN ingestion + scheduled jobs + interactive users + delivery export all writing simultaneously is not a SQLite workload. Multi-tenancy with row-level guards is awkward.
- **Migration cost:** small but irrelevant given the fit failure.
- **Rejected up front** — listed only to make the rejection explicit.

## Decision

Chosen: **Option A — Postgres 17**. Best simultaneous fit across criteria 1, 3, 4, 9, 10, and 11. JSONB gives us a clean place for the kind of semi-structured config the accounting rules engine stores ([R3](../01-current-state.md#r3--accounting-rules-engine-parity-critical-customer-configurable)) without forcing schema-design gymnastics. RLS is available as a defense-in-depth fallback to the application-layer tenant guard chosen in [ADR 0008](.) without committing to it. Hosting in Switzerland or the EU is trivial — every Swiss VPS provider offers managed Postgres, and self-hosting on a single VPS is a one-line docker-compose entry.

## Consequences

- **Positive:**
  - Zero license cost; aligns with O5 (lower TCO).
  - JSONB is a natural home for `AccountingRuleFilter`-shaped config and audit-log payloads.
  - Row-level security is available as a defense-in-depth option beyond the query-layer guard.
  - First-class Spring Data JPA support via the `org.postgresql:postgresql` driver.
  - `pg_dump`/`pg_restore` and logical replication make the cutover rehearsal practical.

- **Negative:**
  - SQL dialect change from SQL Server is real work in the migration scripts ([ADR 0003](.) follow-up).
  - OGN inbound (currently direct SQL writes to a SQL Server schema, [R9](../01-current-state.md#r9--ogn-inbound-contract-is-direct-db-writes)) must now go through an API ([C8](../02-vision-and-constraints.md#3-hard-constraints)) — confirmed, but the schema change makes the API path mandatory rather than optional.
  - Backup / restore / monitoring tooling differs; ops runbook is new.

- **Follow-ups (other ADRs / stories implied):**
  - **ADR 0003** (Schema migration tooling) — needs a Postgres-friendly tool (Flyway and Liquibase both excel here).
  - **ADR 0008** (Multi-tenancy mechanism) — Postgres RLS becomes a real option alongside Hibernate `@Filter`.
  - **ADR 0010** (Hosting) — choose between self-hosted Postgres (docker-compose) and a managed offering.
  - **ADR 0011** (Observability) — pick a Postgres metrics exporter (postgres_exporter) and slow-query log shipping.
  - **Story:** capture a parity baseline of the current SQL Server schema (table list, column types, constraints, indexes) so the new Postgres schema can be compared row-by-row in [C11](../02-vision-and-constraints.md#3-hard-constraints) verification.
  - **Story:** design the `alpenflight/database/` layout — migration scripts, seed data, fixture for tests.
  - **Story:** spike a data-migration script from production-shaped SQL Server data to Postgres, end-to-end, to validate the cutover-window budget ([C6](../02-vision-and-constraints.md#3-hard-constraints) ≤6 hrs).
