---
id: S-184
title: Migration-bundle — identity sub-package mappers
epic: E-02
status: todo
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
  - Concrete mappers under `ch.alpenflight.migration.bundle.identity.*` for every IDENTITY-group `EntityType` member except `AUDIT_LOG` (S-186): `Language`, `ClubState`, `MemberState`, `PersonCategory`, `PersonCategoryAssignment` (new — see below), `Club`, `Person`, `PersonClub`, `User`.
  - Each mapper implements bidirectional `writeNdjson` + `readEntity` + `foreignKeys()` per the S-183 contract; each has a `AbstractMapperContractTest<MapperType>` subclass that passes (defensive-copy columns, round-trip, FK ordinals precede self).
  - **Retroactive `EntityType` amendments (in this PR):** drop `ROLE` + `USER_ROLE` (Keycloak owns the catalog per ADR 0007; no destination table); add `Roles` + `UserRoles` to `UnmappedTables.REGISTRY`. Add `PERSON_CATEGORY_ASSIGNMENT` to `EntityType` for the new V17 junction (see below).
  - **V17 Flyway migration** in this PR: `t_person_category_assignment (id UUID PK, person_id UUID FK t_person, person_category_id UUID FK t_person_category, club_id UUID FK t_club, ux_unique_assignment WHERE deleted_on IS NULL)` — ports legacy `PersonPersonCategories` M:N junction into a tenant-scoped direct association.
  - **SYSTEM_GLOBAL_RESOLVE entities** (resolve via `code` lookup against V2 seeds, **not** `legacy_int_id` — V2 schema's `t_language` / `t_club_state` carry no such column): `Language` (legacy `LanguageKey` lower-cased → `t_language.code`), `ClubState` (legacy `ClubStateName` upper-cased → `t_club_state.code`). Mapper emits `(legacy_guid, lookup_key)` pairs; S-141 ingest joins against the pre-seeded canonical rows.
  - **Cross-tenant FK declarations** via `EntityPolicy.tenantBypassFks`: `User.person_id` → PERSON; `PersonClub.person_id` → PERSON; `PersonCategoryAssignment.person_id` → PERSON. Person itself only FKs to Country (SYSTEM_GLOBAL, no bypass needed). The original story body's claim of `Person.PrimaryClubId` was incorrect — legacy `Persons` carries no such column.
  - **System-row policy:** legacy `Users.Id = 13731ee2-c1d8-455c-8ad1-c39399893fff` (system actor) is dropped at ingest; every `created_by_user_id` / `modified_by_user_id` / `deleted_by_user_id` reference to it is re-routed to the S-186 audit-actor orphan synthesis (UUID v7 + `migration_run.warnings` entry). No synthetic User row created.
  - **User column deny-list (passwords + KC shadow):** `UserMapper.columns()` MUST omit `Password`, `LastPasswordChangeOn`, `ForcePasswordChangeNextLogon`, `FailedLoginCounts`, `AccountState` (and any other ASP.NET-Identity shadow column present in the legacy DBO). Producer-side query MUST NOT `SELECT` these columns (defense-in-depth against accidental NDJSON leak). Unit test asserts deny-list absent. `keycloak_sub` stays NULL on ingest — minted at S-028.
  - **Legacy Language drift:** any legacy `Users.LanguageKey` not in the V2 seed set fails with explicit `BUNDLE_LANGUAGE_NOT_SEEDED` rather than silent default to `de`. Fail-closed per ADR 0022 D1.
  - **`PersonCategory.ParentPersonCategoryId` self-FK** resolves at ingest via two-pass: insert with NULL parent, then UPDATE per the V2 constraint (no DEFERRABLE clause). S-141 hand-off.
  - **`PersonClub` PK reshape:** legacy composite (PersonId, ClubId) → surrogate UUID v7. **No `legacy_id_map_person_club`** — PersonClub is a leaf junction; no inbound FKs reference it from other identity-group entities.
  - **Cross-story hand-offs:** PII surface declarations for S-186 (audit `LEGACY_MIGRATED` payload deny-list — see Security plan) and S-024 leakage exemption (Person stays on the cross-tenant allow-list update owned by S-186).
estimate: M
adr_refs: [0002, 0003, 0007, 0008, 0019, 0022]
---

## Context

Scope-split from [S-183](implemented/S-183-migration-bundle-mappers-and-parity-oracle.md). S-183 shipped the contract scaffolding + Country sample. This story fills in the remaining 9 identity-group mappers (8 from the original AC plus the new `PersonCategoryAssignment` from the PersonPersonCategories port decision below). Audit-log mapper (`AUDIT_LOG`) stays in S-186 since it carries the S-027 / S-024 hand-offs.

This PR also makes 2 retroactive amendments to S-183: dropping `ROLE` / `USER_ROLE` from `EntityType` (Keycloak owns the catalog), and adding `PERSON_CATEGORY_ASSIGNMENT` for the new V17 junction.

## Cross-story contracts

- **Consumes:** S-183 `Mapper` interface (`writeNdjson` / `readEntity` / `columns()` / `foreignKeys()`), `EntityPolicy.PortPolicy` (`FULL_PORT` / `SYSTEM_GLOBAL_RESOLVE` / `OPTIONAL`), `LegacyIdMapWriter`, `AbstractMapperContractTest`, `UnmappedTables.REGISTRY`, `Coercions`, `LegacyIdMapTables`. CountryMapper as SYSTEM_GLOBAL template.
- **Produces:** 9 concrete mappers + V17 migration consumed by S-141 (ingest pipeline) and S-187 (parity oracle).
- **Hand-offs:** PII column deny-list for S-186 audit `LEGACY_MIGRATED` payload contract. S-024 cross-tenant leakage exemption add for `t_person` is owned by S-186.

<!-- modernize-refine: start -->

## Design notes

- **Two-tier ref resolution:** Language + ClubState are **SYSTEM_GLOBAL_RESOLVE via `code` lookup** (V2 doesn't carry `legacy_int_id` on `t_language` / `t_club_state` — the prior migration-plan claim is corrected here). Bundle emits `(legacy_guid, lookup_key)`; S-141 joins by `code` against V2 seeds. MemberState + PersonCategory + PersonCategoryAssignment are **FULL_PORT, TENANT_SCOPED** (per-bundle map).
- **Aggregate roots FULL_PORT cross-tenant set:** Person is cross-tenant (no `@TenantId`); Person's only outgoing FK is COUNTRY (SYSTEM_GLOBAL, no bypass needed). Club is the tenant root. User is tenant-scoped on `club_id`. The cross-tenant escape hatches via `tenantBypassFks` live on **User.person_id → PERSON**, **PersonClub.person_id → PERSON**, **PersonCategoryAssignment.person_id → PERSON**. Person itself declares no bypass.
- **System-row drop:** legacy Users row `13731ee2-c1d8-455c-8ad1-c39399893fff` is filtered out by `UserMapper` at producer time (legacy `WHERE Id != …`). Audit refs to it land in S-186's orphan-actor synthesis path. Avoids the Keycloak-less User violation of ADR 0007.
- **`Roles` / `UserRoles` retroactive UnmappedTables entries:** added to `UnmappedTables.REGISTRY` with reason "Realm-role catalog owned by Keycloak per ADR 0007; importer maps legacy role names to KC realm roles at S-028 provisioning without persisting." `EntityType.ROLE` + `EntityType.USER_ROLE` removed; `ArchUnit knownMappersListCoversEveryConcreteMapperOnTheClasspath` still green.
- **`PersonCategoryAssignment` new EntityType + V17:** ports legacy `PersonPersonCategories` (`PersonId`, `PersonCategoryId`) into a tenant-scoped junction carrying `club_id` (the operating club's category assignment to a person). New `EntityType` entry slots after `PERSON_CATEGORY`. New ArchUnit ingest-order rule still passes.
- **`PersonClub` PK + key strategy:** surrogate UUID v7 at ingest; mapper `foreignKeys()` declares PERSON + CLUB + MEMBER_STATE. No `legacy_id_map_person_club` — PersonClub is a leaf junction (no inbound FKs from other identity entities). Legacy `Person.Has*Licence` booleans are projected to per-Club scope (`is_glider_pilot`, etc.) — semantic shift from person-wide to per-Club. Flag in PR description for reviewer awareness.
- **`@ParityIgnore` placements:** `User.notification_email` (resampled by S-187 oracle as opaque); `Club.last_*_synchronisation_on` (operational cache); audit columns (`created_on`, `modified_on`, `RowVersion`) across the group. Reference tables (Language, ClubState, MemberState, PersonCategory) carry no `@ParityIgnore` — small enough for full parity coverage.
- **Producer-side queries:** each mapper's legacy-side `SELECT` MUST exclude the deny-listed columns (passwords / KC shadow / OwnershipType / OwnerId metadata / SortIndicator / RecordState) — not just the mapper's `writeNdjson`. Defense-in-depth: the NDJSON never carries what the destination doesn't model.

## Edge cases & hidden requirements

- **Person has no `PrimaryClubId` column in legacy** (verified `flsserver/src/FLS.Server.Data/DbEntities/Person.cs`). Original AC was wrong; corrected. Person's cross-tenant character flows from being referenced by tenant-scoped entities (User, PersonClub, PersonCategoryAssignment, Aircraft.ManagingClubId per ADR 0008), not from a column on Person itself.
- **`Users.PersonId` is nullable in legacy** — `t_user.person_id` is also nullable. Port NULL → NULL; do NOT synthesize a stub Person. Pre-S-052 service accounts preserve.
- **`Users.LanguageId` INT → `t_user.language_id` UUID NOT NULL:** must resolve through `legacy_id_map_language`. Drift (FR/IT/EN added by DBUpdates past V2 seed) fails closed with `BUNDLE_LANGUAGE_NOT_SEEDED` rather than silent default.
- **Legacy `ClubStates` has 4 rows, new has 3** — `1=Active → ACTIVE`, `2=Passiv → CLOSED`, `3=Inactive → SUSPENDED`. `0=System` doesn't map; any User pointing at ClubStateId=0 follows the system-row drop policy.
- **`MemberState` per-Club tenancy:** TENANT_SCOPED per-bundle map; S-138 per-Club seed runs as no-op for ported states (bundle-wins via `ux_member_state_club_name`).
- **`PersonCategory.ParentPersonCategoryId` self-FK:** V2 has no DEFERRABLE constraint. Two-pass ingest: insert with NULL parent, then UPDATE after PERSON_CATEGORY pass completes. S-141 ingest concern.
- **`PersonClub.IsActive` vs `IsDeleted`:** legacy carries both. New schema preserves both (`is_active` + `deleted_on`) — soft-disabled-but-not-deleted (`IsActive=0, IsDeleted=0`) is a distinct state from tombstoned. Don't collapse.
- **Legacy ASP.NET artifacts dropped universally** (no destination): `OwnerId`, `OwnershipType`, `RecordState`, `MemberKey`, `SortIndicator`. Documented in shared package-info.java.
- **`PersonCategoryAssignment` chicken-and-egg with the V17 migration:** the migration ships in this PR; mapper depends on it. CI gate: migration must apply before tests run (Spring Boot test context handles this).

## Security plan

- **`tenantBypassFks` explicit allow-list:** `User.person_id` → PERSON, `PersonClub.person_id` → PERSON, `PersonCategoryAssignment.person_id` → PERSON. Language / ClubState / MemberState / PersonCategory / Club declare empty `tenantBypassFks` — Manifest validator rejects any non-empty set on these.
- **User password + KC-shadow column deny-list (never copied):** `Password`, `LastPasswordChangeOn`, `ForcePasswordChangeNextLogon`, `FailedLoginCounts`, `AccountState`. Producer-side `SELECT` excludes these — bundle NDJSON never contains them. Unit test on `UserMapper.columns()` asserts the deny-list absent.
- **Person PII routed plain into bundle** (destination needs them): firstname, lastname, emails, phones, addresses, birthdate. **At the audit-log surface (S-186 `LEGACY_MIGRATED` event), the payload carries only `legacy_guid` + `new_uuid` + row count — NOT the PII columns.** Hand-off to S-186: pin the payload-deny-list contract on `S-186-LegacyMigratedAuditEvent`.
- **`PersonClub.member_number` is per-Club PII** — mark `@AuditRedact` on the field. S-027 `AuditRedactionCoverageTest` must assert `t_person_club.member_number` is in the redacted-field set. Hand-off to S-186.
- **`Club` contact columns are per-tenant PII** (not cross-tenant); standard S-027 default-deny applies on Club CRUD audit events (S-026 hand-off; not ours).
- **Cross-bundle Person dedupe stays out of scope** (inherited from S-016 / S-183) — bundle-local resolution via `legacy_id_map_person ON COMMIT DROP`.
- **S-028 `keycloak_sub` mint coordination:** UserMapper writes `keycloak_sub = NULL`. S-028's `BulkUserProvisioningService` is the only writer that flips NULL → UUID. Document on `UserMapper.readEntity` as a one-line ADR 0007 pointer. Adding `keycloak_sub` to the bundle column list creates a double-write race — guard structurally.
- **S-024 leakage-exemption hand-off:** S-186 owns the YAML edit that adds `t_person` to the cross-tenant allow-list (per S-183 hand-off contract). Identity story files no YAML change; flag in PR description.

## Test plan

- **Per-mapper contract test (×9):** `class <Entity>MapperContractTest extends AbstractMapperContractTest<<Entity>Mapper>` — overrides `mapper()` + `legacyRow(Faker)`. No other methods unless an edge case below.
- **Faker fixture invariants:** SYSTEM_GLOBAL mappers (Language, ClubState) — `legacyRow()` standalone, no GUID FKs. MemberState / PersonCategory — `legacyRow()` carries `ClubId` seeded earlier. Club — `OwnerPersonId` always NULL at ingest (resolves in S-141 second pass). Person — only Country FK; no cross-tenant escape. PersonClub — composite FK seeded earlier. User — `PersonId` may be NULL.
- **Cross-mapper coverage:** inherited S-183 ArchUnit rule `knownMappersListCoversEveryConcreteMapperOnTheClasspath` fails if any new `<Entity>Mapper` lands without a contract-test subclass.
- **Dedicated `@Test` cases beyond the contract suite:**
  - `UserMapperPasswordDenyListTest.columnsExcludeEveryPasswordAndKcShadowColumn` — asserts the deny-list is absent from `columns()`.
  - `UserMapperContractTest.readEntity_withNullPersonId_bindsSqlNull` — orphan service-account case.
  - `PersonCategoryMapperContractTest.readEntity_withNullParent_bindsSqlNull` — root-category case.
  - `ClubMapperContractTest.readEntity_ownerPersonId_isNullAtFirstPass` — documents the deferred resolution.
- **Parity-strategy delta vs S-187:** `@ParityIgnore` columns per mapper enumerated in Design notes; S-187's oracle reads the annotations.
- **What's NOT in scope here:** `MapperVsSchemaCompatibilityTest` (S-187, in `alpenflight/server/`); no Testcontainers; no JMH.

## Performance plan

- **Allocation discipline** (inherited from S-183 contract).
- **Batched FK lookups:** every cross-entity FK resolves through `LegacyIdMapTables.resolveForeignKeyArrayQuery` per 500-row batch. Identity-group cardinalities are small (< 350 reference rows, ≈200K Person, ≈500K PersonClub at customer scale) — no FlightCrew-class hot path; no JMH gate.
- **N+1 risks:** PersonClub at ≈500K rows is the only volume FK hop — three FK lookups (Person + Club + MemberState) must batch as three array queries, not 3×500 single-row lookups. Already enforced by S-183 ArchUnit ban on per-row `findByLegacyGuid`.
- **`legacy_id_map_person` COPY binary stream** at ≈200K rows ≈ 8.4 MB on the wire (PGCOPY framing per S-183 byte layout).
- **`t_person.legacy_guid` partial index** required for the FK-sweep — verify V2 carries `WHERE legacy_guid IS NOT NULL` form; if missing, file V17+ delta (alpenflight/server concern, not migration-bundle).
- **Latency budget:** N/A — bulk batch; identity group is < 1 min of the 6 h cutover SLO at customer scale.

<!-- modernize-refine: end -->
