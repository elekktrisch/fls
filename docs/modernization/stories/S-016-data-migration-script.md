---
id: S-016
title: Legacy schema-mapping library + parity oracle
epic: E-02
status: todo
depends_on: [S-012, S-013, S-014]
acceptance:
  - `alpenflight/migration-bundle/` library provides one mapper per legacy entity cluster (~60 entity types): per-entity column lists, type coercions, FK rewrites, enum re-encodings (e.g. legacy `BOOLEAN` → string-serialized enums per S-129), tenant-scoping defaults.
  - Library is consumed by S-139 (JAR bundle-writer) AND S-141 (server ingest pipeline) — single source of truth for "what's in the bundle".
  - Mappers cover every legacy table in the S-011 tenant-scoped-entities catalog plus cross-tenant tables (audit, system data).
  - Parity oracle in CI: row-count diff, FK-integrity check, 1% sampled-value diff on Flight / Delivery / PersonClub / AircraftReservation / AccountingRuleFilter against a seeded legacy SQL Server fixture. Fails loud on regression.
  - Machine-readable verification output (JSON) alongside a human-readable report — CI asserts on the JSON.
estimate: L
adr_refs: [0002, 0003, 0019]
parity_test: tests/migration/schema-parity.spec.ts (new)
---

## Context
The transport for legacy-to-new data is the JAR (S-139) + upload pipeline (S-141). This story owns the *content* both sides depend on: the entity-by-entity mapping rules, and the verification automation that proves a round-trip preserves the data.

Per memory `[[feedback-re-runnable-over-frozen-docs]]`: the parity oracle re-exports from a seeded legacy DB on every CI run, never a committed bundle.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Bootstrap `alpenflight/migration-bundle/` Gradle module; published as an internal Maven artifact consumed by `alpenflight/server/` and `alpenflight/migration-tool/`.
- [ ] One mapper per entity cluster (S-012 / S-013 / S-014 cover the schema groupings).
- [ ] FK-integrity check (every FK in the new schema resolves).
- [ ] Row-count check (per-tenant + total).
- [ ] Sampled-value check (random 1% per table, value-compare key columns).
- [ ] Seed a SQL Server fixture in CI (Testcontainers); re-export through S-139's JAR; round-trip through S-141; diff.
- [ ] Emit JSON + markdown verification reports.

## Notes
- Complexity is real (~60 entity types). Plan for ~10 working days even with transport out of scope.
- Tables migrated in topological FK order; FK constraints disabled during bulk insert then re-enabled — handled inside S-141's ingest, not here.
