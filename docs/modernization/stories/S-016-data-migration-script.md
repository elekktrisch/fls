---
id: S-016
title: One-shot data-migration script + verification automation
epic: E-02
status: todo
depends_on: [S-012, S-013, S-014]
acceptance:
  - Script `next/database/migrate-from-legacy/` reads production SQL Server (via a snapshot or live read-replica) and writes Postgres conforming to V1__baseline.
  - Verification automation produces a zero-delta report covering: row counts per table; FK integrity; sampled value diff (1% sample) on Flight, Delivery, PersonClub, AircraftReservation, AccountingRuleFilter.
  - Migration is **idempotent** — re-running against a already-migrated DB is a no-op or errors loudly with a clear message.
  - Migration is **bounded** — runs in under 4 hours on production-row-count data (leaves headroom inside the 6-hr cutover budget for verification + DNS + sanity checks).
estimate: L
adr_refs: [0002, 0003]
parity_test: none
---

## Context
The cutover gate. Schema reshape (C9) is allowed only with a validated migration; this is the validation.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Pick a migration tool: hand-rolled JDBC, Spring Batch, pgloader, or AWS SCT. Recommendation: **hand-rolled JDBC with batching + parallel-per-table where safe** — gives full control over column-by-column reshape rules.
- [ ] For each cluster in S-012/S-013/S-014, write a per-table migration step.
- [ ] Write FK-integrity checks (every FK in the new schema resolves).
- [ ] Write row-count checks (per-tenant + total).
- [ ] Write sampled-value checks (random 1% sample per table, value-compare key columns).
- [ ] Build a one-page HTML or markdown report from verification output.
- [ ] Document the order: tables migrated in topological FK order; constraints disabled during bulk insert then re-enabled.

## Notes
This story is L. Tasks decompose it but the inherent complexity is real (~60 entity types). Plan for ~10 working days.
