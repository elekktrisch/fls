---
id: S-187
title: Migration-bundle — parity oracle harness + LegacyFixtureSeeder + MapperVsSchemaCompatibilityTest
epic: E-02
status: todo
depends_on: [S-183, S-184, S-185, S-186]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: false
acceptance:
  - **Parity oracle harness.** Testcontainers MSSQL 2022 (per-class reuse) seeded by `LegacyFixtureSeeder` (Faker-only, deterministic seed) + Testcontainers Postgres 17. Round-trip via in-process call to the producer side of every mapper → in-memory `tar.gz` → consumer side → diff. Reports under `build/reports/parity/<run-id>/{summary.json, report.md, deltas/*.json}`. Asserts `summary.json.passed && totalDeltas==0 && fkOrphans==0`. Gated `@Tag("parity")`, excluded from `./gradlew test`.
  - **Row-count diff** exact per (Club, table). **FK-integrity** via reflective walk of Hibernate-declared FKs; orphan count must be zero. **Sampled-value** 1% sample (`TABLESAMPLE BERNOULLI(1) REPEATABLE(<seed>)`); zero tolerance on sentinel columns (every FK + status enum + monetary + timestamp + generated column). `@ParitySentinel` / `@ParityIgnore` annotations (from S-183) opt columns in/out.
  - **Soft-delete invariant** per soft-deletable entity per Club. Hard fail if tombstones lost.
  - `LegacyFixtureSeeder` Faker-only, seed from sysprop `parity.seed` default `42`, targets post-final-DBUpdate FLSTest schema. ≥ 2 Clubs mandatory (exercises per-bundle Person sub-map). Audit fixture includes three actor shapes: real actor, orphan actor, NULL actor.
  - **`MapperVsSchemaCompatibilityTest`** lives in `alpenflight/server/` (uses live Hibernate `Metadata`). Asserts `bundleMapper.columns() ⊆ hibernateTable.columns()` + every non-nullable non-defaulted column is in `columns()`. Skips `@Generated`, `legacy_int_id` shadow columns, `@TenantId` discriminator.
  - **In-process producer is the temporary affordance.** Harness wires producer side directly until `:migration-tool:shadowJar` (S-139) lands; file a sibling task referenced from S-139's done-criteria for the `ProcessBuilder` swap.
estimate: L
adr_refs: [0002, 0003, 0008, 0019, 0022, 0023]
parity_test: tests/migration/schema-parity.spec.ts (new)
---

## Context

Scope-split from [S-183](S-183-migration-bundle-mappers-and-parity-oracle.md). Once all ~28 mappers (S-184 + S-185 + S-186) land, this story builds the parity oracle harness that round-trips a synthetic legacy bundle through every mapper and asserts zero deltas.

## Cross-story contracts

- **Consumes:** S-183's `Mapper` / `Manifest` / `LegacyIdMapWriter` + S-184/S-185/S-186 mappers. Hibernate `Metadata` via `alpenflight/server/` test context.
- **Produces:** parity oracle that S-141 (ingest) + S-139 (export) and every future migration story rely on as the rehearsal mechanism.

## Notes

- See S-183's refinement block (`<!-- modernize-refine: start --> / end -->` of `S-183-migration-bundle-mappers-and-parity-oracle.md`) for the load-bearing edge-case list: MSSQL `datetime2(7)` precision, monetary `decimal(18,4)`, `TABLESAMPLE` determinism, cross-tenant FK sweep scope, etc. Refinement of this story re-confirms each against the post-mappers code.
