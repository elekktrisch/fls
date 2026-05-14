---
id: E-02
title: Database schema, migration tooling & data migration
status: todo
adr_refs: [0002, 0003]
---

## Goal
Design the Postgres 17 target schema, wire Flyway as the migration driver, build the one-shot SQL Server → Postgres data migration, and rehearse it inside the 6-hour cutover budget (C6) at least twice against production-shaped staging data.

## Scope
- In: target schema design, Flyway wiring, parity-baseline extraction from current SQL Server, one-shot data migration script, verification automation (row counts + FK integrity + sampled value diff), Testcontainers test-DB strategy, ShedLock stub.
- Out: tenancy filter implementation (E-03); RLS hardening (deferred); ongoing schema evolution past V1__baseline (lives in feature epics).

## Stories
- [ ] S-009 — Wire Flyway into Spring Boot + V1__baseline placeholder
- [ ] S-010 — Extract production-schema parity baseline
- [ ] S-011 — Catalog tenant-scoped vs cross-tenant entities
- [ ] S-012 — V1__baseline part 1: identity + reference data
- [ ] S-013 — V1__baseline part 2: flights / aircraft / persons / clubs / locations
- [ ] S-014 — V1__baseline part 3: reservations / planning / accounting
- [ ] S-015 — Testcontainers test-DB strategy + helpers
- [ ] S-016 — One-shot data-migration script + verification automation
- [ ] S-017 — Data-migration rehearsal #1 (production-shaped staging)
- [ ] S-018 — ShedLock stub table in Flyway baseline

## Done when
- `flyway migrate` from an empty Postgres produces a schema bit-equivalent to the design (column types, indexes, FKs, check constraints) and a Spring Boot test confirms it.
- Migration rehearsal completes in <6 hr against a production-row-count dataset; verification automation produces a zero-delta report (row counts per table; FK integrity; sampled value diff on Flight, Delivery, PersonClub).
- Test DB strategy is documented and every existing test class can be migrated to the chosen pattern without per-test boilerplate.
