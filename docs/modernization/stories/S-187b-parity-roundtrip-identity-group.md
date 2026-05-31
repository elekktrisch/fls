---
id: S-187b
title: Parity round-trip — IDENTITY-group remainder (Person + categories + audit) + two-pass UPDATE
epic: E-02
status: todo
depends_on: [S-187a, S-184]
integration_base: integration/migration
origin: scope-split
origin_story: S-187a
refined: false
acceptance:
  - **IDENTITY-group bindings.** Extend `MapperLegacyBindings` with the producer SELECT + consumer INSERT for PERSON, MEMBER_STATE, PERSON_CATEGORY, PERSON_CLUB, PERSON_CATEGORY_ASSIGNMENT, AUDIT_LOG, against the canonical FLSTest schema.
  - **Fixtures.** `LegacyFixtureSeeder` seeds these per Club, including the three audit-actor shapes (real, orphan, NULL) + orphan-dedupe, and a PersonCategory self-FK tree with a tombstoned ref.
  - **Two-pass UPDATE** for `PersonCategory.parent_person_category_id` (pass-1 insert NULL, pass-2 resolve via `legacy_id_map_*`); self-FK to a tombstoned/dropped parent → resolve-to-NULL, keep child. FK sweep zero orphans post-pass-2.
  - **Cross-tenant Person sub-map** resolver widening in `LegacyIdMapPopulator` / `ForeignKeyRewriter`; tenant-isolation invariant asserted against `Manifest.tenantBypassAllowList()`.
  - Round-trip green in CI's parity job for the extended mapper set.
estimate: M
adr_refs: [0008, 0019, 0022, 0023]
parity_test: alpenflight/migration-bundle/src/parity/java/ch/alpenflight/migration/bundle/parity/ParityOracleHarnessTest.java
---

## Context

Scope-split from [S-187a](S-187a-parity-harness-remaining-mappers-and-gates.md). S-187a built the container-independent parity machinery (coverage gate, drop reconciliation, fail-closed PII redactor, mutation-smoke decorator, FK-orphan plan, tenant accessor) with unit tests, plus the `parity-reject`/`parity-meta` Gradle tasks. The schema-coupled breadth — extending the live MSSQL→Postgres round-trip to the remaining mappers — was deferred to per-group follow-ups because it needs the FLSTest MSSQL container (unavailable in the dev sandbox; verified only in CI's parity job).

This story extends the round-trip to the remaining IDENTITY-group mappers and wires the two-pass UPDATE machinery the machinery story prepared. See S-187a's refinement block for the load-bearing decisions (two-pass resolve-to-NULL, tenant invariant, audit-actor shapes).
