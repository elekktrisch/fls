---
id: S-013
title: V1__baseline part 2 — flights / aircraft / persons / clubs / locations
epic: E-02
status: in_progress
started_at: 2026-05-16
depends_on: [S-012]
acceptance:
  - Tables defined: `flight`, `flight_crew`, `flight_crew_type`, `flight_type`, `flight_cost_balance_type`, `flight_process_state`, `flight_air_state`, `aircraft`, `aircraft_type`, `aircraft_state`, `aircraft_aircraft_state`, `aircraft_operating_counter`, `article`, `location`, `location_type`, `inoutbound_point` (16 tables — `flight_process_state` + `flight_air_state` promoted from "modelled as enum or lookup" into explicit lookup tables per ADR 0019 uniformity).
  - **Every PK is `UUID NOT NULL PRIMARY KEY`** (Postgres native `uuid`); every FK column is `uuid`. App-generated via Hibernate 7 + `f4b6a3:uuid-creator` `UuidCreator.getTimeOrderedEpoch()` at S-022; the migration MUST NOT use `DEFAULT gen_random_uuid()`. (Hibernate's built-in `@UuidGenerator(style = TIME)` is **UUID v1**, not v7 — Context7-confirmed.)
  - **Aggregate roots** per ADR 0018 (5): `Flight`, `Aircraft` (cross-tenant per amendment), `Location` (cross-tenant), `FlightType` (tenant-scoped), `Article` (tenant-scoped). Each carries an SQL `COMMENT ON COLUMN` on `id` referencing ADR 0019 + the prefix (`flt_` / `acf_` / `loc_` / `fty_` / `art_`).
  - **Aggregate-internal entities** (no prefix; raw UUID at every layer): `flight_crew` (under Flight), `aircraft_aircraft_state` + `aircraft_operating_counter` (under Aircraft), `inoutbound_point` (under Location).
  - **Reference data — not aggregates** (UUID PKs + `legacy_int_id SMALLINT UNIQUE` for S-016 cutover): `aircraft_type`, `aircraft_state`, `location_type`, `flight_crew_type`, `flight_process_state`, `flight_air_state`, `flight_cost_balance_type`. Seeded with fixed canonical UUID v7 literals (extends S-012's `reference-seeds-canonical-uuids.json` + the committed generator script).
  - `flight.flight_aircraft_type_id` is **`SMALLINT NOT NULL CHECK (flight_aircraft_type_id IN (1, 2, 4))`** — NOT a FK to a lookup. Sparse-enum sacred cow per legacy `FlightAircraftTypeValue.cs:5-7` (1=Glider, 2=Tow, 4=Motor — value 3 is deliberately skipped; GliderWithMotor lives on `Aircraft.AircraftType`, NOT on Flight).
  - `flight.tow_flight_id uuid NULL → flight.id ON DELETE SET NULL` self-FK + `CHECK (tow_flight_id IS NULL OR tow_flight_id <> id)` (no self-pairing) + `CHECK (tow_flight_id IS NULL OR flight_aircraft_type_id = 1)` (only glider flights link a tow).
  - `flight.operating_club_id uuid NOT NULL → club(id) ON DELETE RESTRICT` — set per-flight by the operator (the club whose operations are responsible for this flight). **NOT denormalized from aircraft** (per the 2026-05-16 amendment making Aircraft cross-tenant); charter case: Club B operates Club A's aircraft → `flight.operating_club_id = Club B`, `aircraft.owner_club_id = Club A`. Indexed.
  - `flight_crew.person_id uuid NOT NULL → person(id) ON DELETE RESTRICT` — **cross-tenant ride-through sacred cow** (preserved per ADR 0008 + S-011); RESTRICT preserves flight-history attribution (DSAR scrubs PII on Person, doesn't row-delete).
  - **Aircraft is CROSS_TENANT** (per the 2026-05-16 amendment): no `@TenantId`; `aircraft.owner_club_id uuid NULL → club(id) ON DELETE SET NULL` (renamed from legacy `OwnerClubId`; nullable so aircraft may be Person-owned, charter-shared, or rental-fleet). Sacred-cow parallel to Person + Location. Service layer (S-026) enforces "may this club use this aircraft for this Flight?" via owner / charter / public-rental checks.
  - `aircraft.aircraft_owner_person_id uuid NULL → person(id) ON DELETE SET NULL` — private-owner case; cross-tenant ride-through.
  - `aircraft_aircraft_state` + `aircraft_operating_counter` inherit Aircraft's cross-tenancy (state/counter records belong to the aircraft, not to a club's operations). No `operating_club_id` denormalization on either.
  - `aircraft_aircraft_state.noticed_by_person_id uuid NULL → person(id) ON DELETE SET NULL` — cross-tenant ride-through.
  - `aircraft.immatriculation VARCHAR(15) NOT NULL` + **global `UNIQUE (immatriculation) WHERE deleted_on IS NULL`** (was composite `(operating_club_id, immatriculation)` in prior refinement; with Aircraft cross-tenant the per-club uniqueness is no longer expressible — immatriculation is globally unique by aviation regulator convention).
  - `aircraft_aircraft_state` reshapes legacy composite PK `(AircraftId, AircraftStateId, ValidFrom)` to surrogate `id uuid PRIMARY KEY` + `UNIQUE (aircraft_id, valid_from)` + partial `UNIQUE (aircraft_id) WHERE valid_to IS NULL AND deleted_on IS NULL` (at most one current state per aircraft).
  - `aircraft_operating_counter`: cumulative time-series (NOT delta), one snapshot per `(aircraft_id, at_date_time)`; covered index `(aircraft_id, at_date_time DESC) INCLUDE (flight_operating_counter_in_seconds, engine_operating_counter_in_seconds)` for latest-counter Index Only Scan.
  - `location` has NO `operating_club_id` (sacred cow — shared cross-tenant; LSZH used by multiple clubs). `inoutbound_point.location_id → location.id ON DELETE CASCADE` (Location-aggregate internal).
  - `flight_type` reclassified from S-011's `reference` to **TENANT_SCOPED** (legacy `FlightType.cs:25` carries `ClubId NOT NULL`); aggregate root per ADR 0018.
  - `ALTER TABLE club` adds 5 deferred FK columns (from S-012) AFTER `flight_type` + `location` exist:
    - `homebase_id uuid NULL → location(id) ON DELETE SET NULL`
    - `default_glider_flight_type_id uuid NULL → flight_type(id) ON DELETE SET NULL`
    - `default_tow_flight_type_id uuid NULL → flight_type(id) ON DELETE SET NULL`
    - `default_motor_flight_type_id uuid NULL → flight_type(id) ON DELETE SET NULL`
    - `default_glider_with_motor_flight_type_id uuid NULL → flight_type(id) ON DELETE SET NULL` (**NOT in legacy `Club.cs:77-81` — forward-looking; migration header documents the deviation; operator can drop for strict parity**).
  - `tenant-rules.yaml` extended: `flight_type` reclassified TENANT_SCOPED; PII catalog (`flight.comment/incident_comment/validation_errors/outbound_route/inbound_route`, `aircraft.comment`, `aircraft_aircraft_state.remarks`, `location.description`); sensitive_columns (`aircraft.flarm_id`, `aircraft.spot_link`); ride_through_targets enumerated on `flight_crew`, `aircraft`, `aircraft_aircraft_state`.
  - Hot-path indexes (full grid in design notes): `(operating_club_id, flight_date DESC)` on flight; `(operating_club_id, process_state_id) INCLUDE (flight_date, id)` on flight; `(tow_flight_id) WHERE NOT NULL` partial; `(aircraft_id, flight_date DESC)` on flight; per-pilot `(person_id, flight_crew_type_id) INCLUDE (flight_id)` on flight_crew Index Only Scan; per-aircraft latest counter; per-club ones on flight_type/article.
  - Flyway migration succeeds against fresh Postgres in Testcontainers; `FlightBaselineIntegrationTest` asserts 16-table presence + UUID type pin per PK + per FK + tow self-FK + check constraints + aggregate-root column comments + reference-data canonical-UUID pins; `TenantCatalogConsistencyTest` extended with the 12 new entries + flight_type reclassification flip + PII catalog.
estimate: L
adr_refs: [0001, 0002, 0003, 0007, 0008, 0018, 0019]
parity_test: none
refined: true
refined_at: 2026-05-16
refined_speculative: false
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
github_issue: 32
github_pr: 33
---

## Context
Largest chunk of the schema and the load-bearing core for E-07. Sacred-cow shapes: single Flight entity discriminated by `FlightAircraftType`, glider↔tow link via `TowFlightId`, cross-tenant crew references.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Translate `Flight` columns from S-010 baseline; reshape allowed but document deltas.
- [ ] Self-FK on `tow_flight_id`.
- [ ] FlightCrew M:N between Flight and Person; with `FlightCrewType` lookup.
- [ ] FlightType + FlightCostBalanceType with `is_for_glider`/`is_for_tow`/`is_for_motor` flags.
- [ ] Aircraft + AircraftType + AircraftState + AircraftOperatingCounter.
- [ ] Location + LocationType + InOutboundPoint.
- [ ] Article (per-club, used by accounting rules).
- [ ] Add the hot-path indexes (commit hash `99a69c4` in legacy got this right — port them).

## Notes
This story is L because it touches ~16 tables. Tasks split it into the verifiable sub-pieces. Don't try to do it all in one PR — land tables in groups (Flight + crew; Aircraft cluster; Location cluster) so review is tractable.

## Implementation status (paused 2026-05-16)

PR #33 (draft). Branch: `story/S-013-schema-flights-aircraft-locations`. Operator paused after the boyscout slice; primary S-013 work not yet begun.

**Landed on the branch (4 boyscout commits + branch bootstrap):**

- `#32: start` — frontmatter status flip + refinement-adjustment carry (3 surgical brand flips from `/modernize-refine S-013 adjust for the rebranding`)
- `#32: boyscout — S-128 post-merge bookkeeping` — added `reviewed: true / merged: true / review_outcome: pass` frontmatter stamps + `## Review` section to `implemented/S-128-*.md`. Closes pending-followups item #1.
- `#32: boyscout — SKILL.md amendments (no-SHAs + git-mv ordering)` — amended `.claude/skills/modernize-implement/SKILL.md` + `.claude/skills/modernize-finalize/SKILL.md` with the no-SHAs-in-committed-docs Quality bar bullet, and added a "Pre-merge bookkeeping ordering" section to finalize Step 2.5 guarding against the rename-eats-content trap. Closes pending-followups items #2 + #3.
- `#32: boyscout — pgAdmin :8080 → :5050 (resolve AlpenFlight server conflict)` — rebound pgAdmin from `127.0.0.1:8080:80` to `127.0.0.1:5050:80` in `docker-compose.yml`, updated `next/ops/dev-up-full.sh` banner. Server stays on conventional 8080. Closes pending-followups item #4.
- `#32: stamp github_pr: 33 on frontmatter` — bookkeeping.

`pending-boyscout-followups.md` now empty.

**Pending (primary S-013 work — NOT yet started):**

1. Step 1 (full): read 870-line refinement in full, ADRs 0001/0002/0003/0007/0008/0018/0019, implemented S-012, key legacy entities (`flsserver/src/FLS.Server.Data/DbEntities/Flight.cs`, `Aircraft.cs`, `Location.cs`, `FlightType.cs`, `FlightCrew.cs`, etc.), the V1/V2 migrations on main for the baseline-pattern shape.
2. Step 1.5: Context7 freshness pass — Spring Boot 4.x, Flyway 11.x, Hibernate 7.x (UUID v7 + @TenantId), Testcontainers, Postgres 17.
3. Step 2.5: write `FlightBaselineIntegrationTest` (~70-80 assertions) + extend `TenantCatalogConsistencyTest` + `TenantCatalogYamlTest` with the 12 new entries + flight_type reclassification + PII catalog. Watch RED.
4. Write `V3__flights_aircraft_locations.sql` (~700-850 lines). **Note:** main currently has V1 + V2; the design notes anticipated V4 but S-018 (ShedLock) hasn't shipped — actual filename is V3, not V4. Re-numbering trivial; tests assert `>= 3`.
5. Extend `next/database/tenant-rules.yaml` per the refinement (12 new entries + reclassifications + PII catalog + sensitive_columns).
6. Iterate to green: `./gradlew clean check` in `next/server/`.
7. Step 6.7: `maintainability-reviewer` self-review (blockers-only).
8. Step 7: status: done, push, `gh pr ready 33`, post done comment on #32.

**Notes for pickup:**
- One-time contributor cleanup after pulling this branch: `docker compose -p fls-e2e --profile next down && docker compose -p fls-e2e --profile next up -d pgadmin` (port flip).
- The refinement-adjustment ALREADY shipped on the branch — no further rebrand sweep needed.
- 4 boyscout commits + branch bootstrap give a clean baseline; the next implement session should pick up at Step 1 above.

<!-- modernize-refine: start -->

## Design notes

### Migration shape

Ships as **single `V<n+1>__flights_aircraft_locations.sql`** (~700-850 lines). `<n+1>` picked at implement time from `next/server/src/main/resources/db/migration/` listing — likely **V4** (S-009 V1 `app_meta`, S-018 V2 `shedlock`, S-012 V3 `identity_and_reference`); tests assert `>= 4` not `== 4` to tolerate ordering shifts.

FK ordering: location_type → location → inoutbound_point; aircraft_type + aircraft_state; flight_cost_balance_type + flight_crew_type + flight_process_state + flight_air_state; flight_type; aircraft; aircraft_aircraft_state + aircraft_operating_counter; flight (with deferred self-FK constraint added post-create); flight_crew; article; **ALTER TABLE club** at the end (adds 5 FK columns to S-012's `club`).

Migration header documents: (a) UUID v7 PK convention + app-side generation; (b) ADR 0019 prefix scheme reference; (c) `flight.operating_club_id` denormalization invariant (S-011 § Flights INDIRECT→TENANT_SCOPED); (d) `aircraft_aircraft_state` composite→surrogate reshape; (e) `default_glider_with_motor_flight_type_id` deviation from legacy; (f) sparse-enum `flight_aircraft_type_id IN (1, 2, 4)` sacred cow.

### ID strategy (per ADR 0019; carries from S-012)

Pure UUID v7. Every PK + FK + `operating_club_id` column is `uuid`. No `DEFAULT gen_random_uuid()`. App generates via Hibernate 7 `@UuidV7` + `FlsUuidV7Generator` (wraps `f4b6a3:uuid-creator`'s `UuidCreator.getTimeOrderedEpoch()`) — S-022 wires; S-013 ships only the schema.

Audit columns on every TENANT_SCOPED + CROSS_TENANT-internal mutable table:
- `created_on TIMESTAMPTZ NOT NULL DEFAULT now()`, `created_by_user_id uuid` (no FK; chicken-and-egg per S-012)
- `modified_on TIMESTAMPTZ NOT NULL DEFAULT now()`, `modified_by_user_id uuid`

Reference tables (`aircraft_type` etc.) skip audit columns (operator-only via migration). Each reference row carries a `legacy_int_id SMALLINT UNIQUE` column for S-016 cutover remapping from legacy `int` IDs → these canonical UUIDs.

### Aggregate composition (per ADR 0018)

| Layer | Tables in S-013 | Tenant scope | Notes |
|---|---|---|---|
| **Aggregate roots** (5) | `flight`, `aircraft`, `location`, `flight_type`, `article` | mixed (see right column) | Prefix scheme `flt`/`acf`/`loc`/`fty`/`art` at JSON/URL/log boundary. SQL `COMMENT ON COLUMN` on `id` references ADR 0019. |
| → `flight` | | TENANT_SCOPED via `operating_club_id` (set per-flight by operator; NOT denormalized from aircraft) | |
| → `aircraft` | | **CROSS_TENANT** (amendment 2026-05-16) — optional `owner_club_id`; service layer enforces use-rights | |
| → `location` | | CROSS_TENANT (sacred cow; shared airports) | |
| → `flight_type` | | TENANT_SCOPED via `operating_club_id` (reclassified from S-011 `reference`) | |
| → `article` | | TENANT_SCOPED via `operating_club_id` | |
| **Internal entities under `Flight`** | `flight_crew` | inherits TENANT_SCOPED (via `flight.operating_club_id`; optionally denormalized) | CASCADE on Flight delete. Cross-tenant Person FK preserved as sacred cow. |
| **Internal entities under `Aircraft`** | `aircraft_aircraft_state`, `aircraft_operating_counter` | inherit **CROSS_TENANT** (state/counter records belong to the aircraft) | CASCADE on Aircraft delete. NO `operating_club_id` denormalization. |
| **Internal entities under `Location`** | `inoutbound_point` | inherits CROSS_TENANT | CASCADE on Location delete. |
| **System-global reference / lookup tables — not aggregates** | `aircraft_type`, `aircraft_state`, `location_type`, `flight_crew_type`, `flight_process_state`, `flight_air_state`, `flight_cost_balance_type` | SYSTEM_GLOBAL | Anemic JPA shape acceptable per ADR 0018 escape hatch. UUID PKs + `legacy_int_id SMALLINT UNIQUE` for S-016 cutover. |

Cross-aggregate FKs (UUIDs at the boundary; raw UUID at the DB):
- `flight.aircraft_id → aircraft.id ON DELETE RESTRICT` — **cross-tenant FK** (Aircraft is cross-tenant per amendment); FK loads not @TenantId-filtered, same shape as `flight_crew.person_id`. Service layer enforces use-rights.
- `flight.start_location_id / ldg_location_id → location.id ON DELETE SET NULL` (cross-tenant FK; Location is shared).
- `flight.flight_type_id → flight_type.id ON DELETE RESTRICT` (same-tenant; FlightType is per-club).
- `flight.tow_flight_id → flight.id ON DELETE SET NULL` (self-FK; same-tenant by inference — both flights have the same `operating_club_id`).
- `aircraft.homebase_id → location.id ON DELETE SET NULL`.
- `aircraft.owner_club_id uuid NULL → club.id ON DELETE SET NULL` — **optional**; aircraft may be Person-owned (private) or cross-club shared.

Cross-tenant ride-through FKs (Hibernate `@TenantId` does NOT filter FK-by-id loads; sacred-cow preserved per ADR 0008):
- `flight_crew.person_id → person.id ON DELETE RESTRICT`.
- `flight.aircraft_id → aircraft.id ON DELETE RESTRICT` (Aircraft cross-tenant per amendment).
- `aircraft.aircraft_owner_person_id → person.id ON DELETE SET NULL` (private-owner case).
- `aircraft_aircraft_state.noticed_by_person_id → person.id ON DELETE SET NULL`.
- `aircraft.owner_club_id → club.id ON DELETE SET NULL` (Aircraft cross-tenant; FK to Club).

### Per-table column inventory (load-bearing columns; lengths re-pinned from legacy)

**`flight`** (`Flight.cs`):
- `id uuid PRIMARY KEY`, `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `aircraft_id uuid NOT NULL → aircraft(id) RESTRICT`
- `flight_date DATE NULL` (`Flight.cs:29-30` `Column(TypeName="Date")`)
- `start_date_time TIMESTAMPTZ NULL`, `ldg_date_time TIMESTAMPTZ NULL` + `CHECK (ldg_date_time IS NULL OR start_date_time IS NULL OR ldg_date_time >= start_date_time)`
- `block_start_date_time TIMESTAMPTZ NULL`, `block_end_date_time TIMESTAMPTZ NULL` (analogous CHECK)
- `start_location_id uuid NULL → location(id) SET NULL`
- `ldg_location_id uuid NULL → location(id) SET NULL`
- `start_runway VARCHAR(5)`, `ldg_runway VARCHAR(5)` + regex CHECK
- `outbound_route VARCHAR(50)`, `inbound_route VARCHAR(50)` (PII catalog)
- `flight_type_id uuid NULL → flight_type(id) RESTRICT`
- `is_solo_flight BOOLEAN NOT NULL DEFAULT false` (note legacy `IsSoloFlight`, NOT `IsSoaringFlight`)
- `start_type_id uuid NULL → start_type(id)` (S-012 reference)
- `tow_flight_id uuid NULL → flight(id) SET NULL` + the two sacred-cow CHECKs (no self-pair; glider only)
- `nr_of_ldgs SMALLINT NULL` + `CHECK (>= 0)`, `nr_of_ldgs_on_start_location SMALLINT NULL` + `CHECK (<= nr_of_ldgs)`
- `no_start_time_information BOOLEAN NOT NULL DEFAULT false`, `no_ldg_time_information BOOLEAN NOT NULL DEFAULT false`
- `air_state_id uuid NOT NULL → flight_air_state(id) RESTRICT`
- `process_state_id uuid NOT NULL → flight_process_state(id) RESTRICT`
- `flight_aircraft_type_id SMALLINT NOT NULL CHECK (flight_aircraft_type_id IN (1, 2, 4))` — SMALLINT + CHECK enum (NOT lookup table) per sacred cow
- `engine_start_operating_counter_in_seconds BIGINT NULL`, `engine_end_operating_counter_in_seconds BIGINT NULL` + monotonic CHECK
- `comment TEXT`, `incident_comment TEXT`, `validation_errors TEXT` (PII catalog)
- `coupon_number VARCHAR(20)` + regex CHECK
- `flight_cost_balance_type_id uuid NULL → flight_cost_balance_type(id) RESTRICT`
- `delivery_created_on TIMESTAMPTZ NULL`, `validated_on TIMESTAMPTZ NULL`
- `nr_of_passengers SMALLINT NULL` + `CHECK (>= 0)`
- `start_position SMALLINT NULL` + `CHECK (BETWEEN 1 AND 999)`
- `flight_report_sent_on TIMESTAMPTZ NULL`
- Audit columns; soft-delete columns (`deleted_on`, `deleted_by_user_id`)
- **No denormalized `*_pilot_person_id` columns on flight** — legacy `Flight.cs:23-92` has no such columns; crew is M:N via `flight_crew` only (pilot/instructor/copilot accessors are computed properties over the collection). The story prompt's AC line listing `glider_pilot_person_id`/`flight_instructor_person_id`/`tow_pilot_person_id` is a misread — crew lives in `flight_crew`.

**`aircraft`** (`Aircraft.cs:13-87`) — **CROSS_TENANT per 2026-05-16 amendment** (no `@TenantId`):
- `id uuid PRIMARY KEY`, `owner_club_id uuid NULL → club(id) ON DELETE SET NULL` (renamed from legacy `AircraftOwnerClubId`; **nullable** to allow Person-owned + charter-shared + rental-fleet aircraft; SQL comment documents the cross-tenant model)
- `aircraft_type_id uuid NOT NULL → aircraft_type(id) RESTRICT`
- `manufacturer_name VARCHAR(100)`, `aircraft_model VARCHAR(50)`
- `immatriculation VARCHAR(15) NOT NULL` + **global UNIQUE `(immatriculation) WHERE deleted_on IS NULL`** (aircraft immatriculation is globally unique by aviation regulator convention; the per-club composite UNIQUE from the prior refinement is incompatible with cross-tenant Aircraft)
- `competition_sign VARCHAR(5)`, `flarm_id VARCHAR(50)` (sensitive_columns), `aircraft_serial_number VARCHAR(20)`
- `year_of_manufacture DATE` (reshape from legacy `datetime2`; month-precision meaningful)
- `noise_class CHAR(1)`, `noise_level NUMERIC(6,2)`, `mtom INTEGER`
- `nr_of_seats INTEGER` + `CHECK (>= 1)`
- `aircraft_owner_person_id uuid NULL → person(id) SET NULL` (private-owner case; cross-tenant ride-through). Aircraft owners: either `owner_club_id` set OR `aircraft_owner_person_id` set OR both NULL (charter pool); enforced at service layer (S-022), not DB (cheap-CHECK with-OR is brittle).
- `flight_operating_counter_unit_type_id uuid → counter_unit_type(id)`, `engine_operating_counter_unit_type_id uuid → counter_unit_type(id)` (S-012 reference tables)
- `homebase_id uuid NULL → location(id) SET NULL`
- `spot_link VARCHAR(250)` + `CHECK (~* '^https://')` (A10 SSRF mitigation; column comment "never fetched server-side")
- `is_towing_or_winch_required / is_towing_start_allowed / is_winch_start_allowed / is_towing_aircraft / is_fast_entry_record BOOLEAN NOT NULL DEFAULT false`
- `comment VARCHAR(250)` (PII catalog), `daec_index INTEGER`
- Audit + soft-delete

**`flight_crew`** (`FlightCrew.cs`):
- `id uuid PRIMARY KEY` (Flight-aggregate-internal; no prefix)
- `flight_id uuid NOT NULL → flight(id) CASCADE`
- `person_id uuid NOT NULL → person(id) RESTRICT` (cross-tenant; SQL comment documents sacred-cow contract + S-026 PersonClub-membership validation requirement)
- `flight_crew_type_id uuid NOT NULL → flight_crew_type(id) RESTRICT`
- `begin_flight_datetime TIMESTAMPTZ`, `end_flight_datetime TIMESTAMPTZ`, `begin_instruction_datetime TIMESTAMPTZ`, `end_instruction_datetime TIMESTAMPTZ`
- `nr_of_ldgs SMALLINT` + `CHECK (>= 0)`, `nr_of_starts SMALLINT` + `CHECK (>= 0)`
- Audit + soft-delete
- **Composite UNIQUE `(flight_id, person_id, flight_crew_type_id) WHERE deleted_on IS NULL`** — sacred-cow partial-unique per `tenant-rules.yaml:303`.

**`aircraft_aircraft_state`** (`AircraftAircraftState.cs:21-33`):
- `id uuid PRIMARY KEY` — **surrogate; reshapes legacy composite `(AircraftId, AircraftStateId, ValidFrom)`** — migration header documents
- `aircraft_id uuid NOT NULL → aircraft(id) CASCADE`
- `aircraft_state_id uuid NOT NULL → aircraft_state(id) RESTRICT`
- `valid_from TIMESTAMPTZ NOT NULL`, `valid_to TIMESTAMPTZ NULL` + `CHECK (valid_to IS NULL OR valid_to >= valid_from)`
- `noticed_by_person_id uuid NULL → person(id) SET NULL` (cross-tenant)
- `remarks TEXT` (PII catalog)
- Audit + soft-delete
- `UNIQUE (aircraft_id, valid_from)` (no temporal collision); partial `UNIQUE (aircraft_id) WHERE valid_to IS NULL AND deleted_on IS NULL` (at most one current state)

**`aircraft_operating_counter`** (`AircraftOperatingCounter.cs:20-37`):
- `id uuid PRIMARY KEY` (Aircraft-aggregate-internal; no prefix)
- `aircraft_id uuid NOT NULL → aircraft(id) CASCADE`
- `at_date_time TIMESTAMPTZ NOT NULL` + `CHECK (at_date_time <= now() + INTERVAL '1 day')`
- `total_towed_glider_starts INT`, `total_winch_launch_starts INT`, `total_self_starts INT` + `CHECK (>= 0)` per column
- `flight_operating_counter_in_seconds BIGINT`, `engine_operating_counter_in_seconds BIGINT` (legacy `long`) + `CHECK (>= 0)`
- `next_maintenance_at_flight_operating_counter_in_seconds BIGINT`, `next_maintenance_at_engine_operating_counter_in_seconds BIGINT`
- Audit + soft-delete
- `UNIQUE (aircraft_id, at_date_time)` (no dupe snapshots)
- **Cumulative snapshot, NOT delta** (sacred-cow legacy parity; SQL comment documents)
- Monotonic invariant (subsequent rows have non-decreasing totals) NOT schema-enforceable cross-row → S-022 service-layer guard

**`location`** (`Location.cs:13-90`) — **cross-tenant shared (no operating_club_id)**:
- `id uuid PRIMARY KEY` (aggregate root, `loc` prefix)
- `location_name VARCHAR(100) NOT NULL`, `location_short_name VARCHAR(50)`
- `country_id uuid NOT NULL → country(id) RESTRICT`, `location_type_id uuid NOT NULL → location_type(id) RESTRICT`
- `icao_code VARCHAR(10)` + partial UNIQUE `WHERE icao_code IS NOT NULL` + uppercase CHECK
- `latitude VARCHAR(10)`, `longitude VARCHAR(10)` — legacy verbatim strings; **PostGIS reshape deferred to a future mapping story** (loose regex CHECK only; backlog story flagged)
- `elevation INTEGER`, `elevation_unit_type_id uuid → elevation_unit_type(id)`
- `runway_direction VARCHAR(50)`, `runway_length INTEGER`, `runway_length_unit_type_id uuid → length_unit_type(id)`
- `airport_frequency VARCHAR(50)`
- `description TEXT` (PII catalog)
- `sort_indicator INTEGER`, `is_inbound_route_required / is_outbound_route_required / is_fast_entry_record BOOLEAN NOT NULL DEFAULT false`
- Audit + soft-delete
- SQL column comment on `location.id` documents cross-tenant shared status + SYSTEM_ADMIN-only mutation gate

**`flight_type`** (`FlightType.cs`) — **TENANT_SCOPED per-club** (reclassified from S-011 `reference`):
- `id uuid PRIMARY KEY` (aggregate root, `fty` prefix)
- `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `flight_type_name VARCHAR(100) NOT NULL`, `flight_code VARCHAR(30)` + composite UNIQUE `(operating_club_id, flight_code) WHERE flight_code IS NOT NULL AND deleted_on IS NULL`
- 11 booleans: `instructor_required`, `observer_pilot_or_instructor_required`, `is_check_flight`, `is_passenger_flight`, `is_solo_flight`, `is_for_glider_flights`, `is_for_tow_flights`, `is_for_motor_flights`, `is_flight_cost_balance_selectable`, `is_coupon_number_required`, `is_for_aircraft_reservation_type` — all `NOT NULL DEFAULT false`
- `min_nr_of_aircraft_seats_required INTEGER` + `CHECK (IS NULL OR >= 1)`
- Audit + soft-delete

**`article`** (`Article.cs`) — TENANT_SCOPED aggregate root (`art` prefix):
- `id uuid PRIMARY KEY`
- `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `article_number VARCHAR(50) NOT NULL` + composite UNIQUE `(operating_club_id, article_number) WHERE deleted_on IS NULL`
- `article_name VARCHAR(250) NOT NULL`, `article_info VARCHAR(250)`, `description TEXT`
- `is_active BOOLEAN NOT NULL DEFAULT true`
- Audit + soft-delete

**Reference data (7 tables; minimal columns + audit-light + `legacy_int_id SMALLINT UNIQUE`):**
- `aircraft_type` (8 rows; bit-field codes: Unknown=0, Glider=1, GliderWithMotor=2, MotorGlider=4, MotorAircraft=8, MultiEngine=16, Jet=32, Helicopter=64 per `AircraftType.cs`) + `has_engine / requires_towing_info / may_be_towing_aircraft BOOLEAN NULL`.
- `aircraft_state` (7 rows: OK/Information/Attention/Malfunction/Maintenance/Uninsured/EndOfLife per `AircraftStateKey.cs`) + `is_aircraft_flyable BOOLEAN NOT NULL`.
- `location_type` (17 rows per legacy snapshot).
- `flight_crew_type` (7 rows: PilotOrStudent=1, CoPilot=2, FlightInstructor=3, Passenger=4, WinchOperator=5, Observer=6, FlightCostInvoiceRecipient=10 per `FlightCrewType.cs`).
- `flight_process_state` (8 rows: NotProcessed=0, Invalid=28, Valid=30, Locked=40, DeliveryPreparationError=45, DeliveryPrepared=50, DeliveryBooked=60, ExcludedFromDeliveryProcess=99).
- `flight_air_state` (7 rows: New, FlightPlanOpen, MightBeStarted, Started, MightBeLandedOrInAir, Landed, FlightPlanClosed).
- `flight_cost_balance_type` (legacy snapshot) + `is_for_glider / is_for_tow / is_for_motor BOOLEAN NOT NULL DEFAULT false` + at-least-one CHECK.

### SQL `COMMENT ON COLUMN` for forensic clarity

```sql
COMMENT ON COLUMN flight.id IS
  'UUID v7. Aggregate root (ADR 0018). External form: flt_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN aircraft.id IS
  'UUID v7. Aggregate root (ADR 0018). External form: acf_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN location.id IS
  'UUID v7. Aggregate root (ADR 0018). External form: loc_<crockford-base32>. See ADR 0019. Cross-tenant shared resource (per S-011 sacred cow); SYSTEM_ADMIN-only mutation.';
COMMENT ON COLUMN flight_type.id IS
  'UUID v7. Aggregate root (ADR 0018). External form: fty_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN article.id IS
  'UUID v7. Aggregate root (ADR 0018). External form: art_<crockford-base32>. See ADR 0019.';

COMMENT ON COLUMN flight.operating_club_id IS
  'Set per-flight by the operator (the club whose operations are responsible for this flight). NOT denormalized from aircraft (per 2026-05-16 Aircraft-cross-tenant amendment); charter case: Club B operates Club A''s aircraft → flight.operating_club_id = Club B, aircraft.owner_club_id = Club A.';
COMMENT ON COLUMN aircraft.owner_club_id IS
  'Optional Club owner of the aircraft. NULL = privately owned (see aircraft_owner_person_id) or charter-pool aircraft. SET NULL on Club delete preserves the aircraft row. Aircraft itself is CROSS_TENANT — no @TenantId.';
COMMENT ON COLUMN flight.aircraft_id IS
  'Cross-tenant FK (Aircraft is cross-tenant per 2026-05-16 amendment). FK loads not @TenantId-filtered; service layer (S-026) verifies the flight''s operating_club is authorized to use this aircraft (owner / charter / public-rental check).';
COMMENT ON COLUMN flight.flight_aircraft_type_id IS
  'Sparse-enum sacred cow: 1=Glider, 2=Tow, 4=Motor (per FlightAircraftTypeValue.cs:5-7); value 3 is deliberately skipped; GliderWithMotor lives on aircraft.aircraft_type_id, NOT here. SMALLINT + CHECK enforced; NOT a lookup table.';
COMMENT ON COLUMN flight.tow_flight_id IS
  'Self-FK; populated ONLY for Glider flights with start_type=TowingByAircraft. Two CHECKs: no self-pair; only glider may link a tow. SET NULL on delete.';
COMMENT ON COLUMN flight_crew.person_id IS
  'Cross-tenant Person FK (sacred cow per ADR 0008 + S-011). RESTRICT on delete preserves flight-history attribution; DSAR scrubs PII on Person row, not row-delete. Service layer (S-026) must verify PersonClub membership before INSERT.';
COMMENT ON COLUMN aircraft.aircraft_owner_person_id IS
  'Cross-tenant ride-through; SET NULL on delete for FADP erasure.';
COMMENT ON COLUMN aircraft_aircraft_state.noticed_by_person_id IS
  'Cross-tenant ride-through; SET NULL on delete.';
COMMENT ON COLUMN aircraft.spot_link IS
  'External URL; NEVER fetched server-side (A10 SSRF mitigation). https-only CHECK enforced. Render link only in UI.';

-- Free-text PII columns (S-027 audit-blob redaction policy):
COMMENT ON COLUMN flight.comment IS 'Free text; PII-spill risk; redact in audit blob and never log raw';
COMMENT ON COLUMN flight.incident_comment IS 'Free text; PII-spill risk; redact in audit blob and never log raw';
COMMENT ON COLUMN flight.validation_errors IS 'Free text; PII-spill risk; redact in audit blob and never log raw';
COMMENT ON COLUMN flight.outbound_route IS 'Free text; PII-spill risk; redact in audit blob';
COMMENT ON COLUMN flight.inbound_route IS 'Free text; PII-spill risk; redact in audit blob';
COMMENT ON COLUMN aircraft.comment IS 'Free text; PII-spill risk; redact in audit blob';
COMMENT ON COLUMN aircraft_aircraft_state.remarks IS 'Free text; PII-spill risk; redact in audit blob';
COMMENT ON COLUMN location.description IS 'Free text shared cross-tenant; PII-spill risk; redact in audit blob; review on edit';
```

Internal-entity PKs (`flight_crew.id`, `aircraft_aircraft_state.id`, `aircraft_operating_counter.id`, `inoutbound_point.id`) get NO prefix comment — raw UUID at every layer per ADR 0019.

### Index strategy (full per-table grid)

```sql
-- flight (load-bearing hot table)
CREATE INDEX ix_flight_club_date          ON flight (operating_club_id, flight_date DESC);
CREATE INDEX ix_flight_club_state         ON flight (operating_club_id, process_state_id) INCLUDE (flight_date, id);
CREATE INDEX ix_flight_aircraft_date      ON flight (aircraft_id, flight_date DESC);
CREATE INDEX ix_flight_tow_flight         ON flight (tow_flight_id) WHERE tow_flight_id IS NOT NULL;
CREATE INDEX ix_flight_start_location     ON flight (operating_club_id, start_location_id, flight_date) WHERE start_location_id IS NOT NULL;
CREATE INDEX ix_flight_ldg_location       ON flight (operating_club_id, ldg_location_id, flight_date)   WHERE ldg_location_id IS NOT NULL;
CREATE INDEX ix_flight_flight_type        ON flight (operating_club_id, flight_type_id) WHERE flight_type_id IS NOT NULL;
CREATE INDEX ix_flight_club_aircraft_type ON flight (operating_club_id, flight_aircraft_type_id, flight_date DESC);
CREATE INDEX ix_flight_validated_on       ON flight (operating_club_id, validated_on) WHERE validated_on IS NULL;
CREATE INDEX ix_flight_coupon             ON flight (operating_club_id, coupon_number) WHERE coupon_number IS NOT NULL;

-- flight_crew
CREATE UNIQUE INDEX ux_flight_crew_unique ON flight_crew (flight_id, person_id, flight_crew_type_id) WHERE deleted_on IS NULL;
CREATE INDEX ix_flight_crew_flight        ON flight_crew (flight_id);
CREATE INDEX ix_flight_crew_person_type   ON flight_crew (person_id, flight_crew_type_id) INCLUDE (flight_id);

-- aircraft (CROSS_TENANT per 2026-05-16 amendment; no per-club composite indexes)
CREATE UNIQUE INDEX ux_aircraft_immatriculation ON aircraft (immatriculation) WHERE deleted_on IS NULL;  -- GLOBAL unique
CREATE INDEX ix_aircraft_owner_club       ON aircraft (owner_club_id) WHERE owner_club_id IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_aircraft_type             ON aircraft (aircraft_type_id);
CREATE INDEX ix_aircraft_homebase         ON aircraft (homebase_id) WHERE homebase_id IS NOT NULL;
CREATE INDEX ix_aircraft_owner_person     ON aircraft (aircraft_owner_person_id) WHERE aircraft_owner_person_id IS NOT NULL;

-- aircraft_operating_counter (time-series; cumulative snapshots)
CREATE INDEX ix_aoc_aircraft_recorded     ON aircraft_operating_counter (aircraft_id, at_date_time DESC) INCLUDE (flight_operating_counter_in_seconds, engine_operating_counter_in_seconds);

-- aircraft_aircraft_state
CREATE INDEX ix_aas_aircraft_valid        ON aircraft_aircraft_state (aircraft_id, valid_from DESC);
CREATE INDEX ix_aas_current               ON aircraft_aircraft_state (aircraft_id) WHERE valid_to IS NULL AND deleted_on IS NULL;

-- flight_type
CREATE INDEX ix_flight_type_club          ON flight_type (operating_club_id) WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_flight_type_club_code ON flight_type (operating_club_id, flight_code) WHERE flight_code IS NOT NULL AND deleted_on IS NULL;

-- article
CREATE INDEX ix_article_club              ON article (operating_club_id) WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_article_club_number ON article (operating_club_id, article_number) WHERE deleted_on IS NULL;

-- location
CREATE INDEX ix_location_country_type     ON location (country_id, location_type_id);
CREATE UNIQUE INDEX ux_location_icao      ON location (icao_code) WHERE icao_code IS NOT NULL;
CREATE INDEX ix_location_name_lower       ON location (LOWER(location_name));

-- inoutbound_point
CREATE INDEX ix_iop_location              ON inoutbound_point (location_id);
```

### FK cascade rules (full grid)

| FK | ON DELETE | Rationale |
|---|---|---|
| `flight.operating_club_id → club.id` | RESTRICT | Cannot delete tenant with flights |
| `flight.aircraft_id → aircraft.id` | RESTRICT | Preserve flight history; soft-delete aircraft |
| `flight.flight_type_id → flight_type.id` | RESTRICT | |
| `flight.start_location_id → location.id` | SET NULL | Location is shared reference; airfield retire doesn't void flight |
| `flight.ldg_location_id → location.id` | SET NULL | Same |
| `flight.start_type_id → start_type.id` | RESTRICT | |
| `flight.flight_cost_balance_type_id → flight_cost_balance_type.id` | RESTRICT | |
| `flight.air_state_id → flight_air_state.id` | RESTRICT | |
| `flight.process_state_id → flight_process_state.id` | RESTRICT | |
| `flight.tow_flight_id → flight.id` | SET NULL | Glider survives if tow row hard-deleted |
| `flight_crew.flight_id → flight.id` | CASCADE | Crew dies with flight |
| `flight_crew.person_id → person.id` | RESTRICT | **Sacred-cow cross-tenant**; preserve flight history; DSAR scrubs PII, not row |
| `flight_crew.flight_crew_type_id → flight_crew_type.id` | RESTRICT | |
| `aircraft.owner_club_id → club.id` | SET NULL | **Cross-tenant FK** (per 2026-05-16 amendment); club delete preserves aircraft row |
| `aircraft.aircraft_type_id → aircraft_type.id` | RESTRICT | |
| `aircraft.aircraft_owner_person_id → person.id` | SET NULL | Cross-tenant ride-through |
| `aircraft.homebase_id → location.id` | SET NULL | |
| `aircraft.flight_operating_counter_unit_type_id → counter_unit_type.id` | RESTRICT | |
| `aircraft.engine_operating_counter_unit_type_id → counter_unit_type.id` | RESTRICT | |
| `aircraft_aircraft_state.aircraft_id → aircraft.id` | CASCADE | State history dies with aircraft (inherits CROSS_TENANT) |
| `aircraft_aircraft_state.aircraft_state_id → aircraft_state.id` | RESTRICT | |
| `aircraft_aircraft_state.noticed_by_person_id → person.id` | SET NULL | Cross-tenant ride-through |
| `aircraft_operating_counter.aircraft_id → aircraft.id` | CASCADE | Counter series dies with aircraft (inherits CROSS_TENANT) |
| `flight_type.operating_club_id → club.id` | RESTRICT | |
| `article.operating_club_id → club.id` | RESTRICT | |
| `location.country_id → country.id` | RESTRICT | |
| `location.location_type_id → location_type.id` | RESTRICT | |
| `location.elevation_unit_type_id → elevation_unit_type.id` | RESTRICT | |
| `location.runway_length_unit_type_id → length_unit_type.id` | RESTRICT | |
| `inoutbound_point.location_id → location.id` | CASCADE | Inbound point meaningless without location |
| `club.homebase_id → location.id` | SET NULL | |
| `club.default_*_flight_type_id → flight_type.id` | SET NULL | (4 FKs added via ALTER TABLE) |

### `tenant-rules.yaml` updates

```yaml
# Reclassify (legacy carries ClubId):
FlightTypes: { kind: tenant-scoped, target_entity: FlightType, tenant_column: operating_club_id, emits_audit: true }

# Aircraft RECLASSIFIED to cross-tenant per 2026-05-16 amendment (charter / loan / private ownership):
Aircrafts:
  kind: cross-tenant   # no @TenantId
  target_entity: Aircraft
  tenant_column_legacy: OwnerClubId
  owner_column: owner_club_id   # uuid NULL → club(id) ON DELETE SET NULL
  emits_audit: true
  ride_through_targets: [person, location, club]   # FK to Club is now ride-through, not tenant
  pii_columns: [comment]
  sensitive_columns: [immatriculation, flarm_id, competition_sign, aircraft_serial_number, mtom, noise_class, noise_level, spot_link]
  pii_ride_through: [aircraft_owner_person_id]

# Append TENANT_SCOPED operational tables:
flight:
  kind: tenant-scoped
  target_entity: Flight
  tenant_column: operating_club_id
  emits_audit: true
  ride_through_targets: [person, aircraft, location]   # aircraft now cross-tenant per amendment
  pii_columns: [comment, incident_comment, validation_errors, outbound_route, inbound_route, coupon_number]
  preconditions:
    - "flight.operating_club_id set per-flight by operator (NOT denormalized from aircraft per 2026-05-16 Aircraft-cross-tenant amendment)"
    - "S-022 service layer enforces 'may this club use this aircraft?' via owner_club_id / charter agreement / public-rental checks"

flight_crew:
  kind: tenant-scoped  # indirect (via flight); operating_club_id denormalized at S-022 if needed
  target_entity: FlightCrew
  ride_through_targets: [person]
  pii_ride_through: [person_id]
  emits_audit: true

aircraft_aircraft_state:
  kind: cross-tenant   # inherits from Aircraft per 2026-05-16 amendment
  target_entity: AircraftAircraftState
  ride_through_targets: [person]
  pii_columns: [remarks]
  pii_ride_through: [noticed_by_person_id]

aircraft_operating_counter:
  kind: cross-tenant   # inherits from Aircraft per 2026-05-16 amendment
  target_entity: AircraftOperatingCounter

article:
  kind: tenant-scoped
  target_entity: Article
  tenant_column: operating_club_id
  emits_audit: true

# REFERENCE_DATA entries
location: { kind: reference, target_entity: Location, pii_columns: [description] }   # sacred-cow shared cross-tenant
inoutbound_point: { kind: reference, target_entity: InOutboundPoint }
location_type:    { kind: reference, target_entity: LocationType }
aircraft_type:    { kind: reference, target_entity: AircraftType }
aircraft_state:   { kind: reference, target_entity: AircraftState }
flight_crew_type: { kind: reference, target_entity: FlightCrewType }
flight_cost_balance_type: { kind: reference, target_entity: FlightCostBalanceType }
flight_process_state: { kind: reference, target_entity: FlightProcessState }
flight_air_state:     { kind: reference, target_entity: FlightAirState }
```

### Reference-data seeds — fixed canonical UUID v7 literals

Same approach as S-012:
1. Committed generator script (`generate-canonical-uuids.java` extends S-012's) emits UUIDs once.
2. Embed as literals in the SQL migration (`'01234567-89ab-...'::uuid`).
3. `next/server/src/test/resources/reference-seeds-canonical-uuids.json` extends S-012's file with S-013's 7 reference tables; tests pin against this ground truth.
4. Each row carries `legacy_int_id SMALLINT UNIQUE` for S-016 cutover remapping.

### Module layout

- New: `next/server/src/main/resources/db/migration/V<n+1>__flights_aircraft_locations.sql` (~700-850 lines).
- Edit: `next/database/tenant-rules.yaml` (12 new entries + flight_type reclassification + PII catalog extensions).
- New: `next/server/src/test/java/ch/alpenflight/server/migration/FlightBaselineIntegrationTest.java` (~70-80 tests).
- Extend: `MigrationFolderConventionsTest`, `FlywayBootstrapIntegrationTest`, `TenantCatalogConsistencyTest` (from S-012).
- Extend: `next/server/src/test/resources/reference-seeds-canonical-uuids.json` (canonical UUIDs for the 7 new reference tables).
- Edit: `next/server/src/test/resources/forbidden-migration-patterns.txt` — allowlist new reference-seed INSERTs; deny `INSERT INTO flight|flight_crew|aircraft|aircraft_aircraft_state|aircraft_operating_counter|article|location|inoutbound_point`.

### Alternatives considered

- **Chosen — single V<n+1> migration with 16 tables + seeds + ALTER TABLE club + UUID v7 PKs + 5 aggregate roots.** FK graph spans 3 clusters; splitting forces fake bridge migrations.
- Rejected — Hibernate built-in `@UuidGenerator(style = TIME)` (Context7-confirmed: UUID v1, NOT v7). Use `f4b6a3:uuid-creator` via custom `BeforeExecutionGenerator` at S-022.
- Rejected — Postgres `DEFAULT gen_random_uuid()` (v4 random; breaks ADR 0019 time-ordering locality + app-generation contract).
- Rejected — `flight_aircraft_type_id` as a UUID-keyed lookup table. Sacred-cow sparse enum `{1, 2, 4}`; never changes without code change; SMALLINT + CHECK saves ~14 B/row × 5M rows ≈ 70 MB + a hot-path FK join. NOT the same axis as `aircraft.aircraft_type_id` (which IS a lookup with 8 bit-field rows).
- Rejected — subtype tables (`glider_flight`, `tow_flight`, `motor_flight`). Sacred cow keeps single Flight; subtype would force UNION-VIEW for every list query.
- Rejected — delta-event `aircraft_operating_counter`. Legacy cumulative snapshots are the sacred shape; delta would lose precision.
- Rejected — PostGIS `geography(POINT)` for `location.latitude/longitude` in S-013. Defer to a future mapping story (no story exists in `_ORDER.md`; backlog flag).
- Rejected — `flight.tow_flight_id` ON DELETE RESTRICT. SET NULL handles glider-with-orphan-tow correctly; soft-delete is the actual lifecycle.
- Rejected — `flight_crew.person_id` ON DELETE SET NULL (the story prompt's reading). Sacred cow: preserve flight history; DSAR scrubs PII, doesn't delete crew rows. RESTRICT forces correct semantics.
- Rejected — `aircraft_aircraft_state` composite PK (legacy parity). Hibernate composite-key boilerplate at S-022; surrogate UUID + composite UNIQUE is the canonical reshape (same pattern as S-012's `person_club`).

## Edge cases & hidden requirements

### Per-AC edge cases

**AC1 — 16 tables, UUID PKs:**
- `flight_process_state` + `flight_air_state` promoted from "modelled as enum or lookup" into explicit lookup tables. The frontmatter's 14-table count was understated.
- `flight_aircraft_type_id` is **SMALLINT + CHECK**, NOT a lookup table — sparse-enum sacred cow per `FlightAircraftTypeValue.cs:5-7`.
- `AircraftType` bit-field 0/1/2/4/8/16/32/64 preserved as `legacy_int_id` on the lookup row (sacred cow per legacy reports + S-016 cutover).
- `flight_type` reclassified TENANT_SCOPED (legacy `ClubId NOT NULL` per `FlightType.cs:25`).

**AC2 — self-FK + tenant FK:**
- `tow_flight_id` ON DELETE SET NULL (chosen over RESTRICT).
- Two CHECK constraints: no self-pair; only glider links a tow.
- 1:1 vs 1:N cardinality is an open question (recommend 1:N at row level — legacy `Flight.TowedFlights` collection allows touring-tow patterns).

**AC3 — `flight.operating_club_id` denormalization:**
- Invariant `flight.operating_club_id == aircraft.operating_club_id` enforced at S-022 service layer (Open Q3 — DB trigger vs `@PrePersist`).
- DB CHECK with subquery NOT allowed in Postgres; parity test pins the invariant at the row level.

**AC4 — cross-tenant Person FKs:**
- **Story prompt names `glider_pilot_person_id`/`flight_instructor_person_id`/`tow_pilot_person_id` on flight — these do NOT exist in legacy.** Crew is M:N via `flight_crew` only; pilot/instructor accessors are computed properties over the collection. Refinement keeps `flight_crew.person_id` as the sole cross-tenant FK.
- Cross-tenant tampering threat: schema cannot defend; service must verify `person_club` membership at S-022/S-026.

**AC5 — discriminator modeling:**
- `flight_aircraft_type_id` SMALLINT + CHECK enum (chosen — sacred cow).
- `process_state_id` and `air_state_id` lookup tables with canonical UUIDs (chosen — UI display copy + operator-editable comments).

**AC6 — indexes:**
- Story lists 3; full grid above lists ~25.

### Hidden requirements (promoted)

- `flight_process_state` + `flight_air_state` promoted from hidden to explicit lookup tables in AC1.
- `aircraft_aircraft_state` composite→surrogate PK reshape (migration header documents).
- `ALTER TABLE club` for 5 deferred FK columns (homebase_id + 4× default_*_flight_type_id).
- `tenant-rules.yaml` PII catalog + classification flip for `flight_type` + cross-tenant ride-through enumeration.
- `legacy_int_id SMALLINT UNIQUE` on reference tables that legacy keys by int (aircraft_type/aircraft_state/flight_crew_type/flight_process_state/flight_air_state/flight_cost_balance_type) for S-016 cutover.
- SQL `COMMENT ON COLUMN` on 5 aggregate-root `id` columns + 8 free-text PII columns + 3 cross-tenant FKs + sparse-enum sacred cow + tow self-FK contract.
- `forbidden-migration-patterns.txt` extensions (allowlist reference seeds; deny INSERTs into app tables).

### Scope clarifications

**In:** 16 tables + ALTER TABLE club (5 deferred FKs) + indexes + FKs + CHECK constraints + reference-data seeds (canonical UUIDs + legacy_int_id) + tenant-rules.yaml updates (reclassification + PII catalog + ride-through) + `FlightBaselineIntegrationTest` + extensions to `TenantCatalogConsistencyTest` + SQL column comments + `forbidden-migration-patterns.txt` updates.

**Out:** Reservations/Planning/Accounting → S-014. Audit log → S-027. JPA entities + `@TenantId` annotations + `FlsUuidV7Generator` wiring + strong-typed ID records → S-022. `@TenantId` filter behavior tests → S-022. Aggregate-method invariants (`Flight.assignCrew`, `Flight.attachTowFlight`) → S-022/S-058. Live leakage CI → S-024. Flight state-machine transition rules → S-058. Per-club aircraft availability rules → backlog (no current story; flag). PostGIS reshape of `location.latitude/longitude` → backlog. Legacy → new UUID remap → S-016 (uses `legacy_int_id`). Production-scale perf (5M flights, 25M flight_crew) → S-108. DSAR cross-club cascade → S-051. `keycloak_sub` backfill → S-052.

### Things not the right shape

- AC1 title says "V1__baseline part 2" — V1 is locked by S-009. Ships as V<n+1> (likely V4).
- AC1 frontmatter listed 14 tables; refinement promotes to 16 with `flight_process_state` + `flight_air_state`.
- AC3 lists denormalized `*_pilot_person_id` columns that don't exist in legacy — crew is M:N via `flight_crew` only.
- AC5 says "GliderWithMotor" Flight discriminator — that value belongs to `aircraft.aircraft_type_id`, NOT to `flight.flight_aircraft_type_id` (sparse {1,2,4} only).
- AC6 lists 3 indexes; load-bearing inventory is ~25.
- `parity_test: none` understates — enum value sets ARE parity-checkable via `legacy_int_id` pinning + canonical UUID seeds; tests pin with citations to legacy enum files.
- Story prompt's "S-082/S-090 aircraft availability rules" — neither owns that. No such story in `_ORDER.md`; flag for backlog.

## Security plan

### Threat model

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| (a) | Cross-tenant Person FK tampering — Club A user POSTs Flight with `flight_crew.person_id` for Person B | High | Schema cannot prevent (sacred cow); aggregate-method `Flight.assignCrew(person, type)` at S-022 calls `PersonClubRepository.exists(person_id, operating_club_id, is_active=true)`; S-024 leakage CI |
| (b) | Aircraft commercial-intel disclosure (immatriculation, flarm_id, competition_sign, aircraft_serial_number, aircraft_owner_person_id, spot_link) | High | `@TenantId` via `operating_club_id`; PII catalog flags `flarm_id`/`spot_link` as `sensitive_columns`; redaction in audit blob; column comments |
| (c) | Free-text PII spill (`flight.comment`, `flight.incident_comment`, `flight.validation_errors`, `flight.outbound_route`, `flight.inbound_route`, `aircraft.comment`, `aircraft_aircraft_state.remarks`, `location.description`) | High | SQL column comments flag; S-027 audit blob applies redaction list; structured logs never echo |
| (d) | Tow-flight self-FK manipulation | High | FK + 2 CHECK constraints; aggregate method `Flight.attachTowFlight()` becomes immutable post-Locked (S-077); audit before/after |
| (e) | Billing manipulation via flag-flip (`flight_cost_balance_type_id`, `no_start_time_information`, `no_ldg_time_information`, `nr_of_ldgs`, `engine_*_operating_counter_in_seconds`, `coupon_number`) | High | Aggregate `Flight.lock()` freezes mutation post-Preparation (S-077); audit log; CHECK `(process_state_id < BILLED) OR (modified_on = locked_on)` advisory |
| (f) | Flight-path / customer-activity exposure via (start_location, ldg_location, flight_date) | Med | List APIs role-gated within club (S-026); cross-club lists via UnscopedTenantContext + SYSTEM_ADMIN |
| (g) | Article tampering (price/accounting drift) | High | `@TenantId` operating_club_id; ACCOUNTANT/ADMIN gate at S-026; audit on every mutation |
| (h) | Aircraft state spoofing | Med | Append-only via `Aircraft.recordState()` aggregate method; audit captures actor; CHECK valid_to >= valid_from |
| (i) | AircraftOperatingCounter monotonic invariant violation | Med | CHECK >= 0 per column; service-layer monotonic guard (schema cannot enforce cross-row) |
| (j) | DSAR cascade — Person erasure blanks pilot attribution | Med | `flight_crew.person_id ON DELETE RESTRICT` preserves attribution; `aircraft.aircraft_owner_person_id` + `aircraft_aircraft_state.noticed_by_person_id` SET NULL; column comments document |
| (k) | UUID v7 timestamp leak in error messages | Low | UUID v7's leading 48 bits expose record creation time; for AlpenFlight Flight/Aircraft this is operationally-known anyway; document in CONVENTIONS.md |
| (l) | Aggregate-prefix reveals entity type | Very Low | By design; humans + audit-log search benefit |
| (m) | Forbidden-pattern regression on new reference seeds | Low | Extend allowlist + deny INSERTs into app tables |
| (n) | Charter/cross-club aircraft use authorization gap (replaces former "denormalization drift" threat per 2026-05-16 amendment) | High | Aircraft is cross-tenant; service layer must verify the Flight's `operating_club_id` is authorized to use the aircraft (owner / charter / public-rental check) BEFORE accepting the Flight insert. Schema cannot enforce; S-022 + S-026 + audit log on every Flight insert with cross-club aircraft (`flight.operating_club_id != aircraft.owner_club_id`) for forensic trail. |
| (o) | `location.description` cross-tenant spillover (shared resource) | Low | SYSTEM_ADMIN-only mutation at S-026; audit on every Location mutation; column comment |

### Authorization

- DB-role split (unchanged from S-012): `migrator` (DDL + reference-data INSERT); `app_runtime` (DML on tenant-scoped + read-only on reference; column-restricted SELECT on PII + sensitive columns).
- App-layer authz at aggregate-method boundary (per ADR 0018):
  - `Flight.*` mutations: FLIGHT_OPS / INSTRUCTOR / ADMIN.
  - `Aircraft.*` mutations: FLIGHT_OPS / ADMIN.
  - `Aircraft.transferOwnership()`: ADMIN only.
  - `Article.reprice()`: ACCOUNTANT / ADMIN.
  - `Location.*` mutations: SYSTEM_ADMIN only (cross-tenant shared); runs in UnscopedTenantContext.
  - `FlightType.*` mutations: ADMIN.
- Reference-data role-check: SELECT-only at `app_runtime`. Mutation reserved to `migrator` (operator-only via Flyway).

### Input validation (schema-level)

Full CHECK list in Design notes per-column; key:
- `flight.flight_date` sanity range; `ldg_date_time >= start_date_time`; `nr_of_ldgs >= 0`; `engine_end_*  >= engine_start_*`; `tow_flight_id <> id`; `tow only for glider`; `runway_*` regex; `coupon_number` regex.
- `aircraft.immatriculation` regex + composite UNIQUE per club; `flarm_id` 6-hex regex; `year_of_manufacture` sane range; `nr_of_seats >= 1`; `mtom` sane; `spot_link` https-only.
- `aircraft_operating_counter.at_date_time <= now + 1 day`; `*_in_seconds >= 0`.
- `aircraft_aircraft_state.valid_to >= valid_from`.
- `flight_type` at-least-one-of (is_for_glider OR is_for_tow OR is_for_motor).
- `location.icao_code` uppercase regex; `latitude/longitude` loose regex.
- All UUID columns reject malformed input at the Postgres type level.

### PII handling

Full catalog in tenant-rules.yaml block above. Categories:
- **PII free-text:** `flight.comment/incident_comment/validation_errors/outbound_route/inbound_route/coupon_number`, `aircraft.comment`, `aircraft_aircraft_state.remarks`, `location.description`.
- **Cross-tenant PII ride-through:** `flight_crew.person_id`, `aircraft.aircraft_owner_person_id`, `aircraft_aircraft_state.noticed_by_person_id`.
- **Commercially-sensitive:** `aircraft.immatriculation`, `flarm_id`, `competition_sign`, `aircraft_serial_number`, `mtom`, `noise_class`, `noise_level`, `spot_link`.
- **Operationally sensitive (private airfield):** `location.location_name`, `location_short_name`, `icao_code`, `latitude`, `longitude`, `airport_frequency`.
- Audit-blob redaction operates on column NAMES (S-027); UUID PKs are NOT PII themselves.

### Audit-log events (forward to S-027)

S-013 contributes audit columns + the event-type catalog. Events:
- `flight.created/updated/state_transitioned/locked/deleted` (state_transitioned + locked dedicated for forensic forensic clarity).
- `flight_crew.assigned/removed` (cross_tenant boolean payload flag).
- `aircraft.created/updated/deleted/ownership_transferred`.
- `aircraft_state.changed` (append-only via aircraft_aircraft_state INSERT).
- `aircraft_operating_counter.recorded` (append-only).
- `article.created/updated/deleted` (price changes high-priority).
- `location.created/updated` (SYSTEM_ADMIN; cross_tenant=true marker).

Audit target.id field carries the prefixed external form (`flt_...`, `acf_...`).

### Cross-tenant leakage

- 7 TENANT_SCOPED tables via `operating_club_id uuid`: `flight`, `flight_crew` (denormalized), `aircraft`, `aircraft_aircraft_state` (denormalized), `aircraft_operating_counter` (denormalized), `flight_type`, `article`.
- Sacred-cow cross-tenant FKs (FK-by-id loads not filtered): `flight_crew.person_id`, `aircraft.aircraft_owner_person_id`, `aircraft_aircraft_state.noticed_by_person_id`. Column comments document.
- `location` shared cross-tenant (NO `operating_club_id` column). SYSTEM_ADMIN-only mutation.
- `flight.operating_club_id` denormalized from `aircraft.operating_club_id`; invariant enforced at S-022; parity test pins.
- S-024 leakage CI parameterized over the 7 TENANT_SCOPED tables + 3 cross-tenant ride-through paths.
- UnscopedTenantContext call sites (S-023): system-admin reports, OGN ingest (S-066), DSAR cross-club search (S-051), public-flow resolution (S-025 — N/A directly for S-013).

### OWASP applicability

- A01 Broken Access Control — applies; aggregate-method `@PreAuthorize` per ADR 0018.
- A02 Cryptographic Failures — limited (free-text PII at rest covered by FDE).
- A03 Injection — low at schema layer; literal seeds + CODEOWNERS gate.
- A04 Insecure Design — applies (single flight table with discriminator inherits legacy polymorphic shape).
- A05 Security Misconfiguration — `@TenantId` resolver fail-closed; role split.
- A08 Software & Data Integrity — Flyway checksum + CODEOWNERS; canonical UUIDs grep-able; denormalization integrity.
- A09 Logging & Monitoring — S-027 owns; this story contributes event-type catalog + PII redaction lists.
- A10 SSRF — `aircraft.spot_link` https-only CHECK + "never fetched server-side" column comment.

## Test plan

### Coverage contract

**Owns:** 16 new tables + indexes + FKs + CHECK constraints + reference-data seeds (canonical UUIDs + legacy_int_id) + 5 `ALTER TABLE club ADD COLUMN` + `tenant-rules.yaml` deltas + SQL column comments + `FlightBaselineIntegrationTest` + `TenantCatalogConsistencyTest` extensions.

**Does NOT own:** JPA entities + `@UuidV7` wiring (S-022); `@TenantId` filter (S-022); aggregate-method invariants (S-022/S-058); aggregate prefix codec (S-022); live leakage CI (S-024); audit-log capture (S-027); flight state-machine transitions (S-058); legacy → new ID remap (S-016); production-scale perf (S-108); DSAR cross-club cascade (S-051).

### Specific test cases

**Extensions to `MigrationFolderConventionsTest`:**
- `flights_and_aircraft_migration_present` — exactly one `V<n>__flights_*.sql`, `n >= 3`.
- `vN_flights_baseline_is_non_empty`.

**Extensions to `FlywayBootstrapIntegrationTest`:**
- `current_version_at_least_S013_after_baseline` — reads highest `V\d+__flights_*.sql` filename at runtime; tolerates V4 vs V5.
- `flyway_history_contains_flights_and_aircraft_row`.

**New `FlightBaselineIntegrationTest` (`@SpringBootTest` + shared `PostgresTestContainerLifecycle`; ~70-80 tests):**

Table presence + type pinning:
- `all_16_tables_present` (`containsExactlyInAnyOrder`).
- `all_pk_columns_are_uuid_not_null` (parameterized over 16 tables).
- `all_fk_columns_are_uuid` (parameterized over FK list).

Flight self-FK + discriminator + tenant:
- `flight_has_tow_flight_self_fk_set_null`.
- `flight_tow_flight_not_self_check` (provoke `tow_flight_id = id` → SQLSTATE 23514).
- `flight_tow_flight_only_for_glider_check` (provoke Motor flight with tow → 23514).
- `flight_aircraft_type_discriminator_check_constraint` pins {1, 2, 4} (provoke value 8 → 23514).
- `flight_operating_club_id_not_null_fk_to_club_restrict`.

flight_crew:
- `flight_crew_composite_partial_unique` (provoke dup → 23505).
- `flight_crew_flight_fk_on_delete_cascade`.
- `flight_crew_person_fk_on_delete_restrict` (pins divergence from "cross-tenant SET NULL" reading).
- `flight_crew_no_audit_columns_other_than_deleted_on`.

flight_type + flight_cost_balance_type:
- `flight_type_is_tenant_scoped_uuid_club_id_not_null`.
- `flight_type_club_code_unique_partial`.
- `flight_cost_balance_type_three_aircraft_flag_columns_not_null_default_false`.
- `flight_cost_balance_type_at_least_one_flag_check`.

Aircraft cluster:
- `aircraft_immatriculation_unique_per_club_partial`.
- `aircraft_aircraft_state_partial_unique_open_interval_per_aircraft`.
- `aircraft_aircraft_state_unique_aircraft_valid_from`.
- `aircraft_operating_counter_time_series_unique_aircraft_at_date_time`.
- `aircraft_operating_counter_at_date_time_not_too_future_check`.
- `aircraft_check_year_of_manufacture_sane`.
- `aircraft_check_mtom_sane`.
- `aircraft_check_flarm_id_regex`.
- `aircraft_check_spot_link_https_only`.

Location cluster (sacred cow):
- `location_has_no_operating_club_id_column` (sacred cow).
- `location_icao_unique_partial`.
- `location_name_lower_functional_index`.
- `inoutbound_point_has_location_fk_on_delete_cascade`.

Club deferred ALTER:
- `club_has_5_deferred_fk_columns` (parameterized; all nullable; FK target + delete rule SET NULL).
- `club_default_glider_with_motor_flight_type_id_present` (pin NOT-in-legacy deviation).

Flight CHECK constraints:
- `flight_check_ldg_on_or_after_start`.
- `flight_check_date_within_reasonable_range`.
- `flight_check_nr_of_ldgs_nonnegative`.
- `flight_check_engine_counters_monotonic`.
- `flight_check_runway_regex`.
- `flight_check_coupon_number_regex`.

Index assertions (via `pg_indexes` + `pg_get_indexdef`):
- `flight_hot_path_index_club_date_desc`.
- `flight_hot_path_index_club_state_with_include`.
- `flight_tow_flight_partial_index`.
- `flight_crew_unique_composite_partial`.
- `flight_crew_person_type_with_include`.
- `aircraft_operating_counter_aircraft_recorded_with_include`.
- `aircraft_state_open_interval_partial`.
- `flight_type_club_code_unique_partial`.
- `article_club_number_unique_partial`.
- `location_icao_unique_partial`.
- `location_name_lower_functional`.
- `every_fk_column_has_supporting_index` (parameterized over FK columns).

Reference-data seeds (against `reference-seeds-canonical-uuids.json`):
- `aircraft_type_seeded_8_canonical_bitfield_values` (Unknown/Glider/GliderWithMotor/MotorGlider/MotorAircraft/MultiEngine/Jet/Helicopter; `legacy_int_id` carries {0,1,2,4,8,16,32,64}).
- `aircraft_type_legacy_codes_are_bitfield_powers_of_two_or_zero` (explicit invariant).
- `aircraft_state_seeded_7_canonical_values` (with `is_aircraft_flyable` flag asserted per row).
- `location_type_seeded_17_canonical_values`.
- `flight_crew_type_seeded_7_canonical_values`.
- `flight_process_state_seeded_8_canonical_values` (with `legacy_int_id` carrying legacy int codes).
- `flight_air_state_seeded_7_canonical_values`.
- `flight_cost_balance_type_seeded` (with 3 aircraft_type flag columns).

Aggregate-root column comments:
- `aggregate_root_column_comments_reference_adr_0019` (parameterized over `flight, acf, loc, fty, art`).
- `non_aggregate_root_columns_do_not_carry_prefix_comments` (parameterized over `flight_crew.id, aircraft_aircraft_state.id, aircraft_operating_counter.id, inoutbound_point.id`).

Reference-table immutability:
- `reference_tables_have_no_audit_columns` (parameterized over 7 reference tables).
- `tenant_scoped_tables_have_audit_columns` (parameterized over 7 tenant-scoped tables).

**Extensions to `TenantCatalogConsistencyTest`:**
- `every_s013_tenant_scoped_table_has_operating_club_id_uuid_not_null` (parameterized).
- `every_s013_reference_table_has_no_operating_club_id` (parameterized).
- `location_has_no_club_id` (sacred cow).
- `flight_type_reclassified_to_tenant_scoped`.
- `aircraft_tenant_column_renamed_to_operating_club_id` (legacy `OwnerClubId` documented).
- `flight_tenant_scope_precondition_met` (yaml precondition honored).
- `tenant_rules_yaml_pii_columns_present_for_flight_aircraft_location`.

### Parity strategy

N/A — schema reshape per ADR 0008 + 0018 + 0019. Enum values pinned via seed assertions + `legacy_int_id` columns citing legacy enum source files. S-016 cutover uses these for the legacy-int → new-UUID remap.

### Test data + fixtures

- Shared `PostgresTestContainerLifecycle` (single per-JVM container); identical `@DynamicPropertySource` shape across all 4 test classes so Spring context cache hits.
- `PostgresIntegrationTest` base (S-015 if merged); else share `@DynamicPropertySource` block.
- CHECK-constraint provocation via raw JDBC; SQLSTATE-based assertions (23514 / 23505 / 23503) — locale-independent.
- FK-cascade provocation via minimal-graph fixtures (`FlightFixture.insertMinimalFlight()`).
- Canonical-UUID seed-pin against `reference-seeds-canonical-uuids.json` (extends S-012's file).
- Shared FK-introspection helper (extends from S-013 testsupport).

### Coverage gaps (deferred)

- JPA entity correctness + `@UuidV7` wiring → S-022.
- `@TenantId` filter behavior → S-022.
- Aggregate-method invariant enforcement → S-022/S-058.
- Aggregate prefix codec → S-022.
- Live cross-tenant leakage CI → S-024.
- Audit-log capture → S-027.
- Flight state-machine transitions → S-058.
- Aircraft availability/charter rules → backlog.
- Legacy → new ID remap → S-016 (uses `legacy_int_id`).
- DSAR cross-club cascade → S-051.
- Production-scale perf → S-108.
- `aircraft_operating_counter` cross-row monotonic invariant → S-022 service-layer guard.

### Risks

- V<n+1> collision with S-018 (shedlock). Mitigation: read directory listing at implement; tests assert `>= N` computed at runtime.
- Canonical-UUID immutability post-merge (Flyway checksum). Mitigation: (a) committed generator script reviewable; (b) JSON pin map fails CI pre-merge; (c) PR diffs JSON vs SQL.
- Test boot-time growth. Mitigation: identical `@DynamicPropertySource` shape → single-boot context cache.
- Bit-field integer enum value preservation (AircraftType). Mitigation: explicit `aircraft_type_legacy_codes_are_bitfield_powers_of_two_or_zero` invariant test.
- `pg_description` comment drift. Mitigation: tolerant regex + prefix-token containment.
- Prefix-token uncertainty (`loc_`/`fty_`/`art_` not in the ADR 0019 prefix-registry excerpt I reviewed — implementer confirms at write time).
- `flight_default_*_flight_type_id` on `club` ALTER ordering. Mitigation: explicit ALTER TABLE block after `flight_type` is created; test runs against post-migration shape.

## Performance plan

### Hot paths

- `GET /api/v1/clubs/{clb}/flights?from=&to=` (per-club this-month list, 50 rows): HOTTEST read. 5-50 rps per club × 50-100 clubs.
- `GET /api/v1/persons/{psn}/flights` (per-pilot history via flight_crew JOIN flight).
- `GET /api/v1/aircraft/{acf}/flights` (per-aircraft history).
- `GET /api/v1/flights/{flt}` (tow-flight self-join hydration).
- State-machine filter for ops dashboard.
- `GET /api/v1/aircraft/{acf}/operating-counter/latest` (every flight-edit page).
- Flight write (POST + 3 crew INSERTs): 1-10 writes/sec aggregate peak; UUID v7 app-gen saves ~1 RTT vs IDENTITY-returning.

### Required indexes

Full per-table grid in Design notes. Load-bearing:
- `ix_flight_club_date (operating_club_id, flight_date DESC)` — primary list.
- `ix_flight_club_state (operating_club_id, process_state_id) INCLUDE (flight_date, id)` — Index Only Scan for state-machine.
- `ix_flight_tow_flight (tow_flight_id) WHERE NOT NULL` — partial saves ~50%.
- `ix_flight_aircraft_date (aircraft_id, flight_date DESC)`.
- `ix_flight_crew_person_type (person_id, flight_crew_type_id) INCLUDE (flight_id)` — Index Only Scan for "all flights I was crew on".
- `ix_aoc_aircraft_recorded (aircraft_id, at_date_time DESC) INCLUDE (counter_values)` — latest-counter Index Only Scan.

### N+1 risks (forward to S-022)

- `flight → flight_crew → person` on list page (highest impact). Mitigation: `@EntityGraph` with two-phase fetch.
- `flight → aircraft → aircraft_type`: `@BatchSize(50)` + reference L2 cache.
- `flight → start_location / ldg_location`: `@BatchSize(50)` + Location L2.
- `flight → flight_type → flight_cost_balance_type`: reference L2 (24h).
- `flight → tow_flight` (self-FK): **NEVER eager-fetch**. `@JsonIdentityInfo` mandatory.
- `aircraft → aircraft_operating_counter`: NO `@OneToMany`. Discrete query.
- `aircraft → aircraft_aircraft_state`: same.

### Cartesian / explosion risks

- `flight + flight_crew + person` single fetch-join: 50 × 3-5 × 1 = 250 rows; prefer two-phase batch fetch.
- 6+ EAGER associations on flight: force LAZY; opt-in via `@EntityGraph(LOAD)` per query.
- `MultipleBagFetchException` if `flight + flight_crew + deliveries`: `@FetchMode.SUBSELECT` or split query.

### Caching

| Entity | Cache | TTL | Reason |
|---|---|---|---|
| 7 reference tables (`aircraft_type`, `aircraft_state`, `location_type`, `flight_crew_type`, `flight_process_state`, `flight_air_state`, `flight_cost_balance_type`) | L2 Caffeine | 24h | Read-only, invalidate on migration only; < 1 MB total |
| `location` | L2 per-club | 1h | Low write rate |
| `aircraft` | L2 per-club | 15min | ~50 rows × 100 clubs |
| `flight_type`, `article` | L2 per-club | 15min | Slow churn |
| `flight` | **NEVER** | — | High write velocity + freshness |
| `flight_crew` | **NEVER** | — | PII ride-through + tied to flight |
| `aircraft_operating_counter` | **NEVER** | — | Latest-row Index Only sub-ms |
| `aircraft_aircraft_state` | **NEVER** | — | Same |

### Latency budget (forward S-108)

- Per-club Flight list (50 rows, this-month): p95 < 200ms / < 50ms DB at 100K flights/year/club.
- Per-pilot history (10 rows): p95 < 100ms / < 30ms DB.
- Per-aircraft history (50 rows): p95 < 150ms / < 30ms DB.
- Tow-flight self-join (PK): p95 < 10ms / < 5ms DB.
- State-machine filter: p95 < 100ms / < 30ms DB.
- Aircraft latest-counter: p95 < 20ms / < 5ms DB.
- Flight write: p95 < 100ms / < 30ms DB.

### Memory

- Index footprint at 5-year scale (~5M flights, ~25M flight_crew): ~+900 MB delta on flight + ~+900 MB on flight_crew + trivial elsewhere. **Aggregate S-013 delta ~2 GB.** Combined with S-012's ~300 MB and forward S-014 + S-027: inside ADR 0019's 3-5 GB envelope.
- Postgres `shared_buffers`: **4 GB recommended** (was 1 GB after S-012). Document for S-019.
- UUID v7 generator cost: ~30ns/ID at AlpenFlight write volume = invisible.

### Performance test plan

EXPLAIN canaries (7 queries) at 10K-row fixture; force `enable_seqscan = off` for assertion only:
1. Per-club this-month list → `ix_flight_club_date`.
2. State-machine filter → `ix_flight_club_state` Index Only Scan.
3. Tow-flight self-join → `ix_flight_tow_flight` partial.
4. Per-pilot via flight_crew → `ix_flight_crew_person_type` Index Only Scan.
5. Per-aircraft history → `ix_flight_aircraft_date`.
6. Latest aircraft counter → `ix_aoc_aircraft_recorded` Index Only Scan.
7. Composite club + flight-type → `ix_flight_flight_type`.

Production-scale: k6 hitting flight-list at 100K-rows/club fixture, p95 latency capture, memory-peak via JFR — deferred to S-108.

### Configuration choices

- `uuid NOT NULL PRIMARY KEY` per ADR 0019.
- `flight_date DATE`; `*_date_time TIMESTAMPTZ`.
- `nr_of_ldgs / nr_of_passengers / start_position SMALLINT`.
- `flight_duration_seconds INTEGER`.
- `engine_*_operating_counter_in_seconds BIGINT`; `aircraft_operating_counter.*_in_seconds BIGINT`.
- `flight_aircraft_type_id SMALLINT` + CHECK enum (NOT lookup; sacred cow + 70 MB save).

### Partitioning (forward S-108)

At 10M+ flights, declarative range partitioning on `flight_date` (per year). Hash-partitioning by `operating_club_id` rejected (breaks cross-club admin reports). Index naming above is partition-friendly. Trigger threshold: single club > 1M flights OR aggregate > 20M.

## Open design questions

1. **`flight.tow_flight_id` 1:1 vs 1:N cardinality.** Legacy `Flight.TowedFlights = HashSet<Flight>` allows 1:N (touring-tow pattern). Recommend 1:N at row level (no partial-UNIQUE on tow_flight_id); confirm with operator.
2. **`club.default_glider_with_motor_flight_type_id`** — not in legacy `Club.cs:77-81`. Recommend include (forward-looking); operator can drop for strict parity.
3. **~~`flight.operating_club_id` denormalization mechanism.~~ RESOLVED 2026-05-16:** Aircraft is now cross-tenant; `flight.operating_club_id` is set per-flight by the operator (NOT denormalized). Default at API layer is `user.club_id`; S-022 service layer enforces "may this club use this aircraft?" before accepting the Flight insert.
4. **Soft-delete columns preservation** (`deleted_on`, `deleted_by_user_id`) on every TENANT_SCOPED table. Recommend preserve here (legacy parity); S-027 audit story reshapes if needed.
5. **`location.latitude/longitude` PostGIS reshape** — file as a backlog story; preserve `VARCHAR(10)` for S-013 cutover-parity.
6. **`aircraft.year_of_manufacture` type.** Legacy `datetime2` is structurally wrong (year value, not moment). Recommend reshape to `DATE` here; S-022 may further refine.
7. **~~`aircraft.aircraft_owner_club_id` rename to `operating_club_id`.~~ RESOLVED 2026-05-16:** Aircraft is cross-tenant; column is `owner_club_id uuid NULL` (renamed from legacy `OwnerClubId`; nullable). NOT `operating_club_id` — that name applies only to per-Flight scope.
8. **`flight_crew.operating_club_id` denormalization** — having it enables `(operating_club_id, person_id)` tenant-scoped indexing without a join. Cost: trigger or `@PrePersist`. Recommend denormalize for hot-path read win.
9. **Reference-table `legacy_int_id` column retention.** Recommend keep forever (forensic traceability + future legacy-data inspection).
10. **`flight_aircraft_type_id` SMALLINT + CHECK confirmed sacred cow** — pin formally with operator.
11. **`UuidCreator.getTimeOrderedEpoch()` vs `getTimeOrderedEpochPlus1()`** for hot-path inserts. Recommend default v7; revisit if S-108 baseline shows generator-cost dominance.
12. **Reference-data canonical UUIDs script** — extends S-012's generator with S-013's 7 reference tables; reuse the committed Java script.

<!-- modernize-refine: end -->
