---
id: S-184
title: Migration-bundle — identity sub-package mappers
epic: E-02
status: todo
depends_on: [S-183]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: false
acceptance:
  - All `EntityType` members in `Group.IDENTITY` ship a concrete `Mapper` under `ch.alpenflight.migration.bundle.identity.*` — `Language`, `ClubState`, `MemberState`, `PersonCategory`, `Role`, `Club`, `Person`, `PersonClub`, `User`, `UserRole`.
  - Each mapper implements bidirectional `writeNdjson` (export-side) + `readEntity` (ingest-side) per the contract S-183 pinned on the `Mapper` interface.
  - Each mapper declares its FK targets via `foreignKeys()`. SYSTEM_GLOBAL refs resolve through `legacy_int_id`; TENANT_SCOPED refs declare the per-bundle map dependency.
  - Each mapper has a `AbstractMapperContractTest`-derived unit test that passes the contract suite — including the `Mapper.columns()` defensive-copy invariant and the round-trip identity modulo `@ParityIgnore` columns.
  - Cross-tenant Person sub-map: `Person` mapper FK declarations flag `PrimaryClubId` as tenant-bypass (per ADR 0008).
estimate: M
adr_refs: [0002, 0003, 0008, 0019, 0022]
---

## Context

Scope-split from [S-183](S-183-migration-bundle-mappers-and-parity-oracle.md). S-183 ships the `Mapper` contract + scaffolding + the `Country` sample; this story fills the remaining identity-group entities. Each mapper is a thin column-list + binding pair against the pinned interface — no new design decisions expected.

Identity mappers must land before the parity oracle harness (S-187) can run a real round-trip, and before `S-141` (encrypted-bundle ingest) can wire concrete writers.

## Cross-story contracts

- **Consumes:** S-183's `Mapper` / `Manifest` / `LegacyIdMapWriter` / `AbstractMapperContractTest` scaffolding.
- **Produces:** Identity-group `Mapper` implementations consumed by S-141 (ingest) and S-187 (parity oracle).

## Notes

- Refinement should re-verify the legacy → new column mapping per entity against the post-V16 destination schema and the post-final-DBUpdate FLSTest source schema. `MapperVsSchemaCompatibilityTest` lands at S-187, so this story relies on PR-time review for column-list correctness.
