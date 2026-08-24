---
id: E-02
title: Database schema & legacy schema-mapping library
status: todo
adr_refs: [0002, 0003, 0019]
---

## Goal
Design the Postgres 17 target schema, wire Flyway as the migration driver, and build the legacy schema-mapping library (`alpenflight/migration-bundle/`) shared by the export JAR (S-139) and the server ingest pipeline (S-141). Parity oracle in CI exercises the full JAR → upload → ingest path on every push against a seeded legacy SQL Server fixture.

## Scope
- In: target schema design, Flyway wiring, parity-baseline extraction from current SQL Server, schema-mapping library, parity oracle (row counts + FK integrity + sampled-value diffs), Testcontainers test-DB strategy, ShedLock stub.
- Out: tenancy filter implementation (E-03); RLS hardening (deferred); ongoing schema evolution past V1__baseline (lives in feature epics); the JAR + upload pipeline transport (lives in E-15).

## Stories
- [ ] S-009 — Wire Flyway into Spring Boot + V1__baseline placeholder
- [ ] S-010 — Extract production-schema parity baseline
- [ ] S-011 — Catalog tenant-scoped vs cross-tenant entities
- [ ] S-012 — V1__baseline part 1: identity + reference data
- [ ] S-013 — V1__baseline part 2: flights / aircraft / persons / clubs / locations
- [ ] S-014 — V1__baseline part 3: reservations / planning / accounting
- [ ] S-015 — Testcontainers test-DB strategy + helpers
- [ ] S-016 — Legacy schema-mapping library + parity oracle
- [ ] S-018 — ShedLock stub table in Flyway baseline

## Done when
- `flyway migrate` from an empty Postgres produces a schema bit-equivalent to the design (column types, indexes, FKs, check constraints) and a Spring Boot test confirms it.
- Parity oracle runs continuously in CI against a seeded legacy SQL Server fixture; row counts per table, FK integrity, and sampled-value diffs on Flight / Delivery / PersonClub / AircraftReservation / AccountingRuleFilter are zero-delta (or documented as known/intentional).
- Test DB strategy is documented and every existing test class can be migrated to the chosen pattern without per-test boilerplate.
