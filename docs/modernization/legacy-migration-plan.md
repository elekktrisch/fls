# Legacy database migration plan

Single source of truth for **every legacy SQL Server table and EF entity** and what happens to it in the new stack. One row per legacy artifact (entity ↔ table pair, or framework-managed table with no entity). Exhaustive over the final-state legacy schema (initial EF migration + all `DBUpdate_v*.sql` deltas applied in order — 59 tables, 56 entity classes).

This file is maintained by `/modernize-refine` (Step 4.5). Each story that touches DB schema updates the rows it owns; the diff lands in the same PR as the story's refinement. The story body itself stays silent on per-table migration — readers come here.

## How to read

- **Legacy entity** — EF entity class name under [`flsserver/src/FLS.Server.Data/DbEntities/`](../../flsserver/src/FLS.Server.Data/DbEntities/). `—` when the table is framework-managed (e.g., `TrackerEnabledDbContext` audit tables) and has no application-owned entity class.
- **Legacy table** — name as it appears in the final-state legacy schema (post-DBUpdate). Cross-references: [`flsserver/src/FLS.Server.Data/Migrations/201501222055041_InitialCreate.cs`](../../flsserver/src/FLS.Server.Data/Migrations/201501222055041_InitialCreate.cs) for the baseline; [`flsserver/database/FLS/Updates/`](../../flsserver/database/FLS/Updates/) for the deltas.
- **Destination** — new-stack table name (in the V<N> Flyway migration that creates it) OR `(dropped)` OR `(folded into <table>)` OR `(replaced by <external>)`. `TBD` means no story has refined this row yet.
- **Semantics** — one of: `port-as-rows` · `port-as-schema-only` · `drop` · `fold-into-<table>` · `split-into-<tables>` · `replaced-by-<external-system>`. `TBD` until refined.
- **Owned by** — story ID that wrote / last updated this row. Past owners ride in **Notes** as `also touched by S-XXX`.
- **Notes** — cutover specifics, sacred-cow flags, anything an operator needs at migration time.
- **Detail** — relative link to `legacy-tables/<TableName>/`, a per-table folder containing `_table.json` (entity binding + table-level migration TBD fields) and one `<column>.json` per column (SQL Server metadata + per-column migration TBD fields). Refinement can stamp columns individually when the parent row's semantics isn't uniform.

## Allowed semantics

| Value | Meaning |
|---|---|
| `port-as-rows` | Rows copied 1:1 (column-mapped) by the S-016 importer / S-141 ingest pipeline. |
| `port-as-schema-only` | Table exists in new schema but rows are not copied (e.g. recreated empty per tenant on first use). |
| `drop` | No destination. Legacy rows are not read; the table doesn't exist in the new stack. |
| `fold-into-<table>` | Row contents merged into another aggregate's table (legacy junction → parent's column array, etc.). |
| `split-into-<tables>` | Single legacy table decomposed into N new tables (e.g. inheritance flattened). |
| `replaced-by-<external-system>` | Legacy responsibility moved to a non-DB system (Keycloak, OGN, Proffix). |

If a story needs a semantics value not in this list, add it via refine's Step 3.5 grill and update this header before stamping the row.

## Final-state legacy artifacts

> **Bootstrapped 2026-05-26.** Tables queried from the FLSTest fixture in the local `fls-e2e-mssql-1` container (`mcr.microsoft.com/mssql/server:2022-latest`) — the e2e/integration-test seed mirrors the post-DBUpdate prod schema. Entities enumerated from [`flsserver/src/FLS.Server.Data/DbEntities/`](../../flsserver/src/FLS.Server.Data/DbEntities/), with the `DbSet<E> N` map from [`flsserver/src/FLS.Server.Data/FLSDataEntities.cs`](../../flsserver/src/FLS.Server.Data/FLSDataEntities.cs) used to bind each table to its entity class. Per-table column detail under [`legacy-tables/`](./legacy-tables/) was emitted in the same pass. Row list is exhaustive — refine's Step 4.5 updates rows in place, never adds new ones. If a prod-DB extract via [`alpenflight/database/extract/`](../../alpenflight/database/extract/) later reveals a table absent here, treat it as a bootstrap defect and re-sync rather than appending ad-hoc.

| Legacy entity | Legacy table | Destination | Semantics | Owned by | Notes | Detail |
|---|---|---|---|---|---|---|
| AccountingRuleFilter | AccountingRuleFilters | `t_accounting_rule_filter` (V4) | port-as-rows | S-183 | Tenant-scoped; tombstones ported per S-016 ref policy. | [columns](legacy-tables/AccountingRuleFilters/) |
| AccountingRuleFilterType | AccountingRuleFilterTypes | `t_accounting_rule_filter_type` (V4) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-014). | [columns](legacy-tables/AccountingRuleFilterTypes/) |
| AccountingUnitType | AccountingUnitTypes | `t_accounting_unit_type` (V4) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-014). | [columns](legacy-tables/AccountingUnitTypes/) |
| AircraftAircraftState | AircraftAircraftStates | `t_aircraft_aircraft_state` (V3) | port-as-rows | S-183 | Aircraft↔state history; Aircraft is cross-tenant per ADR 0008. | [columns](legacy-tables/AircraftAircraftStates/) |
| AircraftOperatingCounter | AircraftOperatingCounters | `t_aircraft_operating_counter` (V3) | port-as-rows | S-183 | Per-aircraft counter readings. | [columns](legacy-tables/AircraftOperatingCounters/) |
| AircraftReservation | AircraftReservations | `t_aircraft_reservation` (V4) | port-as-rows | S-183 | Tenant-scoped reservation rows. | [columns](legacy-tables/AircraftReservations/) |
| AircraftReservationType | AircraftReservationTypes | `t_aircraft_reservation_type` (V4) | port-as-rows | S-183 | TENANT_SCOPED ref; ports via per-bundle map. | [columns](legacy-tables/AircraftReservationTypes/) |
| Aircraft | Aircrafts | `t_aircraft` (V3) | port-as-rows | S-183 | Cross-tenant entity per ADR 0008; `managing_club_id` mapped, `owner_club_id` nullable per S-159/S-058 amendment. | [columns](legacy-tables/Aircrafts/) |
| AircraftState | AircraftStates | `t_aircraft_state` (V3) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-013). | [columns](legacy-tables/AircraftStates/) |
| AircraftType | AircraftTypes | `t_aircraft_type` (V3) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-013). | [columns](legacy-tables/AircraftTypes/) |
| Article | Articles | `t_article` (V4) | port-as-rows | S-183 | Catalog item; tenant-scoped. | [columns](legacy-tables/Articles/) |
| — | AuditLogDetails | (dropped) | drop | S-183 | Framework-managed by legacy `TrackerEnabledDbContext`; superseded by S-027's `t_mutation_audit_event`. Manifest WHY-not-mapped. | [columns](legacy-tables/AuditLogDetails/) |
| — | AuditLogEventTypes | (dropped) | drop | S-183 | Framework-managed; superseded by S-027 `action` enum. Manifest WHY-not-mapped. | [columns](legacy-tables/AuditLogEventTypes/) |
| — | AuditLogs | `t_mutation_audit_event` (V9) | port-as-rows | S-183 | Mapper emits `actor_kind='LEGACY_MIGRATED'` + `legacy_actor_user_id` + NULL `actor_keycloak_sub`. Orphan refs → synthetic UUID v7 (one per distinct legacy actor per bundle) + `migration_run.warnings`. S-027 read-back coverage added per cross-story hand-off (AC14). | [columns](legacy-tables/AuditLogs/) |
| ClubExtension | ClubExtensions | `t_club_extension` (V2) | port-as-rows | S-183 | Per-club extension-type enablement; legacy `Extensions` master table is dropped. | [columns](legacy-tables/ClubExtensions/) |
| Club | Clubs | `t_club` (V5) | port-as-rows | S-183 | Tenant root; 1..N Clubs per Deployment per S-138. Renamed `t_club` per S-170. Also touched by S-048 (walking skeleton). | [columns](legacy-tables/Clubs/) |
| ClubState | ClubStates | `t_club_state` (V2) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-012). | [columns](legacy-tables/ClubStates/) |
| CounterUnitType | CounterUnitTypes | `t_counter_unit_type` (V2) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-012). | [columns](legacy-tables/CounterUnitTypes/) |
| Country | Countries | `t_country` (V2) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-012). Sample mapper shipped in S-016. | [columns](legacy-tables/Countries/) |
| Delivery | Deliveries | `t_delivery` (V4) | port-as-rows | S-183 | Invoice delivery; Swiss OR Art. 957a retention — tombstones ported per S-016 ref. | [columns](legacy-tables/Deliveries/) |
| DeliveryCreationTest | DeliveryCreationTests | `t_delivery_creation_test` (V4) | port-as-rows | S-183 | Splits into `t_delivery_creation_test` (root) + `t_delivery_creation_test_item` (line items) — legacy stored both in one table; new schema separates. | [columns](legacy-tables/DeliveryCreationTests/) |
| DeliveryItem | DeliveryItems | `t_delivery_item` (V4) | port-as-rows | S-183 | Delivery line items; tombstones ported (invoice retention). | [columns](legacy-tables/DeliveryItems/) |
| ElevationUnitType | ElevationUnitTypes | `t_elevation_unit_type` (V2) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-012). | [columns](legacy-tables/ElevationUnitTypes/) |
| EmailTemplate | EmailTemplates | `t_email_template` (V2) | port-as-rows | S-183 | Tenant-scoped templates. | [columns](legacy-tables/EmailTemplates/) |
| Extension | Extensions | (dropped) | drop | S-183 | Legacy .NET DLL-extension plugin manifest (`ExtensionClassName` / `ExtensionDllFilename` / `ExtensionDllPublicKey`) — irrelevant to the Java rewrite. Manifest WHY-not-mapped. | [columns](legacy-tables/Extensions/) |
| ExtensionType | ExtensionTypes | `t_extension_type` (V2) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-012). | [columns](legacy-tables/ExtensionTypes/) |
| ExtensionValue | ExtensionValues | `t_extension_value` (V2) | port-as-rows | S-183 | Per-instance extension values; tenant-scoped via parent FK. | [columns](legacy-tables/ExtensionValues/) |
| FlightAirState | FlightAirStates | (dropped) | drop | S-183 | V13 dropped destination — air state is computed not stored per ADR 0022 D2 (S-060). Flight mapper translates legacy `Flight.AirStateId == FlightPlanOpen` → `t_flight.flight_plan_opened_on` timestamp; other legacy air-state values dropped. Manifest WHY-not-mapped. | [columns](legacy-tables/FlightAirStates/) |
| FlightCostBalanceType | FlightCostBalanceTypes | `t_flight_cost_balance_type` (V4) | port-as-rows | S-183 | SYSTEM_GLOBAL ref per S-138 deviation note; resolves via `legacy_int_id` (S-014). | [columns](legacy-tables/FlightCostBalanceTypes/) |
| FlightCrew | FlightCrew | `t_flight_crew` (V3) | port-as-rows | S-183 | JMH-benched mapper (AC12). Person FK rewrites through per-bundle cross-tenant Person sub-map. | [columns](legacy-tables/FlightCrew/) |
| FlightCrewType | FlightCrewTypes | `t_flight_crew_type` (V3) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-013). | [columns](legacy-tables/FlightCrewTypes/) |
| FlightProcessState | FlightProcessStates | `t_flight_process_state` (V3) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-013). | [columns](legacy-tables/FlightProcessStates/) |
| Flight | Flights | `t_flight` (V3) | port-as-rows | S-183 | Tenant-scoped aggregate root; tombstones ported. Maps legacy `AirStateId == FlightPlanOpen` → `flight_plan_opened_on` per V13 contract. | [columns](legacy-tables/Flights/) |
| FlightType | FlightTypes | `t_flight_type` (V3) | port-as-rows | S-183 | TENANT_SCOPED ref; ports via per-bundle map; S-138 per-Club seed runs as no-op for ported types. | [columns](legacy-tables/FlightTypes/) |
| InOutboundPoint | InOutboundPoints | `t_inoutbound_point` (V3) | port-as-rows | S-183 | Per-flight in/out points; aggregate-internal under Location per S-024. | [columns](legacy-tables/InOutboundPoints/) |
| Language | Languages | `t_language` (V2) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-012). | [columns](legacy-tables/Languages/) |
| LanguageTranslation | LanguageTranslations | (dropped) | drop | S-183 | i18n owned by the Angular client per ADR 0004; server-side translation table superseded. Manifest WHY-not-mapped. | [columns](legacy-tables/LanguageTranslations/) |
| LengthUnitType | LengthUnitTypes | `t_length_unit_type` (V2) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-012). | [columns](legacy-tables/LengthUnitTypes/) |
| Location | Locations | `t_location` (V3) | port-as-rows | S-183 | Cross-tenant entity per ADR 0008 / V7 reshape; no `@TenantId` discriminator. | [columns](legacy-tables/Locations/) |
| LocationType | LocationTypes | `t_location_type` (V3) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-013). | [columns](legacy-tables/LocationTypes/) |
| MemberState | MemberStates | `t_member_state` (V2) | port-as-rows | S-183 | TENANT_SCOPED ref; ports via per-bundle map; S-138 per-Club seed runs as no-op for ported states (bundle-wins via `ux_member_state_club_name`). | [columns](legacy-tables/MemberStates/) |
| PersonCategory | PersonCategories | `t_person_category` (V2) | port-as-rows | S-183 | Per-Club category definitions; ports via per-bundle map. (Legacy `PersonPersonCategory` junction is dropped — see below.) | [columns](legacy-tables/PersonCategories/) |
| PersonClub | PersonClub | `t_person_club` (V2) | port-as-rows | S-183 | Person↔Club junction; Person FK is cross-tenant — flagged in manifest `tenantBypassFks`. New schema's boolean role flags (`is_glider_pilot`, etc.) derived from legacy `Person.Has*Licence` columns at the PersonClub mapper. | [columns](legacy-tables/PersonClub/) |
| PersonFlightTimeCredit | PersonFlightTimeCredits | (dropped) | drop | S-183 | Per AC6 unmapped registry — feature retired; balance never materialised into a new flight-credit aggregate. Manifest WHY-not-mapped. | [columns](legacy-tables/PersonFlightTimeCredits/) |
| PersonFlightTimeCreditTransaction | PersonFlightTimeCreditTransactions | (dropped) | drop | S-183 | Per AC6 unmapped registry — transaction history retired alongside parent. Manifest WHY-not-mapped. | [columns](legacy-tables/PersonFlightTimeCreditTransactions/) |
| PersonPersonCategory | PersonPersonCategories | (dropped) | drop | S-183 | Per AC6 unmapped registry — new schema doesn't model Person↔Category as a junction; per-Club boolean role flags on `t_person_club` cover the operational use cases. Manifest WHY-not-mapped. | [columns](legacy-tables/PersonPersonCategories/) |
| Person | Persons | `t_person` (V2) | port-as-rows | S-183 | Cross-tenant entity per ADR 0008; per-bundle cross-tenant sub-map drives FK rewrites (S-141 AC10). S-024 exemption list adds Person. | [columns](legacy-tables/Persons/) |
| PlanningDayAssignment | PlanningDayAssignments | `t_planning_day_assignment` (V4) | port-as-rows | S-183 | Tenant-scoped planning roles. | [columns](legacy-tables/PlanningDayAssignments/) |
| PlanningDayAssignmentType | PlanningDayAssignmentTypes | `t_planning_day_assignment_type` (V4) | port-as-rows | S-183 | TENANT_SCOPED ref; ports via per-bundle map. | [columns](legacy-tables/PlanningDayAssignmentTypes/) |
| PlanningDay | PlanningDays | `t_planning_day` (V4) | port-as-rows | S-183 | Tenant-scoped planning calendar. | [columns](legacy-tables/PlanningDays/) |
| Role | Roles | (dropped) | drop | S-052 | Realm-role catalog lives in Keycloak per ADR 0007; the legacy seed (ADMIN/FLIGHT_OPS/INSTRUCTOR/PILOT/READER) doesn't even match the realm catalog. Importer ignores legacy rows. | [columns](legacy-tables/Roles/) |
| Setting | Settings | (dropped) | drop | S-183 | Per AC6 unmapped registry — per-club KV config moved to typed `ClubSettings` aggregate / env config; legacy KV store not ported. Manifest WHY-not-mapped. S-024 exemption list adds system tables. | [columns](legacy-tables/Settings/) |
| StartType | StartTypes | `t_start_type` (V3) | port-as-rows | S-183 | SYSTEM_GLOBAL ref; resolves via `legacy_int_id` (S-013). | [columns](legacy-tables/StartTypes/) |
| SystemData | SystemData | (dropped) | drop | S-183 | Per AC6 unmapped registry — runtime/process metadata superseded by Spring Boot Actuator + ops tooling. Manifest WHY-not-mapped. | [columns](legacy-tables/SystemData/) |
| SystemLog | SystemLogs | (dropped) | drop | S-183 | Per AC6 unmapped registry — replaced by structured logging stack (no DB target). Manifest WHY-not-mapped. | [columns](legacy-tables/SystemLogs/) |
| SystemVersion | SystemVersion | (dropped) | drop | S-183 | Per AC6 unmapped registry — replaced by Flyway `flyway_schema_history`. Manifest WHY-not-mapped. | [columns](legacy-tables/SystemVersion/) |
| UserAccountState | UserAccountStates | (dropped) | drop | S-052 | KC `enabled` flag + `deleted_on` cover the states. Importer ignores legacy rows. | [columns](legacy-tables/UserAccountStates/) |
| UserRole | UserRoles | (dropped) | drop | S-052 | Roles live in Keycloak realm-roles per ADR 0007. Importer maps legacy role names to KC realm roles at provisioning time (S-028) without persisting the junction. | [columns](legacy-tables/UserRoles/) |
| User | Users | `t_user` (V2) | port-as-rows | S-052 | Created as `t_user` at S-052 (Postgres reserved-word collision); S-170 retroactively brought every other AlpenFlight table under the same convention without touching this row's contract. `keycloak_sub` minted by S-028 bulk-provision; passwords NEVER copied (C14). Legacy KC-shadow columns (`lockout_*`, `access_failed_count`, `two_factor_enabled`, `phone_number_confirmed`, `email_confirmed`) NOT mapped — KC owns those; importer maps legacy `email_confirmed=true` → KC `emailVerified=true` at provisioning. | [columns](legacy-tables/Users/) |

## Coverage check

A future story (or a one-shot script) should grep `_ORDER.md` against this file and assert every story that lists a `flsserver/database` path or names a legacy table in its acceptance has stamped the corresponding rows here. Until then, an operator's eyeball is the check — rows still showing `TBD` after their owning story has merged are bugs.
