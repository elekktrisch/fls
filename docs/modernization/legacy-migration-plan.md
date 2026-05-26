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
| AccountingRuleFilter | AccountingRuleFilters | TBD | TBD | TBD | | [columns](legacy-tables/AccountingRuleFilters/) |
| AccountingRuleFilterType | AccountingRuleFilterTypes | TBD | TBD | TBD | | [columns](legacy-tables/AccountingRuleFilterTypes/) |
| AccountingUnitType | AccountingUnitTypes | TBD | TBD | TBD | | [columns](legacy-tables/AccountingUnitTypes/) |
| AircraftAircraftState | AircraftAircraftStates | TBD | TBD | TBD | | [columns](legacy-tables/AircraftAircraftStates/) |
| AircraftOperatingCounter | AircraftOperatingCounters | TBD | TBD | TBD | | [columns](legacy-tables/AircraftOperatingCounters/) |
| AircraftReservation | AircraftReservations | TBD | TBD | TBD | | [columns](legacy-tables/AircraftReservations/) |
| AircraftReservationType | AircraftReservationTypes | TBD | TBD | TBD | | [columns](legacy-tables/AircraftReservationTypes/) |
| Aircraft | Aircrafts | TBD | TBD | TBD | | [columns](legacy-tables/Aircrafts/) |
| AircraftState | AircraftStates | TBD | TBD | TBD | | [columns](legacy-tables/AircraftStates/) |
| AircraftType | AircraftTypes | TBD | TBD | TBD | | [columns](legacy-tables/AircraftTypes/) |
| Article | Articles | TBD | TBD | TBD | | [columns](legacy-tables/Articles/) |
| — | AuditLogDetails | TBD | TBD | TBD | | [columns](legacy-tables/AuditLogDetails/) |
| — | AuditLogEventTypes | TBD | TBD | TBD | | [columns](legacy-tables/AuditLogEventTypes/) |
| — | AuditLogs | TBD | TBD | TBD | | [columns](legacy-tables/AuditLogs/) |
| ClubExtension | ClubExtensions | TBD | TBD | TBD | | [columns](legacy-tables/ClubExtensions/) |
| Club | Clubs | TBD | TBD | TBD | | [columns](legacy-tables/Clubs/) |
| ClubState | ClubStates | TBD | TBD | TBD | | [columns](legacy-tables/ClubStates/) |
| CounterUnitType | CounterUnitTypes | TBD | TBD | TBD | | [columns](legacy-tables/CounterUnitTypes/) |
| Country | Countries | TBD | TBD | TBD | | [columns](legacy-tables/Countries/) |
| Delivery | Deliveries | TBD | TBD | TBD | | [columns](legacy-tables/Deliveries/) |
| DeliveryCreationTest | DeliveryCreationTests | TBD | TBD | TBD | | [columns](legacy-tables/DeliveryCreationTests/) |
| DeliveryItem | DeliveryItems | TBD | TBD | TBD | | [columns](legacy-tables/DeliveryItems/) |
| ElevationUnitType | ElevationUnitTypes | TBD | TBD | TBD | | [columns](legacy-tables/ElevationUnitTypes/) |
| EmailTemplate | EmailTemplates | TBD | TBD | TBD | | [columns](legacy-tables/EmailTemplates/) |
| Extension | Extensions | TBD | TBD | TBD | | [columns](legacy-tables/Extensions/) |
| ExtensionType | ExtensionTypes | TBD | TBD | TBD | | [columns](legacy-tables/ExtensionTypes/) |
| ExtensionValue | ExtensionValues | TBD | TBD | TBD | | [columns](legacy-tables/ExtensionValues/) |
| FlightAirState | FlightAirStates | TBD | TBD | TBD | | [columns](legacy-tables/FlightAirStates/) |
| FlightCostBalanceType | FlightCostBalanceTypes | TBD | TBD | TBD | | [columns](legacy-tables/FlightCostBalanceTypes/) |
| FlightCrew | FlightCrew | TBD | TBD | TBD | | [columns](legacy-tables/FlightCrew/) |
| FlightCrewType | FlightCrewTypes | TBD | TBD | TBD | | [columns](legacy-tables/FlightCrewTypes/) |
| FlightProcessState | FlightProcessStates | TBD | TBD | TBD | | [columns](legacy-tables/FlightProcessStates/) |
| Flight | Flights | TBD | TBD | TBD | | [columns](legacy-tables/Flights/) |
| FlightType | FlightTypes | TBD | TBD | TBD | | [columns](legacy-tables/FlightTypes/) |
| InOutboundPoint | InOutboundPoints | TBD | TBD | TBD | | [columns](legacy-tables/InOutboundPoints/) |
| Language | Languages | TBD | TBD | TBD | | [columns](legacy-tables/Languages/) |
| LanguageTranslation | LanguageTranslations | TBD | TBD | TBD | | [columns](legacy-tables/LanguageTranslations/) |
| LengthUnitType | LengthUnitTypes | TBD | TBD | TBD | | [columns](legacy-tables/LengthUnitTypes/) |
| Location | Locations | TBD | TBD | TBD | | [columns](legacy-tables/Locations/) |
| LocationType | LocationTypes | TBD | TBD | TBD | | [columns](legacy-tables/LocationTypes/) |
| MemberState | MemberStates | TBD | TBD | TBD | | [columns](legacy-tables/MemberStates/) |
| PersonCategory | PersonCategories | TBD | TBD | TBD | | [columns](legacy-tables/PersonCategories/) |
| PersonClub | PersonClub | TBD | TBD | TBD | | [columns](legacy-tables/PersonClub/) |
| PersonFlightTimeCredit | PersonFlightTimeCredits | TBD | TBD | TBD | | [columns](legacy-tables/PersonFlightTimeCredits/) |
| PersonFlightTimeCreditTransaction | PersonFlightTimeCreditTransactions | TBD | TBD | TBD | | [columns](legacy-tables/PersonFlightTimeCreditTransactions/) |
| PersonPersonCategory | PersonPersonCategories | TBD | TBD | TBD | | [columns](legacy-tables/PersonPersonCategories/) |
| Person | Persons | TBD | TBD | TBD | | [columns](legacy-tables/Persons/) |
| PlanningDayAssignment | PlanningDayAssignments | TBD | TBD | TBD | | [columns](legacy-tables/PlanningDayAssignments/) |
| PlanningDayAssignmentType | PlanningDayAssignmentTypes | TBD | TBD | TBD | | [columns](legacy-tables/PlanningDayAssignmentTypes/) |
| PlanningDay | PlanningDays | TBD | TBD | TBD | | [columns](legacy-tables/PlanningDays/) |
| Role | Roles | TBD | TBD | TBD | | [columns](legacy-tables/Roles/) |
| Setting | Settings | TBD | TBD | TBD | | [columns](legacy-tables/Settings/) |
| StartType | StartTypes | TBD | TBD | TBD | | [columns](legacy-tables/StartTypes/) |
| SystemData | SystemData | TBD | TBD | TBD | | [columns](legacy-tables/SystemData/) |
| SystemLog | SystemLogs | TBD | TBD | TBD | | [columns](legacy-tables/SystemLogs/) |
| SystemVersion | SystemVersion | TBD | TBD | TBD | | [columns](legacy-tables/SystemVersion/) |
| UserAccountState | UserAccountStates | TBD | TBD | TBD | | [columns](legacy-tables/UserAccountStates/) |
| UserRole | UserRoles | TBD | TBD | TBD | | [columns](legacy-tables/UserRoles/) |
| User | Users | TBD | TBD | TBD | | [columns](legacy-tables/Users/) |

## Coverage check

A future story (or a one-shot script) should grep `_ORDER.md` against this file and assert every story that lists a `flsserver/database` path or names a legacy table in its acceptance has stamped the corresponding rows here. Until then, an operator's eyeball is the check — rows still showing `TBD` after their owning story has merged are bugs.
