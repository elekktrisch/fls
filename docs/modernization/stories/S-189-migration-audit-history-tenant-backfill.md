---
id: S-189
title: Migration — back-fill tenant_club_id on LEGACY_MIGRATED audit rows
epic: E-02
status: todo
depends_on: [S-186]
integration_base: integration/migration
origin: scope-split
origin_story: S-186
refined: false
acceptance:
  - Post-cutover script back-fills `t_mutation_audit_event.tenant_club_id` for rows where `actor_kind = 'LEGACY_MIGRATED'` AND `tenant_club_id IS NULL`.
  - Resolution walks `target_entity_type` + `target_entity_id` against the per-aggregate Hibernate-mapped tables, fetching the row's `operating_club_id` (or equivalent tenant discriminator). Cross-tenant entities (Person / Aircraft / Location) yield NULL (still cross-tenant — historical visibility stays SYSADMIN-only).
  - Unresolvable rows (target entity deleted, `target_entity_id IS NULL`, `target_entity_type` references a dropped legacy entity) stay NULL — documented as "legitimately cross-tenant history" rather than failure.
  - Script is idempotent: re-running on already-back-filled rows is a no-op.
  - After back-fill, S-024 leakage exemption for `mutation_audit_event` is reviewed (rows now per-tenant filterable may no longer need the exemption — confirm before removing).
estimate: M
adr_refs: [0008, 0019, 0022]
---

## Context

S-186 lands legacy audit migration with `tenant_club_id` all-NULL on `LEGACY_MIGRATED` rows. Per the S-186 refinement (operator decision 2026-05-30), the cheaper cross-tenant system-event semantics ships first; per-tenant audit search of pre-cutover history is deferred to this story.

This story runs **post-cutover** — when a tenant operator first asks "where's my pre-cutover audit history?". Until then, the rows stay SYSADMIN-only via S-023 `UnscopedTenantContext` (which is the structural guarantee S-186 ships).

## Cross-story contracts

- **Consumes:** S-186's `t_mutation_audit_event.{actor_kind, legacy_int_id, legacy_target_record_id}` columns + the V4-mapped destination tables that carry `operating_club_id` per ADR 0008.
- **Produces:** per-tenant historical audit visibility (S-027 read paths Just Work afterwards). Optional S-024 exemption review.

## Notes

- Lookup by `target_entity_type` + `target_entity_id` is N JOINs per audit row — acceptable as a one-shot script post-cutover, not as a per-request resolver.
- Script lives in `alpenflight/migration-bundle/src/main/java/.../AuditHistoryTenantBackfill.java` (or similar) — operator runs it via CLI; no API surface.
- Cross-tenant target entities (Person / Aircraft / Location per ADR 0008) intentionally yield NULL — these were always cross-tenant historical events.
