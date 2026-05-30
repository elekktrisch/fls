---
id: S-186
title: Migration-bundle — accounting sub-package + audit-log mappers
epic: E-02
status: done
started_at: 2026-05-30
done_at: 2026-05-30
depends_on: [S-183]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: true
refined_at: 2026-05-30
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer]
github_issue: 173
github_pr: 174
acceptance:
  - All `EntityType` members in `Group.ACCOUNTING` ship a concrete `Mapper` under `ch.alpenflight.migration.bundle.accounting.*` — `AircraftReservationType`, `AircraftReservation`, `PlanningDay`, `PlanningDayAssignmentType`, `PlanningDayAssignment`, `Article`, `AccountingRuleFilter`, `Delivery`, `DeliveryItem`. Two per-club ref types added to `EntityType` mid-implement so Manifest coverage gate stays satisfied.
  - `AUDIT_LOG` ships under `ch.alpenflight.migration.bundle.identity.AuditLogMapper`. Producer-resolved actor lands in two mutually-exclusive columns: real `Users.UserName` match → `actor_user_id` (FK to `t_user`); miss → `legacy_orphan_actor_id` (V18, no FK; one UUID v7 per distinct legacy UserName, bundle-local cache) + `migration_run.warnings.AUDIT_ORPHAN_ACTOR`. NULL/whitespace UserName → both NULL.
  - Each mapper passes the `AbstractMapperContractTest` suite.
  - **S-027 + S-024 cross-story hand-offs land here** — `MutationAuditEventLegacyMigratedReadBackIT` covers the V18 column round-trip via JdbcTemplate (bypasses `@TenantId` since migrated rows carry NULL `tenant_club_id` — repository-layer assertions wait on S-023 `UnscopedTenantContext`); `tenant-rules.yaml` AuditLogs entry documents the NULL-tenant semantics and adds Users to `ride_through_targets`.
estimate: M
adr_refs: [0002, 0003, 0008, 0019, 0022]
---

## Context

Scope-split from [S-183](S-183-migration-bundle-mappers-and-parity-oracle.md). Accounting-group + audit-log mappers — the latter carries the orphan-actor synthesis logic that is the load-bearing edge case.

## Cross-story contracts

- **Consumes:** S-183's scaffolding; S-184's `User` mapper for audit-actor lookups; S-185's `Flight` / `FlightCrew` for `Delivery` linkage.
- **Produces:** Accounting-group + audit-log `Mapper`s consumed by S-141 + S-187.
- **Hand-offs (land here, per S-016 + S-183 refinement):** S-027 read-back test + S-024 leakage exemption list update.

## Notes

- `migration_run.warnings` JSON shape is co-owned with S-141 (the txn carrier owns the writer; mapper-emitted warning codes are documented in the relevant mapper javadoc).

<!-- modernize-refine: start -->

## Load-bearing decisions

Most of the design lives in code: mapper javadoc carries the per-mapper contract; V18 / V19 SQL headers carry the schema rationale and ADR 0022 D2 conformance; `Manifest.TENANT_BYPASS_ALLOW_LIST` javadoc enumerates the four S-186 widenings; the `AuditActorKind` enum + `MutationAuditEvent` column comments carry the orphan-actor wire shape. Keep here only what the code can't carry:

- **Wire-shape of an orphan-actor row** (V18 split). `actor_user_id` only ever holds a real `t_user.id` (FK preserved). The synthesized orphan UUID v7 lands in `legacy_orphan_actor_id` (no FK) — the bare AC wording "synthetic `legacy_orphan_actor_id` UUID v7" was missed during refinement and surfaced as a CI FK-violation; the corrected wire shape is `(actor_user_id NULL, legacy_orphan_actor_id NOT NULL, legacy_actor_user_id NOT NULL)` for orphans vs `(actor_user_id NOT NULL, legacy_orphan_actor_id NULL, legacy_actor_user_id NOT NULL)` for real-User matches vs all-three-NULL for NULL/whitespace UserName.
- **`EventType → action` operator decision (2026-05-30).** `Added→CREATE`, `Modified→UPDATE`, `Deleted→DELETE`, `SoftDeleted→UPDATE`, `UnDeleted→UPDATE`. SoftDeleted/UnDeleted collapse to UPDATE rather than DELETE/STATE_TRANSITION (lossy on forensic-intent distinction; round-trip-via-legacy_int_id preserves the legacy-side query path).
- **`tenant_club_id` all-NULL on migrated rows.** Cross-tenant system-event semantics — readable only via S-023 `UnscopedTenantContext`. Filed [S-189](S-189-migration-audit-history-tenant-backfill.md) as the deferred per-tenant back-fill follow-up.
- **EntityType scope-creep.** AC enumerated 7 accounting mappers + 1 audit; implement added `AIRCRAFT_RESERVATION_TYPE` + `PLANNING_DAY_ASSIGNMENT_TYPE` (per-club ref tables) before their parent aggregates so Manifest's coverage gate stays satisfied — same pattern as `MEMBER_STATE` / `PERSON_CATEGORY` in S-184. No additional cross-tenant FKs.

## Cross-story hand-offs

- **S-141:** consumes the 10 new mappers + V18 + V19 + Manifest widening. Owns the producer-side JOINs that resolve the columns the mappers trust (`ResolvedActorUserId` / `ResolvedLegacyOrphanActorId` for audit actor; `ResolvedDeliveryNumber` / `ResolvedLegacyDeliveryNumberText` for delivery numbering; `ResolvedProcessStateId` for delivery state; `ResolvedUnitPrice` + `ResolvedArticleId` for delivery items). Writes `migration_run.warnings` jsonb for the warning codes referenced in mapper javadocs.
- **S-187:** parity-oracle additions land in S-187 itself; `@ParitySentinel` / `@ParityIgnore` markers on the new mappers feed its sampled-value comparison.
- **S-027:** new `MutationAuditEventLegacyMigratedReadBackIT` under `audit/infra/` covers V18 column-acceptance via `JdbcTemplate` (bypasses `@TenantId` — migrated rows are NULL-tenant). Repository round-trip waits on S-023.
- **S-024:** `tenant-rules.yaml` AuditLogs entry adds Users to `ride_through_targets` + documents NULL-tenant semantics; Deliveries `pii_columns` adds `legacy_delivery_number_text`.
- **S-189 (filed):** post-cutover `tenant_club_id` back-fill via per-aggregate `legacy_id_map_<entity>` lookup.

## Parity exclusions

- **`before_state` / `after_state` on LEGACY_MIGRATED rows.** Legacy `AuditLogDetails` (property-change snapshots) is dropped per manifest WHY-not-mapped; migrated rows carry NULL both columns. S-187 treats them `@ParityIgnore` for LEGACY_MIGRATED.
- **`actor_keycloak_sub` and `tenant_club_id` on LEGACY_MIGRATED rows.** Structurally NULL on every migrated row (no Keycloak counterpart per ADR 0007; no legacy ClubId). Marked `@ParityIgnore` on the mapper; the parity oracle pins the NULL invariant via `actor_kind` as the sentinel instead.

<!-- modernize-refine: end -->
