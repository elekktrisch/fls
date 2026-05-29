---
id: S-186
title: Migration-bundle — accounting sub-package + audit-log mappers
epic: E-02
status: todo
depends_on: [S-183]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: false
acceptance:
  - All `EntityType` members in `Group.ACCOUNTING` ship a concrete `Mapper` under `ch.alpenflight.migration.bundle.accounting.*` — `AircraftReservation`, `PlanningDay`, `PlanningDayAssignment`, `Article`, `AccountingRuleFilter`, `Delivery`, `DeliveryItem`.
  - `AUDIT_LOG` ships under `ch.alpenflight.migration.bundle.identity.AuditLogMapper`. Produces rows with `actor_kind='LEGACY_MIGRATED'` + `legacy_actor_user_id` text + NULL `actor_keycloak_sub`. Orphan actor refs → synthetic `legacy_orphan_actor_id` UUID v7 (bundle-local cache, one UUID per distinct legacy-actor string) + warning into `migration_run.warnings`. NULL actor stays NULL.
  - Each mapper implements bidirectional `writeNdjson` + `readEntity` per the contract.
  - Each mapper passes the `AbstractMapperContractTest` suite.
  - **S-027 + S-024 cross-story hand-offs land** with this story — S-027's test plan adds read-back coverage for the `LEGACY_MIGRATED` actor_kind variant (test method in S-027 file under `alpenflight/server/`); S-024's cross-tenant leakage CI exemption list adds Person + audit_log + system tables.
estimate: M
adr_refs: [0002, 0003, 0008, 0019, 0022, 0027]
---

## Context

Scope-split from [S-183](S-183-migration-bundle-mappers-and-parity-oracle.md). Accounting-group + audit-log mappers — the latter carries the orphan-actor synthesis logic that is the load-bearing edge case.

## Cross-story contracts

- **Consumes:** S-183's scaffolding; S-184's `User` mapper for audit-actor lookups; S-185's `Flight` / `FlightCrew` for `Delivery` linkage.
- **Produces:** Accounting-group + audit-log `Mapper`s consumed by S-141 + S-187.
- **Hand-offs (land here, per S-016 + S-183 refinement):** S-027 read-back test + S-024 leakage exemption list update.

## Notes

- Orphan-actor caching is bundle-local (`ON COMMIT DROP`-scoped). Per S-183 refinement: orphan that ALSO appears as a real `User` row in this bundle resolves to the real `User` UUID — cache populated User-first per `EntityType` ordering.
- `migration_run.warnings` JSON shape co-owned with S-141 (the txn carrier).
