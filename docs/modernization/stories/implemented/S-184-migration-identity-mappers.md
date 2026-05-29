---
id: S-184
title: Migration-bundle — identity sub-package mappers
epic: E-02
status: done
started_at: 2026-05-29
done_at: 2026-05-29
merged: true
merged_at: 2026-05-29
depends_on: [S-183]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: true
refined_at: 2026-05-29
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
context7_last_checked: 2026-05-29
github_issue: 169
github_pr: 170
acceptance:
  - Concrete mappers under `ch.alpenflight.migration.bundle.identity.*` for every IDENTITY-group `EntityType` member except `AUDIT_LOG` (S-186): `Language`, `ClubState`, `MemberState`, `PersonCategory`, `PersonCategoryAssignment`, `Club`, `Person`, `PersonClub`, `User`.
  - Each mapper implements the S-183 `Mapper` contract; each has an `AbstractMapperContractTest<MapperType>` subclass that passes.
  - Retroactive `EntityType` amendments: drop `ROLE` + `USER_ROLE` (Keycloak owns the catalog per ADR 0007); add `PERSON_CATEGORY_ASSIGNMENT` for the new V17 junction. Register `Roles` + `UserRoles` in `UnmappedTables.REGISTRY`.
  - V17 Flyway migration ports legacy `PersonPersonCategories` M:N junction into the tenant-scoped `t_person_category_assignment` with FKs to `t_person`, `t_person_category`, `t_club`.
  - `Language` + `ClubState` are SYSTEM_GLOBAL_RESOLVE via natural-key lookup (V2 carries no `legacy_int_id` on these tables). `ClubState` carries the explicit 1=Active→ACTIVE / 2=Passiv→CLOSED / 3=Inactive→SUSPENDED translation; legacy id 0 (System) is producer-side filtered.
  - Cross-tenant FK declarations limited to `User.person_id`, `PersonClub.person_id`, `PersonCategoryAssignment.person_id` (all → PERSON). `Manifest` validator rejects a non-empty `tenantBypassFks` on any other entity.
  - System-row policy: legacy `Users.Id = 13731ee2-c1d8-455c-8ad1-c39399893fff` (`UserMapper.LEGACY_SYSTEM_USER_ID`) is dropped at producer time; audit references re-route to S-186's orphan-actor synthesis.
  - User column deny-list: bundle NDJSON never carries `Password`, `PasswordHash`, `LastPasswordChangeOn`, `ForcePasswordChangeNextLogon`, `FailedLoginCounts`, `AccessFailedCount`, `AccountState`, `EmailConfirmed`, `PhoneNumberConfirmed`, `TwoFactorEnabled`, `LockoutEnabled`, `LockoutEndDateUtc`, `SecurityStamp`. `keycloak_sub` stays NULL — minted at S-028 (single-writer guard against double-write race).
  - Legacy Language drift outside the V2 seed set fails closed at S-141 ingest with `BUNDLE_LANGUAGE_NOT_SEEDED` (ADR 0022 D1).
  - `PersonCategory.parent_person_category_id` self-FK is deferred to S-141 two-pass ingest (no DEFERRABLE in V2; mapper `foreignKeys()` does not declare self).
  - `PersonClub` and `PersonCategoryAssignment` are leaf junctions — no `legacy_id_map_*` temp table; S-141 mints UUID v7 at INSERT.
estimate: M
adr_refs: [0002, 0003, 0007, 0008, 0019, 0022]
---

## Context

Scope-split from [S-183](implemented/S-183-migration-bundle-mappers-and-parity-oracle.md). S-183 shipped the contract scaffolding + Country sample. This story fills the 9 identity-group mappers + V17 junction. Audit-log mapper (`AUDIT_LOG`) stays in S-186 (S-027 / S-024 hand-offs).

## Cross-story contracts

- **Consumes:** S-183 `Mapper`, `EntityPolicy`, `LegacyIdMapWriter`, `LegacyIdMapTables`, `UnmappedTables.REGISTRY`, `Coercions`, `AbstractMapperContractTest`.
- **Produces:** 9 mappers + V17 migration consumed by S-141 (ingest) and S-187 (parity oracle).
- **Hand-offs:**
  - **S-186** owns the audit `LEGACY_MIGRATED` payload contract (row-count + legacy GUID + new UUID; PII columns must not appear). Owns the `@AuditRedact` on `t_person_club.member_number`. Owns the S-024 cross-tenant leakage-exemption YAML edit for `t_person`.
  - **S-028** owns the only NULL→UUID write on `t_user.keycloak_sub`. `UserMapper.readEntity` binds NULL structurally.
  - **S-141** owns: PersonCategory self-FK two-pass; UUID v7 mint for PersonClub / PersonCategoryAssignment surrogate ids; fail-closed `BUNDLE_LANGUAGE_NOT_SEEDED` on Language drift; system-actor orphan-actor synthesis.

## Pickup notes

- `Manifest` constructor now structurally enforces the cross-tenant allow-list — any future identity-group entity that needs a tenant bypass must be added to `Manifest.TENANT_BYPASS_ALLOW_LIST`.
- ArchUnit bumped to 1.4.2 (1.3.0 silently failed to parse Java 25 bytecode; the structural `knownMappersListCovers…` rule was hollow under 1.3.0).
