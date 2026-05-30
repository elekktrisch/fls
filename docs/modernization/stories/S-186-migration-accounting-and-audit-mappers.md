---
id: S-186
title: Migration-bundle — accounting sub-package + audit-log mappers
epic: E-02
status: todo
depends_on: [S-183]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: true
refined_at: 2026-05-30
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer]
github_issue: 173
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

<!-- modernize-refine: start -->

## Design notes

- **V18 + V19 Flyway.** V18 (audit): adds `actor_kind VARCHAR(32) NOT NULL DEFAULT 'NORMAL'` (Java enum `AuditActorKind {NORMAL, SYSTEM, LEGACY_MIGRATED}` — no CHECK IN-set per ADR 0022 D2), `legacy_actor_user_id TEXT NULL`, `legacy_int_id BIGINT NULL` (legacy `AuditLogs.AuditLogId IDENTITY` forensic), `legacy_target_record_id TEXT NULL` (raw legacy `RecordId` when not UUID-parseable). V19 (delivery): adds `legacy_delivery_number_text TEXT NULL` (raw legacy `Deliveries.DeliveryNumber` when not integer-parseable; V4 header anticipated this). Both ALTER-only migrations; no schema deviation.
- **AuditLogMapper actor resolution (producer-side, S-139).** Legacy `AuditLogs.UserName` looked up against bundle's `Users.UserName` set (USER precedes AUDIT_LOG in `EntityType` ordering — S-184 guarantee); hit → real `User.id`. Miss → one synthetic UUID v7 per **distinct** legacy UserName, bundle-local `ON COMMIT DROP` cache + `migration_run.warnings.AUDIT_ORPHAN_ACTOR`. NULL or whitespace-only UserName → NULL `actor_user_id` (no synthesis; bucketing whitespace under one fake principal is forensically misleading). Legacy `LEGACY_SYSTEM_USER_ID = 13731ee2-…` (S-184 producer-drop) routes through the same orphan synthesis path (no Keycloak counterpart per ADR 0007). Username-uniqueness check: legacy `Users.UserName` is club-unique only — duplicate UserNames across clubs in the bundle's User set hard-fail at producer parse (`AUDIT_USERNAME_AMBIGUOUS`).
- **`EventType` → `action` mapping.** `Added → CREATE`, `Modified → UPDATE`, `Deleted → DELETE`, `SoftDeleted → UPDATE`, `UnDeleted → UPDATE` (operator decision: cleaner enum semantics; SoftDeleted/UnDeleted are technically column-level mutations on `deleted_on`; the forensic intent distinction is lost but `target_entity_type` + the `legacy_int_id` round-trip preserve a full legacy-side query path).
- **`TypeFullName` → `target_entity_type`.** Strip `FLS.Server.Data.DbEntities.` prefix (`MappingExtensions.cs:628`). Truncation guard at producer: any legacy short-name > 64 chars hard-fails (`AUDIT_TYPE_NAME_TOO_LONG`); current FLSTest entity names all fit (verify at producer-impl time). `TypeFullName` referencing entities dropped in the rewrite (FlightAirState, Settings, PersonFlightTimeCredit, etc.) ports as-is — forensic value > cleanup.
- **`RecordId` → `target_entity_id` (UUID NULL) + `legacy_target_record_id` (TEXT).** Producer-side `UUID.fromString` parse; success → `target_entity_id` populated, `legacy_target_record_id` NULL. Failure → `target_entity_id` NULL + raw `RecordId` text into `legacy_target_record_id` + `AUDIT_TARGET_NOT_UUID` warning. Cross-entity remap via `legacy_id_map_<entity>` is OUT of scope — `target_entity_id` has no FK in V9, so verbatim legacy UUID preserves forensic linkage.
- **`tenant_club_id` all-NULL for migrated rows.** Legacy `AuditLogs` has no `ClubId`. Cross-tenant system-event semantics — visible only via S-023 `UnscopedTenantContext` (SYSADMIN). **Filed S-189 (audit-history tenant back-fill)** as follow-up: post-cutover back-fill via `legacy_id_map_<entity>` lookup keyed by `target_entity_type` + `target_entity_id`, runs only if/when a tenant requests per-club historical audit visibility. S-024 leakage exemption (below) covers the NULL-tenant rows in the meantime.
- **`before_state` / `after_state` NULL on migrated rows.** Legacy `AuditLogDetails` is dropped (manifest WHY-not-mapped). Accepted parity exclusion; S-187 oracle treats these as `@ParityIgnore` for LEGACY_MIGRATED rows.
- **DeliveryMapper — `delivery_number`.** Producer-side `Integer.parseInt(DeliveryNumber)`; parse failure → `delivery_number` NULL + raw text into `legacy_delivery_number_text` (V19) + `DELIVERY_NUMBER_NON_INTEGER` warning. Avoids per-club UNIQUE collisions on `(operating_club_id, delivery_number)` and preserves the Art. 957a forensic recall.
- **DeliveryMapper — `process_state_id`.** Resolved producer-side by JOIN to legacy `Flights.ProcessStateId` + `Deliveries.IsFurtherProcessed` per V4 header cutover (50→10, 45→30, 60→20, `IsFurtherProcessed=true`→20 wins on conflict). Mapper ResultSet receives the SMALLINT verbatim. 9 frozen `recipient_*` columns straight passthrough (OR Art. 957a — tombstones ported). Pre-snapshot rows where `RecipientPersonId NOT NULL` AND `recipient_*` NULL → NULL passthrough + `DELIVERY_RECIPIENT_NOT_FROZEN` warning (S-064 read-side may lookup-from-Person for Prepared state only).
- **DeliveryItemMapper.** Producer-side `unit_price` back-fill from Article master keyed by `article_id` per V4 header (S-139 SQL responsibility — mapper's bind contract trusts the resolved value). Narrowings: `quantity decimal(18,3) → NUMERIC(12,4)` overflow → producer rejects + `DELIVERY_ITEM_QUANTITY_OVERFLOW` warning; `unit_type_code VARCHAR(50)` from `UnitType NVARCHAR(250)` overrun → producer rejects + `DELIVERY_ITEM_UNIT_TYPE_TOO_LONG` warning (no silent truncation).
- **AircraftReservationMapper.** Tenant-bypass: `aircraft_id` (→ cross-tenant Aircraft per S-185), `pilot_person_id`, `second_crew_person_id`. `reservation_range tstzrange` is GENERATED — `columns()` excludes it. `location_id` resolves through composite `legacy_id_map_location` replica selection (S-185 pattern; replica matches `operating_club_id`). `reservation_type_id` + `flight_type_id` resolve via per-bundle tenant-scoped ref maps. Legacy NULL `PilotPersonId` (degenerate reservation) → producer drops row + `RESERVATION_NO_PILOT` warning (new `pilot_person_id NOT NULL` + FK RESTRICT). Empty-range degenerate (`Start == End`) passes through — S-064 aggregate-side rejects on read.
- **PlanningDayMapper / PlanningDayAssignmentMapper.** `PlanningDay.location_id` → composite-replica Location (no tenant bypass — Location is tenant-scoped per V7). `PlanningDayAssignment.assigned_person_id` → cross-tenant Person (tenant-bypass). `PlanningDayAssignmentType` per-bundle ref map. `(operating_club_id, planning_date, location_id)` UNIQUE collisions: producer-side dedupe-keep-first + `PLANNING_DAY_DUPLICATE` warning (legacy had no constraint; FLSTest unlikely to hit it, real-club case mild — keep-first deterministic on `(CreatedOn, PlanningDayId)`).
- **AccountingRuleFilterMapper — jsonb fold.** 30+ legacy predicate columns (`Matched*`, `IsRuleFor*`, `NoLandingTaxFor*`, `Min/MaxFlight/EngineTimeIn…`, `UseRuleForAll*Except*`, `Threshold*`, `IncludeFlightTypeName`) fold producer-side into `filter_config jsonb` keyed by `filter_type_id` legacy value (10/20/30/40/50/60/70/80 per V4 seed) via per-discriminator allow-list. Boolean `UseAllExcept*` flags pair with their `Matched*` array as `{"useAllExcept": bool, "matched": [...]}` shape — flattening loses inversion semantics. Mapper emits jsonb as opaque text — no Jackson default-typing (V4 A03 mitigation). `filter_type_id` + `accounting_unit_type_id` → UUID via `Coercions.legacyIntIdToUuidString`. `(operating_club_id, sort_indicator)` UNIQUE collisions: producer-side re-number on detection + `ACCOUNTING_RULE_SORT_RENUMBERED` warning carrying old + new indicator pairs.
- **ArticleMapper.** Plain TENANT_SCOPED, no cross-tenant FKs. `(operating_club_id, article_number)` UNIQUE collisions: **producer hard-fails the bundle** (`ARTICLE_DUPLICATE_NUMBER` warning + reject). DeliveryItem snapshots reference `article_number` per OR Art. 957a — silent dedupe rewrites legal records. Operator-readable diagnostic on the unhappy path.
- **`Manifest.TENANT_BYPASS_ALLOW_LIST` widening** (constructor-enforced per S-184): add AUDIT_LOG (`actor_user_id`), AIRCRAFT_RESERVATION (`aircraft_id` + `pilot_person_id` + `second_crew_person_id`), PLANNING_DAY_ASSIGNMENT (`assigned_person_id`), DELIVERY (`recipient_person_id` SET-NULL ride-through). PlanningDay does NOT widen (Location replica is tenant-scoped, structurally handled).
- **Cross-story hand-offs.** **S-141:** consumes the 8 new mappers + V18 + V19 + Manifest widening; owns the `migration_run.warnings` jsonb writer for new warning codes; runs producer-side JOINs for Delivery state + DeliveryItem unit_price; populates the per-bundle `UserName → new UUID` resolver during USER ingest. **S-187:** parity-oracle additions land in S-187 itself (sentinel columns per Test plan; LegacyFixtureSeeder variants). **S-027:** new test method in `alpenflight/server/`. **S-024:** YAML exemption add (per Test plan). **S-189 (filed):** post-cutover tenant_club_id back-fill.
- **Schema deviation per ADR 0022 D2.** None. V18 + V19 add columns only; `actor_kind` is Java-pinned enum (no CHECK IN-set, no generated columns, no triggers).

## Edge cases & hidden requirements

- **Audit `UserName` ambiguity.** Legacy `Users.UserName` is club-unique only. Bundle-wide duplicate UserNames across clubs hard-fail at producer parse — silent collision would cross-attribute audit history.
- **Audit `TypeFullName` for dropped entities** (FlightAirState, Settings, PersonFlightTimeCredit, dropped Roles) ports as-is — forensic preservation over cleanup. S-027 read path treats unknown `target_entity_type` as opaque text.
- **AuditLog `IsDeleted` column** doesn't exist on legacy (re-confirmed against bootstrap). Audit rows are immutable by design — no soft-delete to port.
- **AuditLogMapper package landing.** Lives in `identity.*` per AC and per `EntityType.AUDIT_LOG` group (IDENTITY). ArchUnit "group → package" rule covers it.
- **Delivery pre-snapshot rows** (`RecipientPersonId NOT NULL` AND `recipient_*` NULL) pass through NULL — S-064 read-side lookup-from-Person for Prepared state; Booked rows preserve frozen NULLs (Art. 957a immutability over data hygiene).
- **DeliveryNumber non-integer text + V19 column.** Both `delivery_number INTEGER NULL` and `legacy_delivery_number_text TEXT NULL` exist on every Delivery row. Invariant: at most one is populated for migrated rows; new-write rows leave `legacy_delivery_number_text` NULL. Documented in V19 column comment.
- **AccountingRuleFilter boolean-pair preservation.** Each `UseRuleForAll*Except*` boolean pairs with a `Matched*` array column — folded jsonb keeps both as `{useAllExcept: bool, matched: [...]}` not flattened. Inversion semantics load-bearing.
- **Orphan-actor cache lifecycle.** `ON COMMIT DROP` same as `legacy_id_map_person`. Re-import after `failed` mints fresh UUIDs (S-141 handshake re-fires). Cross-bundle dedupe out of scope (same exclusion as S-183).
- **Forensic preservation triple on `t_mutation_audit_event`.** `legacy_int_id BIGINT NULL` (AuditLogs.AuditLogId) + `legacy_actor_user_id TEXT NULL` (UserName) + `legacy_target_record_id TEXT NULL` (RecordId, when not UUID) — together they enable a full legacy-side back-query without re-querying the legacy DB.
- **`AuditLogDetails` (legacy property-change records) is DROPPED** per manifest WHY-not-mapped. Migrated rows carry NULL `before_state` / `after_state`. Accepted parity exclusion.
- **S-189 follow-up filed** as `stories/S-189-migration-audit-history-tenant-backfill.md` (todo). One-line: post-cutover, back-fill `tenant_club_id` on LEGACY_MIGRATED audit rows via `legacy_id_map_<entity>` lookup.

## Security plan

- **Audit-PII redaction.** `@AuditRedact` on `legacy_actor_user_id`, `legacy_target_record_id`, `legacy_delivery_number_text` — identity-attributable + invoice-attributable. Default-deny serializer (S-027) covers them by default; explicit annotation makes intent load-bearing for grep + survives accidental allow-list edits. `AuditPayloadTurboFilter` blocks logback-side leakage. `target_entity_type` not redacted (structural).
- **Cross-tenant FK widening** (`Manifest.TENANT_BYPASS_ALLOW_LIST`): AUDIT_LOG, AIRCRAFT_RESERVATION, PLANNING_DAY_ASSIGNMENT, DELIVERY. S-184 constructor-enforced gate rejects any wider declaration at parse — no service-layer alternative.
- **Per-bundle orphan-actor cache lifecycle.** `ON COMMIT DROP` (mirrors `legacy_id_map_person`). Cross-bundle dedupe out of scope; re-import after `failed` mints fresh UUIDs.
- **No plaintext bundle bytes at rest.** S-183 ArchUnit ban extends — confirm no `Files.createTempFile` / `FileOutputStream` / `Files.write*` / `Files.newOutputStream` in `accounting/*` or `AuditLogMapper`. Only surface is Jackson `JsonNode` + JDBC.
- **AccountingRuleFilter `filter_config jsonb` — opaque passthrough.** Mapper treats producer-emitted jsonb as text bytes; NO `ObjectMapper.readValue(..., Object.class)` and NO `@JsonTypeInfo(use=Id.CLASS)`. V4 A03 mitigation (Jackson default-typing globally disabled + ArchUnit ban) covers this; no per-mapper code needed.
- **Cross-tenant audit search visibility.** Migrated rows land with NULL `tenant_club_id` — visible only via S-023 `UnscopedTenantContext` (SYSADMIN). Non-SYSADMIN tenant audit search will not see migrated history until S-189 back-fills. Runbook entry warns operators; S-141 implement-time test asserts SYSADMIN-only visibility.
- **S-024 leakage exemption YAML edit** (story-owned hand-off): `mutation_audit_event` (NULL `tenant_club_id` historical rows) + `delivery` (cross-tenant `recipient_person_id` SET-NULL ride-through). Other accounting tables stay tenant-scoped — no exemption.
- **Legacy GUID + BIGINT preservation per ADR 0019.** Inherited threat-boundary: legacy-DB-access ⇒ enumerate new UUIDs + `legacy_int_id`. Same posture as S-183 / S-184; not PII.

## Test plan

- **Pyramid.** One `AbstractMapperContractTest<M>` subclass per mapper (8 total: 7 accounting + `AuditLogMapper`) — no containers; reuses S-184 / S-185 stub pattern. Existing `Manifest` structural validator picks up `TENANT_BYPASS_ALLOW_LIST` widening (AUDIT_LOG, AIRCRAFT_RESERVATION, PLANNING_DAY_ASSIGNMENT, DELIVERY) — assertion in `ManifestTest`. `MapperVsSchemaCompatibilityTest` in `alpenflight/server/` (S-183-owned) auto-covers V4 + V18 + V19 columns once mappers exist.
- **AuditLogMapper.** Orphan-synthesis matrix: distinct legacy UserNames → distinct UUID v7; same UserName twice → same UUID (bundle-local cache); real `Users.UserName` match resolves to that User UUID (User-first ordering); NULL or whitespace-only UserName → NULL `actor_user_id` (no synthesis). Cross-club UserName ambiguity → producer hard-fails. All 5 `EventType` ints → action enum (Added→CREATE, Modified→UPDATE, Deleted→DELETE, SoftDeleted→UPDATE, UnDeleted→UPDATE). Non-UUID `RecordId` → NULL `target_entity_id` + raw text in `legacy_target_record_id` + `AUDIT_TARGET_NOT_UUID` warning. `TypeFullName` namespace-strip + >64-char hard-fail. `tenant_club_id` pinned NULL on migrated rows.
- **DeliveryMapper.** `delivery_number` parses: integer-text populates column + leaves `legacy_delivery_number_text` NULL; non-integer-text populates `legacy_delivery_number_text` + leaves `delivery_number` NULL + warning. 9 `recipient_*` columns present in `columns()` and pass through verbatim — NOT `@ParityIgnore` (frozen-snapshot invariant). Mapper trusts producer for `process_state_id`.
- **DeliveryItemMapper.** Trusts producer for `unit_price` back-fill + `unit_type_code` overrun reject + `quantity` overflow reject — assert columns declared, no mapper-side guards. Narrowed `quantity` in-range round-trips.
- **AircraftReservationMapper.** `foreignKeys()` returns AIRCRAFT + PERSON ×2 (pilot, second_crew); `reservation_range` absent from `columns()` (GENERATED); empty-range passes through; legacy NULL `PilotPersonId` is a producer-drop case (mapper test asserts presence of `pilot_person_id` in `columns()`, no NULL handling).
- **PlanningDayMapper / PlanningDayAssignmentMapper.** `foreignKeys()`: PlanningDayAssignment includes PLANNING_DAY + PERSON (PERSON via cross-tenant sub-map). PlanningDay duplicate-key dedup pinned at producer (no mapper logic).
- **AccountingRuleFilterMapper.** Emitted `filter_config` jsonb shape is a flat `{predicateName: value}` object — no `@class`/`@type` keys, no nested type tags (Jackson default-typing disabled witness). Boolean-pair preservation: at least one fixture row exercises `{useAllExcept: bool, matched: [...]}` shape end-to-end.
- **ArticleMapper.** Minimal contract; `(operating_club_id, article_number)` natural-key drift is producer-owned — no mapper-side handling asserted.
- **Cross-story coverage.** **S-027 read-back test method** lands in the existing test class under `alpenflight/server/` that exercises `MutationAuditEventRepository`: inserts a `LEGACY_MIGRATED` row, asserts `actor_kind = LEGACY_MIGRATED` + `actor_user_id NOT NULL` (synthetic case) + `actor_keycloak_sub IS NULL` + `legacy_actor_user_id NOT NULL` + `legacy_int_id` populated + `legacy_target_record_id` populated for the non-UUID case. Plus a `UnscopedTenantContext` query asserting NULL `tenant_club_id` rows are visible only outside tenant filter. **S-024 leakage exemption YAML**: one-line adds for `mutation_audit_event` + `delivery`; path confirmed at implement.
- **Parity strategy adds (S-187 consumes).** `@ParitySentinel` on `mutation_audit_event.{actor_kind, action, legacy_int_id}` + `delivery.{process_state_id, delivery_number}` + `delivery_item.{unit_price, quantity, unit_type_code}`. `@ParityIgnore` on legacy `AuditLogs.UserName` free-text (row-count exact; sampled-value skipped). `delivery.recipient_*` columns are sentinel-pinned (frozen-snapshot invariant). `LegacyFixtureSeeder` additions per Club: one `AuditLogs` row per `EventType` variant (5) + orphan-actor + NULL-actor + real-`Users.UserName`-match + one `Deliveries` row with non-integer `DeliveryNumber` + one `AccountingRuleFilters` row exercising the `useAllExcept`-shape jsonb.

## Performance plan

(N/A — accounting-group tables are dozens-to-thousands of rows per Club at customer scale; AuditLogs is variable but the JMH bench lives on `FlightCrewMapper` per S-185 / S-188 explicit single-bench decision. Mapper allocation discipline inherited from S-183's `Mapper` contract — no per-row allocation beyond Jackson + JDBC inherent; ArchUnit structural ban already covers the prohibited-API surface.)

<!-- modernize-refine: end -->
