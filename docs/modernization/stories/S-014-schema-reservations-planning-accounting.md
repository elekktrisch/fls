---
id: S-014
title: V1__baseline part 3 — reservations / planning / accounting
epic: E-02
status: done
started_at: 2026-05-16
done_at: 2026-05-16
github_issue: 42
github_pr: 43
depends_on: [S-013]
acceptance:
  - Tables defined: `aircraft_reservation`, `aircraft_reservation_type`, `planning_day`, `planning_day_assignment`, `planning_day_assignment_type`, `accounting_rule_filter`, `accounting_rule_filter_type`, `accounting_unit_type`, `delivery`, `delivery_item`, `delivery_creation_test`, `delivery_creation_test_item` (12 tables) plus `club_delivery_number_counter` operational table (13th).
  - **Every PK is `UUID NOT NULL PRIMARY KEY`** (Postgres native `uuid`) per ADR 0019; every FK column is `uuid`. Tenant column `operating_club_id uuid NOT NULL REFERENCES club(id) ON DELETE RESTRICT` on every TENANT_SCOPED table. No `DEFAULT gen_random_uuid()` — application generates at S-022 via Hibernate 7 + `f4b6a3:uuid-creator` `UuidCreator.getTimeOrderedEpoch()`.
  - **Aggregate roots** per ADR 0018 (5): `AircraftReservation` (`arv`), `PlanningDay` (`pln`), `AccountingRuleFilter` (`arf`), `Delivery` (`dlv`), `DeliveryCreationTest` (`dct`). Each carries SQL `COMMENT ON COLUMN` on `id` referencing ADR 0019 + the prefix.
  - **Internal entities** (no prefix; raw UUID at every layer): `planning_day_assignment` (under PlanningDay), `delivery_item` (under Delivery), `delivery_creation_test_item` (under DeliveryCreationTest).
  - **Reference data**: `aircraft_reservation_type` + `planning_day_assignment_type` reclassified from S-011's `reference` to **TENANT_SCOPED per-club** (legacy carries `ClubId NOT NULL`). `accounting_rule_filter_type` + `accounting_unit_type` stay **SYSTEM_GLOBAL reference** (no legacy `ClubId`); seeded with fixed canonical UUID v7 literals + `legacy_int_id SMALLINT UNIQUE` for S-016 cutover.
  - **`delivery.process_state_id SMALLINT NOT NULL CHECK IN (10, 20, 30, 99)`** for Prepared/Booked/Error/Cancelled. **Reshape from legacy** (legacy stores state on `flight.process_state_id` + `delivery.is_further_processed BOOLEAN`); migration header documents S-016 cutover mapping. Terminal-on-Booked enforced at S-064 service layer.
  - **`accounting_rule_filter.filter_config jsonb NOT NULL DEFAULT '{}'::jsonb`** with `filter_type_id uuid NOT NULL → accounting_rule_filter_type.id` discriminator. ~30 legacy predicate columns collapse into jsonb. Rule action columns (`stop_rule_engine_when_applied`, `is_charged_to_club_internal`, `article_target`, `recipient_target`, `sort_indicator`) remain structural. Jackson default-typing DISABLED globally (jsonb injection mitigation; column comment forbids polymorphic deserialization).
  - **`delivery_creation_test` regression harness**: `flight_id uuid NOT NULL → flight(id) CASCADE`; `expected_delivery jsonb NOT NULL` (full `DeliveryDetails` graph snapshot); `expected_matched_filter_ids BIGINT[] NOT NULL DEFAULT '{}'` (array NOT FK-enforced — deleted filter is legitimate regression signal); 9 `ignore_*` BOOLEAN comparison knobs; 5 `last_test_*` result columns; partial UNIQUE `(operating_club_id, flight_id) WHERE deleted_on IS NULL`.
  - **`delivery.recipient_*` frozen-snapshot columns** (9): `recipient_name VARCHAR(250)`, `recipient_firstname VARCHAR(100)`, `recipient_lastname VARCHAR(100)`, `recipient_address_line1 VARCHAR(200)`, `recipient_address_line2 VARCHAR(200)`, `recipient_zip_code VARCHAR(10)`, `recipient_city VARCHAR(100)`, `recipient_country_name VARCHAR(100)` (frozen text, NOT FK to country), `recipient_person_club_member_number VARCHAR(20)`. Stored directly on row — NOT FK-resolved at read time (Swiss OR Art. 957a invoice integrity).
  - **`delivery_item.total_amount NUMERIC(14,4) GENERATED ALWAYS AS (quantity * unit_price * (100 - discount_in_percent) / 100.0) STORED`** — Postgres 17 stored generated column. `quantity NUMERIC(12,4) CHECK >= 0`; `unit_price NUMERIC(12,4) CHECK >= 0`; `discount_in_percent INTEGER CHECK BETWEEN 0 AND 100`. `article_number VARCHAR(50) NOT NULL` (frozen snapshot from `article.article_number`); `article_id uuid NOT NULL → article(id) RESTRICT` (forward addition — legacy `DeliveryItem` has no FK; invoice-integrity preserved by snapshot column).
  - **`aircraft_reservation` time range**: two `TIMESTAMPTZ NOT NULL` columns + `CHECK (reservation_end > reservation_start)` + generated `reservation_range tstzrange GENERATED ALWAYS AS (tstzrange(reservation_start, reservation_end, '[)')) STORED` for forward GiST overlap indexes. (Refinement design notes wrote `tsrange`, but `TIMESTAMPTZ::timestamp` cast isn't IMMUTABLE; Postgres rejects in generated expressions. `tstzrange` takes TIMESTAMPTZ directly and is immutable — the right primitive for our TIMESTAMPTZ-everywhere schema.) Overlap-exclusion DEFERRED to S-064 (multiple legitimate-overlap business rules: maintenance, multi-pilot patterns). Requires `CREATE EXTENSION IF NOT EXISTS btree_gist`.
  - **`aircraft_reservation.aircraft_id uuid NOT NULL → aircraft(id) RESTRICT`** — **cross-tenant FK per 2026-05-16 Aircraft-cross-tenant amendment**. FK loads NOT @TenantId-filtered; service layer (S-026/S-064) enforces "may this club reserve this aircraft?" via owner / charter / public-rental check. Audit event carries `cross_tenant: true` marker when `aircraft_reservation.operating_club_id != aircraft.owner_club_id`.
  - **Cross-tenant ride-through Person FKs** (sacred-cow): `delivery.recipient_person_id → person(id) SET NULL`; `aircraft_reservation.pilot_person_id → person(id) RESTRICT`; `aircraft_reservation.second_crew_person_id → person(id) SET NULL`; `planning_day_assignment.assigned_person_id → person(id) RESTRICT`.
  - **Per-club delivery numbering** (Swiss OR Art. 957a gap-free invariant): `delivery.delivery_number INTEGER NULL` (reshape from legacy VARCHAR) + `UNIQUE (operating_club_id, delivery_number) WHERE delivery_number IS NOT NULL AND deleted_on IS NULL` + `CHECK (process_state_id <> 20 OR delivery_number IS NOT NULL)` (Booked deliveries must carry a number). New `club_delivery_number_counter (operating_club_id uuid PK → club(id) CASCADE, next_number INTEGER NOT NULL DEFAULT 1)` for service-layer monotonic allocator at S-064. Soft-delete only on numbered deliveries; never hard DELETE.
  - **`tenant-rules.yaml` updates in scope**: 12 entries (5 aggregate roots + 4 internal entities denormalized `operating_club_id` + reclassifications + counter); reclassify `AircraftReservationTypes` + `PlanningDayAssignmentTypes` to `tenant-scoped`; keep `AccountingRuleFilterTypes` + `AccountingUnitTypes` as `reference`; **`AircraftReservations.ride_through_targets: [Persons, Aircrafts]`** (Aircrafts added per 2026-05-16 amendment); PII catalog (`delivery.recipient_*` 9 columns + free-text fields + jsonb whole-column on `accounting_rule_filter.filter_config` + `delivery_creation_test.expected_delivery/last_test_created_delivery`).
  - Reference-data seeds: `accounting_rule_filter_type` (8 canonical codes per legacy `database/FLSTest/3 insert/3 Insert Static Data.sql`: RECIPIENT, NO_LANDING_TAX, FLIGHT_TIME, INSTRUCTOR_FEE, ADDITIONAL_FUEL_FEE, LANDING_TAX, VSF_FEE, ENGINE_TIME) + `accounting_unit_type` (4 canonical codes per same file: MINUTES, SECONDS, LANDINGS, START_OR_FLIGHT). Fixed canonical UUIDs extending S-012/S-013's `reference-seeds-canonical-uuids.json`. Rule-strategy classes in `flsserver/src/FLS.Server.Service/Accounting/Rules/*.cs` (DoNotInvoiceFlightRule, StartTaxRule, etc.) are CODE strategies, NOT seeded filter types — do not seed them.
  - Indexes per design notes (full grid): `(operating_club_id, process_state_id, delivery_date DESC)` on delivery; GiST `(aircraft_id, reservation_range) WHERE deleted_on IS NULL` on aircraft_reservation; `(operating_club_id, is_active, sort_indicator) WHERE deleted_on IS NULL` on accounting_rule_filter (THE hot index); GIN `(filter_config jsonb_path_ops)` (admin search only); per-FK supporting indexes.
  - Flyway migration succeeds against fresh Postgres in Testcontainers; new `ReservationsBaselineIntegrationTest` (~55 tests) asserts table presence + UUID type pins + state-machine CHECK + recipient-snapshot columns + generated `total_amount` + GiST index on aircraft_reservation + cross-tenant aircraft_id column comment + reference-data canonical-UUID pins; `TenantCatalogConsistencyTest` extends with reclassifications + aircraft-cross-tenant ride-through assertion.
estimate: M
adr_refs: [0001, 0002, 0003, 0007, 0008, 0018, 0019]
parity_test: none
refined: true
refined_at: 2026-05-16
refined_speculative: false
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context
Final chunk of V1__baseline. Sets the table for E-08 + E-09.

## Acceptance criteria
See frontmatter.

## Tasks
Tasks superseded by acceptance criteria — see frontmatter (all ACs landed; tests assert each one).

## Notes
Schema for `AccountingRuleFilter` is non-trivial — the legacy schema mixes per-rule-type columns into one table. The reshape decision (keep one wide table vs. per-type tables vs. JSON `filter_config`) is part of this story; the recommendation is **one base table + jsonb filter_config + filter_type_id discriminator**, mirroring how the rules engine instantiates `Rule` objects from a base `AccountingRuleFilter` row at runtime.

<!-- modernize-refine: start -->

## Design notes

### Migration shape

Ships as **single `V4__reservations_planning_accounting.sql`** (~600-700 lines). Actual migration sequence on `main` is V1 baseline / V2 identity (S-012) / V3 flights-aircraft-locations (S-013), so S-014 lands at V4. Tests assert `>= 4` not `== 4` to tolerate ordering shifts.

FK ordering across 3 clusters + counter:
1. **Reservations:** `aircraft_reservation_type` → `aircraft_reservation`.
2. **Planning:** `planning_day_assignment_type` → `planning_day` → `planning_day_assignment`.
3. **Accounting:** `accounting_rule_filter_type` + `accounting_unit_type` → `accounting_rule_filter` → `delivery` → `delivery_item` → `delivery_creation_test` → `delivery_creation_test_item`.
4. **Operational:** `club_delivery_number_counter`.

Migration begins with `CREATE EXTENSION IF NOT EXISTS btree_gist;` (Postgres 17 contrib; required for the composite GiST index on `(aircraft_id, reservation_range)`).

Header documents: UUID v7 convention; Aircraft-cross-tenant 2026-05-16 amendment; state-machine reshape from `flight.process_state_id + delivery.is_further_processed` to first-class `delivery.process_state_id`; `delivery_number INTEGER` reshape from legacy VARCHAR; `filter_config` 30-column→jsonb reshape; `expected_matched_filter_ids BIGINT[]` NOT FK-enforced; `aircraft_reservation_type` + `planning_day_assignment_type` reclassified TENANT_SCOPED; `delivery_creation_test_item` forward-looking table (NOT in legacy); `delivery_item.unit_price` + generated `total_amount` forward-looking (NOT in legacy).

### ID strategy (per ADR 0019)

Every PK + FK column is `uuid`. No `DEFAULT gen_random_uuid()`. App generates at S-022 via Hibernate 7 + uuid-creator. Audit columns: `created_on TIMESTAMPTZ NOT NULL DEFAULT now()`, `created_by_user_id uuid` (no FK), `modified_on`, `modified_by_user_id`, plus `deleted_on TIMESTAMPTZ NULL` + `deleted_by_user_id uuid NULL` for soft-delete on every TENANT_SCOPED mutable table.

### Aggregate composition (per ADR 0018)

| Layer | Tables | Tenant scope | Notes |
|---|---|---|---|
| **Aggregate roots** (5) | `aircraft_reservation` (`arv`), `planning_day` (`pln`), `accounting_rule_filter` (`arf`), `delivery` (`dlv`), `delivery_creation_test` (`dct`) | All TENANT_SCOPED via `operating_club_id` | Prefix at JSON/URL/log boundary; SQL `COMMENT ON COLUMN id` references ADR 0019. |
| **Internal entities under `Delivery`** | `delivery_item` | TENANT_SCOPED (denormalized) | CASCADE; denormalized `operating_club_id`. |
| **Internal entities under `PlanningDay`** | `planning_day_assignment` | TENANT_SCOPED (denormalized) | CASCADE; cross-tenant Person FK preserved. |
| **Internal entities under `DeliveryCreationTest`** | `delivery_creation_test_item` | TENANT_SCOPED (denormalized) | CASCADE; forward-looking (NOT in legacy). |
| **TENANT_SCOPED per-club reference** (reclassified) | `aircraft_reservation_type`, `planning_day_assignment_type` | TENANT_SCOPED via `operating_club_id` | Club-aggregate-internal lookups; legacy `ClubId NOT NULL`. |
| **SYSTEM_GLOBAL reference** | `accounting_rule_filter_type`, `accounting_unit_type` | None | Seeded canonical UUIDs + `legacy_int_id SMALLINT UNIQUE`. |
| **Operational** | `club_delivery_number_counter` | TENANT_SCOPED (PK is `operating_club_id`) | One row per Club; S-064 owns the allocator. |

Cross-aggregate + cross-tenant FKs:
- `delivery.flight_id → flight(id) RESTRICT` (same-tenant invariant at S-022).
- `delivery_item.article_id → article(id) RESTRICT` (same-tenant).
- **`aircraft_reservation.aircraft_id → aircraft(id) RESTRICT`** — **cross-tenant FK per 2026-05-16 amendment** (Aircraft has no `@TenantId`; service-layer authz at S-026/S-064).
- `aircraft_reservation.location_id → location(id) RESTRICT` (cross-tenant).
- `planning_day.location_id → location(id) RESTRICT` (cross-tenant).
- `accounting_rule_filter.filter_type_id → accounting_rule_filter_type(id) RESTRICT` (system-global discriminator).

Cross-tenant ride-through Person FKs (sacred cow per ADR 0008):
- `delivery.recipient_person_id → person(id) SET NULL`.
- `aircraft_reservation.pilot_person_id → person(id) RESTRICT`.
- `aircraft_reservation.second_crew_person_id → person(id) SET NULL`.
- `planning_day_assignment.assigned_person_id → person(id) RESTRICT`.

### Per-table column inventory (load-bearing columns)

**`aircraft_reservation`** (`AircraftReservation.cs:21-35`):
- `id uuid PK` (aggregate root, `arv` prefix); `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `aircraft_id uuid NOT NULL → aircraft(id) RESTRICT` — **cross-tenant FK** per amendment
- `reservation_start TIMESTAMPTZ NOT NULL`, `reservation_end TIMESTAMPTZ NOT NULL` + `CHECK (reservation_end > reservation_start)` + `CHECK (reservation_end <= reservation_start + INTERVAL '30 days')` (sanity cap)
- `reservation_range tsrange GENERATED ALWAYS AS (tsrange(reservation_start, reservation_end, '[)')) STORED`
- `is_all_day BOOLEAN NOT NULL DEFAULT false`
- `pilot_person_id uuid NOT NULL → person(id) RESTRICT` (cross-tenant ride-through)
- `second_crew_person_id uuid NULL → person(id) SET NULL` (cross-tenant)
- `location_id uuid NOT NULL → location(id) RESTRICT` (cross-tenant; Location shared)
- `reservation_type_id uuid NULL → aircraft_reservation_type(id) RESTRICT`
- `flight_type_id uuid NULL → flight_type(id) RESTRICT`
- `info TEXT` (PII catalog; legacy `Remarks`)
- audit + soft-delete

**`planning_day`** + **`planning_day_assignment`** + **`planning_day_assignment_type`** (`PlanningDay.cs`, `PlanningDayAssignment.cs`, `PlanningDayAssignmentType.cs`):
- `planning_day`: id uuid PK (`pln` prefix), `operating_club_id uuid NOT NULL`, `planning_date DATE NOT NULL` + sane range CHECK, `location_id uuid NOT NULL → location(id) RESTRICT`, `info TEXT`, partial UNIQUE `(operating_club_id, planning_date, location_id) WHERE deleted_on IS NULL`.
- `planning_day_assignment`: id uuid PK, denormalized `operating_club_id uuid NOT NULL`, `planning_day_id uuid NOT NULL → planning_day(id) CASCADE`, `assigned_person_id uuid NOT NULL → person(id) RESTRICT` (cross-tenant), `assignment_type_id uuid NOT NULL → planning_day_assignment_type(id) RESTRICT`, `info TEXT`, partial UNIQUE `(planning_day_id, assigned_person_id, assignment_type_id) WHERE deleted_on IS NULL`.
- `planning_day_assignment_type` (TENANT_SCOPED reclassified): id uuid PK, `operating_club_id uuid NOT NULL → club(id) RESTRICT`, `assignment_type_name VARCHAR(100) NOT NULL`, `required_nr_of_assignments SMALLINT NOT NULL DEFAULT 1` + `CHECK (>= 0)`.
- `aircraft_reservation_type` (TENANT_SCOPED reclassified): id uuid PK, `operating_club_id uuid NOT NULL → club(id) RESTRICT`, `reservation_type_name VARCHAR(100) NOT NULL`, `is_instructor_required BOOLEAN`, `is_maintenance BOOLEAN`, `is_active BOOLEAN`, `remarks TEXT`, audit + soft-delete.

**`accounting_rule_filter`** + types (`AccountingRuleFilter.cs:22-89`, `AccountingRuleFilterType.cs:18-32`, `AccountingUnitType.cs:18-32`):
- `accounting_rule_filter`: id uuid PK (`arf` prefix), `operating_club_id uuid NOT NULL`, `filter_type_id uuid NOT NULL → accounting_rule_filter_type(id) RESTRICT` (discriminator), `accounting_unit_type_id uuid NULL`, `rule_filter_name VARCHAR(250) NOT NULL`, `description TEXT`, `is_active BOOLEAN NOT NULL DEFAULT true`, `sort_indicator INTEGER NOT NULL DEFAULT 0` + `CHECK (>= 0)`, `stop_rule_engine_when_applied BOOLEAN`, `is_charged_to_club_internal BOOLEAN`, `article_target VARCHAR(50)`, `recipient_target VARCHAR(50)`, **`filter_config jsonb NOT NULL DEFAULT '{}'::jsonb`** (30+ legacy predicate columns collapse here), audit + soft-delete. UNIQUE `(operating_club_id, sort_indicator) WHERE deleted_on IS NULL` (deterministic engine output; Open Q12).
- `accounting_rule_filter_type` (SYSTEM_GLOBAL; no ClubId): id uuid PK, `code VARCHAR(50) NOT NULL UNIQUE`, `name VARCHAR(100) NOT NULL`, `description TEXT`, `legacy_int_id SMALLINT UNIQUE`. Seed 8 canonical codes from legacy `database/FLSTest/3 insert/3 Insert Static Data.sql`: RECIPIENT (10), NO_LANDING_TAX (20), FLIGHT_TIME (30), INSTRUCTOR_FEE (40), ADDITIONAL_FUEL_FEE (50), LANDING_TAX (60), VSF_FEE (70), ENGINE_TIME (80). (Refinement originally said 10 conflating rule-strategy code classes; legacy DB only seeds the 8 filter types — strategies like DoNotInvoiceFlightRule / StartTaxRule are code, not seeded rows.)
- `accounting_unit_type` (SYSTEM_GLOBAL): id uuid PK, `code VARCHAR(50) NOT NULL UNIQUE`, `name VARCHAR(100) NOT NULL`, `legacy_int_id SMALLINT UNIQUE`. Seed ≥3 canonical codes per legacy.

`filter_config` jsonb bag holds the legacy predicates: `is_rule_for_glider/towing/motor_flights`, `use_*_except_listed` flags + matched-list arrays (`matched_aircraft_immatriculations`, `matched_start_types`, `matched_flight_type_codes`, `matched_start_locations`, `matched_ldg_locations`, `matched_club_member_numbers`, `matched_flight_crew_types`, `matched_aircrafts_homebase`, `matched_member_states`, `matched_person_categories`), min/max `flight_time_in_seconds`, min/max `engine_time_in_seconds`, `include_threshold_text`, `threshold_text`, `include_flight_type_name`, `no_landing_tax_for_glider/towing/aircraft`, `extend_matching_flight_type_codes_to_glider_and_tow_flight`. Per-discriminator typed-shape validation at S-064.

**`delivery`** (`Delivery.cs:13-94`):
- `id uuid PK` (`dlv` prefix), `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `process_state_id SMALLINT NOT NULL DEFAULT 10` + `CHECK (process_state_id IN (10, 20, 30, 99))` — **new column; reshape from legacy `flight.process_state_id + delivery.is_further_processed`**. Values: 10=Prepared / 20=Booked / 30=Error / 99=Cancelled. Terminal-on-Booked enforced at S-064.
- `flight_id uuid NULL → flight(id) RESTRICT` (nullable for manual deliveries; service-layer asserts same-tenant)
- `recipient_person_id uuid NULL → person(id) SET NULL` (cross-tenant ride-through)
- **9 frozen recipient snapshot columns** (Swiss OR Art. 957a invoice integrity; NEVER re-resolve from recipient_person_id):
  - `recipient_name VARCHAR(250)`, `recipient_firstname VARCHAR(100)`, `recipient_lastname VARCHAR(100)`
  - `recipient_address_line1 VARCHAR(200)`, `recipient_address_line2 VARCHAR(200)`
  - `recipient_zip_code VARCHAR(10)`, `recipient_city VARCHAR(100)`
  - `recipient_country_name VARCHAR(100)` (frozen text; NOT FK)
  - `recipient_person_club_member_number VARCHAR(20)`
- `delivery_information VARCHAR(250)` (PII catalog), `additional_information VARCHAR(250)`
- `delivery_number INTEGER NULL` — reshape from legacy VARCHAR
- `delivered_on TIMESTAMPTZ NULL` + `CHECK (delivered_on IS NULL OR delivered_on <= now() + INTERVAL '1 day')`
- `batch_id BIGINT NOT NULL DEFAULT 0` — operational sequence (NOT aggregate UUID per ADR 0019 escape hatch; SQL column comment documents); UNIQUE `(operating_club_id, batch_id) WHERE batch_id IS NOT NULL`
- audit + soft-delete
- Partial UNIQUE `(operating_club_id, delivery_number) WHERE delivery_number IS NOT NULL AND deleted_on IS NULL`
- CHECK `(process_state_id <> 20 OR delivery_number IS NOT NULL)` — Booked requires number
- CHECK `(process_state_id <> 20 OR (recipient_lastname IS NOT NULL AND recipient_firstname IS NOT NULL))` — Booked requires recipient snapshot

**`delivery_item`** (`DeliveryItem.cs:13-66`):
- `id uuid PK` (internal; no prefix), `operating_club_id uuid NOT NULL` (denormalized)
- `delivery_id uuid NOT NULL → delivery(id) CASCADE`
- `position INTEGER NOT NULL` + `CHECK (position >= 1)` + partial UNIQUE `(delivery_id, position) WHERE deleted_on IS NULL`
- `article_id uuid NOT NULL → article(id) RESTRICT` (forward addition; legacy has no FK; invoice integrity preserved by snapshot)
- `article_number VARCHAR(50) NOT NULL` — frozen snapshot from `article.article_number` at booking
- `item_text VARCHAR(250)` (PII catalog), `additional_information VARCHAR(250)` (PII)
- `quantity NUMERIC(12,4) NOT NULL` + `CHECK (>= 0)` (zero allowed for complimentary line items per legacy)
- `unit_price NUMERIC(12,4) NOT NULL DEFAULT 0` + `CHECK (>= 0)` (forward addition; legacy has no unit_price)
- `discount_in_percent INTEGER NOT NULL DEFAULT 0` + `CHECK (BETWEEN 0 AND 100)`
- `unit_type_code VARCHAR(50) NOT NULL` — frozen snapshot from `accounting_unit_type.code`
- **`total_amount NUMERIC(14,4) GENERATED ALWAYS AS (quantity * unit_price * (100 - discount_in_percent) / 100.0) STORED`** (Postgres 17 stored generated column)
- audit + soft-delete

**`delivery_creation_test`** (`DeliveryCreationTest.cs:22-65`):
- `id uuid PK` (aggregate root, `dct` prefix), `operating_club_id uuid NOT NULL`
- `flight_id uuid NOT NULL → flight(id) CASCADE` (harness payload dies with flight)
- `is_active BOOLEAN NOT NULL DEFAULT true`, `test_name VARCHAR(250) NOT NULL`, `description TEXT` (PII)
- `expected_delivery jsonb NOT NULL` — snapshot of full `DeliveryDetails` graph (recipient + flight info + items + info fields)
- `expected_matched_filter_ids BIGINT[] NOT NULL DEFAULT '{}'` — array NOT FK-enforced (deleted filter is legitimate regression signal)
- `must_not_create_delivery_for_flight BOOLEAN NOT NULL DEFAULT false`
- **9 ignore-on-compare boolean flags** (`DeliveryCreationTest.cs:39-55`): `ignore_recipient_name`, `ignore_recipient_address`, `ignore_recipient_person_id`, `ignore_recipient_club_member_number`, `ignore_delivery_information`, `ignore_additional_information`, `ignore_item_positioning`, `ignore_item_text`, `ignore_item_additional_information` — all `BOOLEAN NOT NULL DEFAULT false`
- **5 last_test_* result columns**: `last_test_run_on TIMESTAMPTZ NULL`, `last_test_successful BOOLEAN NULL`, `last_test_result_message TEXT`, `last_test_created_delivery jsonb NULL`, `last_test_matched_filter_ids BIGINT[] NULL`
- audit + soft-delete
- Partial UNIQUE `(operating_club_id, flight_id) WHERE deleted_on IS NULL`

**`delivery_creation_test_item`** — forward-looking, NOT in legacy (legacy stores items inside JSON):
- `id uuid PK`, `operating_club_id uuid NOT NULL` (denormalized)
- `delivery_creation_test_id uuid NOT NULL → delivery_creation_test(id) CASCADE`
- `position INTEGER NOT NULL` + `CHECK (>= 1)`
- `article_number VARCHAR(50) NOT NULL`, `item_text VARCHAR(250)`, `additional_information VARCHAR(250)`
- `quantity NUMERIC(12,4) NOT NULL`, `unit_price NUMERIC(12,4) NULL`, `unit_type_code VARCHAR(50) NOT NULL`, `discount_in_percent INTEGER NOT NULL DEFAULT 0`
- Minimal audit; no soft-delete (snapshot rows)

**`club_delivery_number_counter`** — operational counter (13th table):
- `operating_club_id uuid PRIMARY KEY → club(id) ON DELETE CASCADE`
- `next_number INTEGER NOT NULL DEFAULT 1`
- `modified_on TIMESTAMPTZ NOT NULL DEFAULT now()`
- Service-layer allocator at S-064: `UPDATE club_delivery_number_counter SET next_number = next_number + 1 WHERE operating_club_id = ? RETURNING next_number - 1`.

### SQL `COMMENT ON COLUMN` for forensic clarity

```sql
COMMENT ON COLUMN aircraft_reservation.id IS 'UUID v7. Aggregate root (ADR 0018). External form: arv_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN planning_day.id           IS 'UUID v7. Aggregate root. External form: pln_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN accounting_rule_filter.id IS 'UUID v7. Aggregate root. External form: arf_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN delivery.id               IS 'UUID v7. Aggregate root. External form: dlv_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN delivery_creation_test.id IS 'UUID v7. Aggregate root. External form: dct_<crockford-base32>. See ADR 0019.';

COMMENT ON COLUMN aircraft_reservation.aircraft_id IS
  'Cross-tenant FK per 2026-05-16 Aircraft-cross-tenant amendment. FK loads NOT @TenantId-filtered. Service layer (S-026/S-064) enforces "may operating_club reserve this aircraft?" via owner / charter / public-rental check. Audit event carries cross_tenant: true when aircraft_reservation.operating_club_id != aircraft.owner_club_id.';

COMMENT ON COLUMN delivery.recipient_lastname IS
  'Frozen snapshot at invoice booking per Swiss OR Art. 957a (10-year retention). NEVER re-resolve from recipient_person_id. DSAR-exempt once process_state_id >= 20.';
-- Same comment shape on the 8 other recipient_* columns.

COMMENT ON COLUMN delivery.delivery_number IS
  'Per-club gap-free invoice number per Swiss OR Art. 957a. Assigned at Book transition only. Hard DELETE forbidden once non-NULL (soft-delete via deleted_on). Gap-detection report at S-027.';

COMMENT ON COLUMN delivery.process_state_id IS
  'State machine: 10=Prepared, 20=Booked (terminal-on-mutation, gap-free numbering), 30=Error (retryable), 99=Cancelled. Reshape from legacy flight.process_state_id + delivery.is_further_processed; see S-016 cutover mapping.';

COMMENT ON COLUMN accounting_rule_filter.filter_config IS
  'jsonb predicate bag. Engine reads typed keys per filter_type_id; allow-list validated at S-064 write path. Jackson default-typing DISABLED globally; NEVER deserialize polymorphic types from this column (A03 injection mitigation). PII redaction: pii_blob: true.';

COMMENT ON COLUMN delivery_item.article_number IS 'Frozen snapshot from article.article_number at booking. Invoice integrity per Swiss OR Art. 957a.';
COMMENT ON COLUMN delivery_item.total_amount IS 'GENERATED ALWAYS AS (quantity * unit_price * (100 - discount_in_percent) / 100.0) STORED. Re-computation drift impossible.';
COMMENT ON COLUMN delivery.batch_id IS 'Operational sequence for batch-cancel via DeliveryBatchDeleteRequest. NOT an aggregate UUID (ADR 0019 escape hatch for operational counters).';
```

### Index strategy (load-bearing)

```sql
-- delivery
CREATE INDEX ix_delivery_club_state_date  ON delivery (operating_club_id, process_state_id, delivered_on DESC);
CREATE UNIQUE INDEX ux_delivery_club_number_partial ON delivery (operating_club_id, delivery_number) WHERE delivery_number IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_delivery_flight           ON delivery (flight_id) WHERE flight_id IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_delivery_club_batch       ON delivery (operating_club_id, batch_id);

-- delivery_item
CREATE INDEX ix_delivery_item_delivery    ON delivery_item (delivery_id) INCLUDE (article_id, article_number, quantity, unit_price, total_amount);
CREATE UNIQUE INDEX ux_delivery_item_delivery_pos ON delivery_item (delivery_id, position) WHERE deleted_on IS NULL;

-- aircraft_reservation (GiST + B-tree)
CREATE INDEX ix_ar_aircraft_range_gist    ON aircraft_reservation USING gist (aircraft_id, reservation_range) WHERE deleted_on IS NULL;
CREATE INDEX ix_ar_club_start_end         ON aircraft_reservation (operating_club_id, reservation_start, reservation_end) WHERE deleted_on IS NULL;
CREATE INDEX ix_ar_pilot                  ON aircraft_reservation (pilot_person_id, reservation_start DESC) WHERE pilot_person_id IS NOT NULL AND deleted_on IS NULL;

-- planning_day + assignment
CREATE UNIQUE INDEX ux_planning_day_club_date_loc ON planning_day (operating_club_id, planning_date, location_id) WHERE deleted_on IS NULL;
CREATE INDEX ix_pda_planning_day          ON planning_day_assignment (planning_day_id);
CREATE INDEX ix_pda_person                ON planning_day_assignment (assigned_person_id, planning_day_id) WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_pda_unique         ON planning_day_assignment (planning_day_id, assigned_person_id, assignment_type_id) WHERE deleted_on IS NULL;

-- accounting_rule_filter
CREATE INDEX ix_arf_club_active_sort      ON accounting_rule_filter (operating_club_id, is_active, sort_indicator) WHERE deleted_on IS NULL;  -- THE hot index
CREATE INDEX ix_arf_filter_config_gin     ON accounting_rule_filter USING gin (filter_config jsonb_path_ops);  -- admin search only

-- delivery_creation_test
CREATE UNIQUE INDEX ux_dct_club_flight_partial ON delivery_creation_test (operating_club_id, flight_id) WHERE deleted_on IS NULL;
```

### FK cascade rules (key entries)

| FK | ON DELETE | Rationale |
|---|---|---|
| `aircraft_reservation.aircraft_id → aircraft.id` | RESTRICT | Cross-tenant per amendment; preserve history |
| `aircraft_reservation.pilot_person_id → person.id` | RESTRICT | Cross-tenant; preserve history |
| `aircraft_reservation.second_crew_person_id → person.id` | SET NULL | Cross-tenant; optional |
| `aircraft_reservation.location_id → location.id` | RESTRICT | Cross-tenant shared |
| `planning_day_assignment.planning_day_id → planning_day.id` | CASCADE | Internal entity |
| `planning_day_assignment.assigned_person_id → person.id` | RESTRICT | Cross-tenant; preserve history |
| `delivery.flight_id → flight.id` | RESTRICT | Invoice trail integrity |
| `delivery.recipient_person_id → person.id` | SET NULL | Cross-tenant; snapshot survives |
| `delivery_item.delivery_id → delivery.id` | CASCADE | Internal |
| `delivery_item.article_id → article.id` | RESTRICT | Invoice integrity |
| `delivery_creation_test.flight_id → flight.id` | CASCADE | Harness payload |
| `delivery_creation_test_item.delivery_creation_test_id → delivery_creation_test.id` | CASCADE | Internal |
| `club_delivery_number_counter.operating_club_id → club.id` | CASCADE | Counter dies with club |

### `tenant-rules.yaml` updates

- Reclassify `AircraftReservationTypes` + `PlanningDayAssignmentTypes` from `reference` to `tenant-scoped`.
- Keep `AccountingRuleFilterTypes` + `AccountingUnitTypes` as `reference`.
- **`AircraftReservations.ride_through_targets: [Persons, Aircrafts]`** (Aircrafts added per 2026-05-16 amendment); add preconditions for service-layer `Aircraft.canBeReservedBy()` check + audit `cross_tenant: true` marker.
- PII catalog: `Deliveries.pii_columns: [recipient_name, recipient_firstname, recipient_lastname, recipient_address_line1, recipient_address_line2, recipient_zip_code, recipient_city, recipient_country_name, recipient_person_club_member_number, delivery_information, additional_information]`; free-text PII on `delivery_item.item_text`, `delivery_item.additional_information`, `aircraft_reservation.info`, `planning_day.info`, `planning_day_assignment.info`; whole-jsonb `pii_blob: true` on `accounting_rule_filter.filter_config`, `delivery_creation_test.expected_delivery`, `delivery_creation_test.last_test_created_delivery`.
- `Deliveries.fadp_dsar_retention_exempt_when: "process_state_id >= 20"` (Swiss OR Art. 957a override).
- Add new entries: `DeliveryCreationTest`, `DeliveryCreationTestItem`, `ClubDeliveryNumberCounter`.

### Module layout

- New: `next/server/src/main/resources/db/migration/V4__reservations_planning_accounting.sql` (~600-700 lines).
- Edit: `next/database/tenant-rules.yaml` (12 entries + reclassifications + Aircraft-cross-tenant ride-through).
- New: `next/server/src/test/java/ch/alpenflight/server/migration/ReservationsBaselineIntegrationTest.java` (~55 tests). (Package is `ch.alpenflight` per the S-128 FLS → AlpenFlight technical rebrand, not the legacy `ch.fls`.)
- Extend: `MigrationFolderConventionsTest`, `FlywayBootstrapIntegrationTest`, `TenantCatalogConsistencyTest`.
- Extend: `next/server/src/test/resources/reference-seeds-canonical-uuids.json` (10 + ≥3 canonical UUIDs).
- Extend: `next/server/src/test/resources/scripts/generate-canonical-uuids.java`.
- Edit: `next/server/src/test/resources/forbidden-migration-patterns.txt` — allowlist new ref seeds; deny `INSERT INTO delivery|delivery_item|aircraft_reservation|planning_day|planning_day_assignment|accounting_rule_filter|delivery_creation_test*`.

### Alternatives considered

- **Chosen — single V4 migration; UUID v7 PKs; `filter_config jsonb` + `filter_type_id` discriminator; SMALLINT process_state_id with CHECK; two TIMESTAMPTZ + generated tsrange (NOT EXCLUDE constraint); counter table for per-club delivery numbering; generated `total_amount` STORED.**
- Rejected — per-type tables for AccountingRuleFilter (fragments rules engine).
- Rejected — EXCLUDE USING gist on aircraft_reservation (multiple legitimate-overlap rules).
- Rejected — Postgres ENUM for process_state_id (SMALLINT + CHECK + lookup table at S-064 is operator-friendlier).
- Rejected — legacy `delivery.delivery_number VARCHAR` parity (reshape to INTEGER per OR 957a + counter table).
- Rejected — hard-DELETE on Booked deliveries (Swiss OR 957a 10-year retention; revoke DELETE at DB role).

## Edge cases & hidden requirements

### Per-AC edge cases

- **AC1**: 12 tables + counter = 13 schema artifacts. `delivery_creation_test_item` forward-looking (legacy stores items inside JSON). `aircraft_reservation_type` + `planning_day_assignment_type` reclassified TENANT_SCOPED per legacy `ClubId NOT NULL`.
- **AC2**: AircraftReservation TENANT_SCOPED via `operating_club_id` but `aircraft_id` is now CROSS-TENANT FK per amendment.
- **AC3 (Delivery state machine)**: legacy has NO `delivery.process_state_id`; state lives on `flight.process_state_id` + `delivery.is_further_processed`. Refinement reshapes to first-class on Delivery. Migration header documents S-016 cutover mapping.
- **AC4 (filter_config jsonb)**: per-discriminator typed shape; engine reads wholesale + interprets in Java; GIN admin-search-only; Jackson default-typing DISABLED.
- **AC5 (DeliveryCreationTest)**: 9 `ignore_*` boolean knobs + 5 `last_test_*` result fields + `expected_matched_filter_ids BIGINT[]` (NOT FK-enforced) + `must_not_create_delivery_for_flight BOOLEAN`. Data origin: operator-driven (NOT migration-seeded).

### Hidden requirements (promoted)

- `club_delivery_number_counter` operational table (13th).
- `CREATE EXTENSION IF NOT EXISTS btree_gist`.
- AircraftReservation cross-tenant aircraft FK column comment (per 2026-05-16 amendment).
- `tenant-rules.yaml` reclassifications + PII catalog extension + `AircraftReservations.ride_through_targets: [Persons, Aircrafts]`.
- `legacy_int_id SMALLINT UNIQUE` on reference tables for S-016 cutover.
- Reference-data canonical UUIDs committed in `reference-seeds-canonical-uuids.json` + generator script.
- Migration header sections documenting all forward-looking reshapes.
- SQL column comments (5 aggregate-root IDs + 9 frozen recipient + state-machine + jsonb hardening + cross-tenant aircraft + batch_id + total_amount generated-stored).
- `forbidden-migration-patterns.txt` extension.
- DSAR retention exemption in tenant-rules.yaml.

### Scope clarifications

**In:** 12 tables + counter + extension + indexes + FKs + CHECKs + reference seeds + tenant-rules.yaml + 5 aggregate-root + ~12 SQL column comments + `ReservationsBaselineIntegrationTest` (~55 tests) + `TenantCatalogConsistencyTest` extensions.

**Out:** JPA entities (S-022); `@TenantId` filter wiring (S-022); aggregate-method invariants (S-022/S-064); aggregate prefix codec (S-022); audit log (S-027); rules engine implementation (S-064); delivery state-machine transitions (S-064); delivery numbering allocator service (S-064); delivery PDF (S-099); email export (S-066/S-090); legacy cutover (S-016); production-scale perf (S-108); DSAR cross-club cascade (S-051); Swiss tax compliance review (operator UAT).

### Things not the right shape

- AC `process_state_id` originally listed only Prepared/Booked/Error — refinement adds `Cancelled` (99) for state-machine completeness.
- `DeliveryItem` AC line lists `unit_price + total` — forward-looking (NOT in legacy).
- `delivery_creation_test_item` table — forward-looking (NOT in legacy).
- Original `adr_refs: [0002, 0003, 0008]` — expanded to include 0001/0007/0018/0019.
- AC for `delivery_creation_test references a flight_id + a JSON snapshot of expected delivery items` understates: snapshot is full `DeliveryDetails` graph + 9 `ignore_*` flags + 5 `last_test_*` columns + filter_ids array.

## Security plan

### Threat model

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| (a) | Cross-tenant Delivery tampering (Club A POSTs Delivery with Club B's flight_id/recipient_person_id) | High | `operating_club_id NOT NULL` + `@TenantId`; service-layer re-verify Flight under tenant filter; PersonClub membership check for recipient |
| (b) | Delivery price manipulation post-Booked | High (financial) | State-machine at S-064 rejects DML on Booked deliveries; audit log captures every attempt |
| (c) | `accounting_rule_filter.filter_config` jsonb injection / Jackson polymorphic-deserialization gadget | Critical | Globally disable Jackson default-typing; ban `@JsonTypeInfo(use=Id.CLASS)` via ArchUnit; allow-list `filter_type_id → JSON Schema` at S-064; SQL column comment forbids; ADMIN-only mutation |
| (d) | Delivery recipient snapshot mutation post-issuance | High (legal — OR 957a) | 9 frozen columns directly on row; NEVER re-resolve; CHECK `(process_state_id <> 20 OR recipient_lastname IS NOT NULL)` |
| (e) | DeliveryCreationTest jsonb PII exposure | Med | tenant-scoped; pii_blob: true; READER denied; audit redacts |
| (f) | PlanningDayAssignment cross-tenant Person FK tampering | Med | Sacred-cow shape; service-layer PersonClub-membership check at S-064 |
| **(g)** | **AircraftReservation cross-club aircraft authorization** (NEW per amendment) | **High** | **Aircraft is cross-tenant; S-022/S-064 calls `Aircraft.canBeReservedBy(clubId)`; audit event with `cross_tenant: true` marker when `aircraft.owner_club_id != aircraft_reservation.operating_club_id`** |
| (h) | AircraftReservation availability spoofing | Med | Mitigated by (g) + soft-delete + audit log |
| (i) | Hibernate jsonb gadget deserialization (filter_config, expected_delivery, last_test_created_delivery) | Critical | Same as (c) — globally disable default-typing; ArchUnit ban |
| (j) | DSAR vs invoice retention conflict (Swiss OR 957a 10-year retention) | High (legal) | Recipient snapshot DSAR-exempt once Booked; documented; tenant-rules.yaml `fadp_dsar_retention_exempt_when` |
| (k) | Per-club Delivery numbering gap-free invariant (OR 957a) | High (legal) | UNIQUE per club; DELETE revoked at DB role; soft-delete only on numbered rows; gap-detection report at S-027 |
| (l) | `accounting_rule_filter` config drift (financial blast radius) | High | HIGHEST audit; full before/after JSON; CLUB_ADMIN-only; soft-delete only; mandatory change_reason at S-064 |
| (m) | Free-text PII spill | Med | Classify in pii_columns; audit-blob redaction; length caps |
| (n) | UUID v7 timestamp leak | Low | Per ADR 0019; documented |
| (o) | Aggregate-prefix reveals entity type | Very Low | By design |
| (p) | `delivery.batch_id` cross-tenant collision | Low | UNIQUE per club |
| (q) | `delivery_creation_test.flight_id` cross-tenant FK leak | Low | Service-layer same-tenant check |
| (s) | Negative quantity / discount-bomb fraud on delivery_item | High | CHECKs `>= 0`; total_amount GENERATED STORED (no client override) |

### Authorization

- DB-role split: `migrator` owns DDL + system-global reference INSERT; `app_runtime` DML on tenant-scoped; **DELETE revoked on `delivery` + `delivery_item`** (soft-delete only).
- App-layer authz at aggregate-method boundary: `Delivery.create/update` requires `FLIGHT_OPS`/`CLUB_ADMIN`; `Delivery.book` requires `CLUB_ADMIN` (HIGHEST audit); `accounting_rule_filter.*` requires `CLUB_ADMIN`; `AircraftReservation.create/update` requires `PILOT`/`FLIGHT_OPS`/`CLUB_ADMIN` + service-layer Aircraft authz; `PlanningDay/Assignment.*` requires `FLIGHT_OPS`/`CLUB_ADMIN` + PersonClub-membership check.

### Input validation (schema-level)

- `delivery.process_state_id IN (10, 20, 30, 99)` CHECK.
- `delivery.delivery_number > 0` CHECK + UNIQUE per club partial.
- `delivery.delivered_on <= now() + INTERVAL '1 day'` CHECK.
- `delivery.batch_id` UNIQUE per club partial.
- `delivery_item.quantity/unit_price >= 0`; `discount_in_percent BETWEEN 0 AND 100`; `position >= 1` + UNIQUE per delivery.
- `aircraft_reservation.reservation_end > reservation_start` + sanity cap `<= start + INTERVAL '30 days'`.
- `planning_day.planning_date BETWEEN '1990-01-01' AND '2100-01-01'`.
- `planning_day_assignment_type.required_nr_of_assignments >= 0`.
- `accounting_rule_filter.sort_indicator >= 0` + UNIQUE per club partial.
- `accounting_rule_filter.filter_config jsonb NOT NULL DEFAULT '{}'::jsonb`.
- UUID columns reject malformed input at type level.

### PII handling

- **Frozen direct PII on delivery** (9 cols `recipient_*`): DSAR-exempt once Booked (OR Art. 957a).
- **Free-text quasi-PII**: `delivery.delivery_information/additional_information`, `delivery_item.item_text/additional_information`, `aircraft_reservation.info`, `planning_day.info`, `planning_day_assignment.info`.
- **Whole-jsonb redaction (pii_blob: true)**: `accounting_rule_filter.filter_config`, `delivery_creation_test.expected_delivery`, `delivery_creation_test.last_test_created_delivery`.
- **Cross-tenant Person ride-through**: 4 FK columns; audit emits prefixed `psn_<crockford>` form.

### Audit-log events (forward S-027)

- `delivery.created/updated/state_transitioned/booked/cancelled/voided/soft_deleted` (state_transitioned + booked HIGHEST priority; voided requires reason).
- `delivery_item.created/updated/deleted` (price changes HIGHEST priority).
- `accounting_rule_filter.created/updated/deleted/reordered/activated/deactivated` — HIGHEST audit priority; mandatory change_reason.
- `aircraft_reservation.created/updated/deleted/cancelled` (cross_tenant marker per amendment); `aircraft_swapped` sub-event.
- `planning_day.*` + `planning_day_assignment.assigned/removed`.
- `delivery_creation_test.created/updated/deleted/run_completed`.

All `target.id` carry prefixed external form.

### Cross-tenant leakage

- 9 TENANT_SCOPED tables; `@TenantId` auto-filters at S-022.
- Sacred-cow cross-tenant FKs: `delivery.recipient_person_id`, `aircraft_reservation.aircraft_id` (NEW per amendment), `pilot_person_id`, `second_crew_person_id`, `location_id`, `planning_day_assignment.assigned_person_id`, `planning_day.location_id`, `delivery_creation_test.flight_id`.
- **S-024 leakage CI refiner must add `aircraft_reservation.aircraft_id` to the cross-tenant FK roster per amendment.**

### OWASP applicability

A01 (tenant + cross-tenant aircraft authz), A02 (free-text PII at rest; FDE), A03 (jsonb Jackson default-typing DISABLED), A04 (invoice integrity + gap-free numbering + soft-delete only on numbered rows), A08 (Flyway checksum + CODEOWNERS), A09 (state-transition + price-line + rule-mutation audit MANDATORY).

### Story-specific concerns

Swiss tax law (OR 957a/958f, MWSTG 70) — invoice gap-free per fiscal year per club; recipient + article snapshot at issuance; retention 10 years overrides FADP DSAR. CODEOWNERS on `db/migration/**`. `filter_config` Jackson hardening via ArchUnit rule. AircraftReservation cross-tenant authz at S-022/S-064. **S-024 roster update: add `aircraft_reservation.aircraft_id` cross-tenant FK per amendment.**

## Test plan

### Coverage contract

**Owns:** 12 tables + counter + indexes + FKs + CHECKs + reference seeds + tenant-rules.yaml + 5 aggregate-root + ~12 SQL column comments + state-machine CHECK + frozen recipient + generated total_amount + GiST + cross-tenant aircraft_id comment + `ReservationsBaselineIntegrationTest` (~55 tests) + `TenantCatalogConsistencyTest` extensions.

**Does NOT own:** JPA entities (S-022); `@TenantId` filter (S-022); aggregate-method invariants (S-022/S-064); aggregate prefix codec (S-022); live leakage CI (S-024 — **must add aircraft_reservation.aircraft_id per amendment**); audit log (S-027); rules engine (S-064); delivery state-machine transitions (S-064); delivery numbering allocator (S-064); delivery PDF (S-099); email export (S-066/S-090); legacy cutover (S-016); production-scale perf (S-108); Swiss tax compliance review (operator UAT).

### Specific test cases (highlights)

- `all_12_tables_plus_counter_present` (containsExactlyInAnyOrder).
- `all_pk_columns_are_uuid_not_null`, `all_fk_columns_are_uuid` (parameterized).
- `delivery_process_state_id_check_pins_10_20_30_99` (provoke 999 → 23514).
- `delivery_unique_per_club_delivery_number_partial` (provoke dup → 23505; cross-club same number → success).
- `delivery_has_9_recipient_snapshot_columns` (parameterized; width assertions).
- `delivery_booked_requires_delivery_number_check` (provoke `process_state=20, delivery_number=NULL` → 23514).
- `delivery_booked_requires_recipient_lastname_check`.
- `delivery_item_total_amount_is_generated_always_stored` (pg_attribute.attgenerated='s').
- `delivery_item_quantity_nonnegative_check`, `unit_price_nonnegative_check`, `discount_in_range_check`.
- `aircraft_reservation_end_after_start_check`.
- `aircraft_reservation_has_generated_tsrange_column`.
- `aircraft_reservation_gist_index_on_aircraft_range_present`.
- **`aircraft_reservation_aircraft_id_cross_tenant_column_comment`** (NEW per amendment — `pg_description` contains "cross-tenant").
- `planning_day_unique_per_club_date_location_partial`.
- `planning_day_assignment_person_fk_restrict` (sacred-cow).
- `accounting_rule_filter_filter_config_is_jsonb_not_null`.
- `accounting_rule_filter_gin_index_on_filter_config_jsonb_path_ops`.
- `accounting_rule_filter_sort_indicator_unique_per_club_partial`.
- `accounting_rule_filter_type_seeded_with_8_canonical_codes` (cite `database/FLSTest/3 insert/3 Insert Static Data.sql`).
- `accounting_unit_type_seeded_with_4_canonical_codes` (MINUTES, SECONDS, LANDINGS, START_OR_FLIGHT).
- `aircraft_reservation_type_NOT_seeded_in_migration` (per-club via API).
- `delivery_creation_test_flight_fk_cascade`.
- `delivery_creation_test_has_9_ignore_boolean_columns` (parameterized).
- `delivery_creation_test_expected_matched_filter_ids_is_bigint_array_not_fk`.
- `club_delivery_number_counter_pk_is_operating_club_id`.
- `aggregate_root_column_comments_reference_adr_0019` (parameterized over 5 roots).
- `TenantCatalogConsistencyTest`: `aircraft_reservation_type_reclassified_to_tenant_scoped`, `planning_day_assignment_type_reclassified_to_tenant_scoped`, **`aircraft_reservation_aircraft_id_is_cross_tenant_ride_through`** (NEW).

### Parity strategy

N/A — schema reshape; reference-seed enum values pinned via legacy-code citations.

### Test data + fixtures

Shared `PostgresTestContainerLifecycle`; identical `@DynamicPropertySource` for context cache reuse; SQLSTATE-based assertions (23514 / 23505 / 23503); minimal-graph fixtures with savepoint/rollback; `reference-seeds-canonical-uuids.json` extends S-012/S-013.

### Coverage gaps (deferred)

JPA + `@UuidV7` → S-022. `@TenantId` filter → S-022. Aggregate-method invariants → S-022/S-064. Live leakage CI → S-024 (**add aircraft_reservation.aircraft_id parameter per amendment**). Audit log → S-027. Rules engine → S-064. Delivery state-machine transitions → S-064. Delivery numbering allocator → S-064. Delivery PDF → S-099. Production-scale perf → S-108.

### Risks

- V4 ordering collision (S-018 ShedLock could land between V3 and V4 and push S-014 to V5) — tests assert `>= 4` not `== 4` to tolerate.
- Reference-seed UUID immutability post-merge — committed generator script + JSON pin map.
- Test boot-time growth — identical `@DynamicPropertySource` for context cache hits.
- Generated-column choice — pinned GENERATED STORED; S-016 may revisit for legacy import edge cases.
- Locale-fragile message text — assert SQLSTATE.
- `btree_gist` extension dependency — `CREATE EXTENSION` at top of migration.
- **Aircraft cross-tenant parameter not in S-024 roster** — load-bearing hand-off.

## Performance plan

### Hot paths

- AccountingRuleFilter evaluation per flight write (per-club ordered scan).
- Delivery list per club + state + fiscal year (operator dashboard).
- AircraftReservation conflict detection on submit (range-overlap on aircraft_id).
- AircraftReservation 30-day calendar (per-club).
- PlanningDay 7-day widget.
- DeliveryItem fetch alongside Delivery.
- Delivery numbering claim via counter table (UPDATE...RETURNING, sub-10ms).

### Required indexes

Full grid in Design notes. Load-bearing:
- `ix_delivery_club_state_date` — primary dashboard.
- `ix_delivery_item_delivery INCLUDE(...)` — Index-Only Scan.
- `ix_ar_aircraft_range_gist` — conflict probe sub-10ms.
- `ix_ar_club_start_end` — 30-day calendar.
- `ux_planning_day_club_date_loc`.
- `ix_arf_club_active_sort` — THE rules engine hot index.
- `ix_arf_filter_config_gin` (jsonb_path_ops) — admin search only.

### N+1 risks (forward S-022)

- Delivery → DeliveryItem always-eager via `@EntityGraph`.
- AccountingRuleFilter → AccountingRuleFilterType / AccountingUnitType: `@ManyToOne LAZY` + `@Cache(READ_ONLY)` 24h L2.
- AircraftReservation → Aircraft/Persons/Location/Types: fetch-join Aircraft+Location + `@BatchSize(64)` for Persons+Types.
- PlanningDay → assignments → Person: fetch-join assignment, `@BatchSize(64)` for Person.

### Caching

- `accounting_rule_filter` per-club: L2 5min TTL (CRITICAL — evict on rule edit).
- Reference data (4 types): L2 24h READ_ONLY.
- `delivery`, `delivery_item`: NEVER cached.
- `aircraft_reservation`: NEVER by default; 60s SWR on calendar if S-108 measured hot.

### Latency budget (forward S-108)

- Rules-engine filter load (L2 hit): p95 < 5ms; cold < 50ms DB.
- Per-flight rules evaluation in Java: p95 < 20ms.
- `POST /api/v1/deliveries`: p95 < 150ms.
- `GET /api/v1/deliveries?...`: p95 < 100ms.
- `POST /api/v1/aircraft-reservations`: p95 < 80ms; probe alone < 30ms.
- `GET /api/v1/aircraft-reservations?from&to`: p95 < 200ms.
- `GET /api/v1/planning-days?from&to`: p95 < 100ms.
- Delivery numbering claim: p95 < 10ms.

### Memory

Schema footprint modest (~10K deliveries/year/club, ~5K reservations/year/club, ~100 rules/club; < 50 MB per club at 5-year horizon). `filter_config` median ~500B. `shared_buffers` 4 GB (carries from S-013).

### Performance test plan

EXPLAIN canaries (6 hot queries) at 10K-row fixture; force `enable_seqscan = off` + `enable_bitmapscan = off`; assert Index Scan / Index Only Scan / GiST / GIN as appropriate. Production-scale deferred to S-108.

### Configuration choices

- `uuid NOT NULL PRIMARY KEY` per ADR 0019.
- Two TIMESTAMPTZ + generated `reservation_range tsrange ... STORED` (chosen over functional GiST).
- `filter_config jsonb` evaluated in Java; GIN admin-search-only.
- `delivery_item.total_amount NUMERIC(14,4) GENERATED ALWAYS AS ... STORED`.
- `delivery.process_state_id SMALLINT` + CHECK enum.
- `club_delivery_number_counter` per-row UPDATE...RETURNING for per-club monotonic numbering.

## Open design questions

1. **Delivery state-machine reshape vs legacy parity (Q5):** Recommend reshape (delivery.process_state_id first-class); S-016 cutover maps. Operator confirms.
2. **`delivery.delivery_number` INTEGER vs legacy VARCHAR (Q5b):** Recommend INTEGER + counter table; legacy text format on `club_extension` (S-012) or `delivery.legacy_delivery_number_text VARCHAR(50)` parity column at S-016.
3. **`delivery_creation_test_item` table vs jsonb-only (Q3):** Recommend ship the table for admin query support; alternative is JSON-only.
4. **`accounting_rule_filter.filter_config` jsonb schema validation (Q6):** Recommend app-layer at S-064 (no `pg_jsonschema` extension dependency).
5. **`accounting_rule_filter.sort_indicator` per-club UNIQUE (Q12):** Recommend yes (deterministic engine output).
6. **Per-fiscal-year vs perpetual delivery numbering (Q7):** Swiss OR Art. 957a typically expects per-fiscal-year reset. Should `club_delivery_number_counter` carry `(operating_club_id, fiscal_year SMALLINT)` composite key or single-row-per-club? **Operator decision.**
7. **`delivery_item.unit_price` + generated `total_amount` forward-looking:** Confirmed forward addition; S-016 cutover back-fills from article master.
8. **`delivery.batch_id` reshape:** Keep as BIGINT operational sequence (NOT aggregate UUID per ADR 0019 escape hatch). UNIQUE per club.

<!-- modernize-refine: end -->


<!-- modernize-refine: start -->

## Design notes

### Migration shape

Ships as **single `V4__reservations_planning_accounting.sql`** (~600-700 lines). Actual migration sequence on `main` is V1 baseline / V2 identity (S-012) / V3 flights-aircraft-locations (S-013), so S-014 lands at V4. Tests assert `>= 4` not `== 4` to tolerate ordering shifts. Header documents: UUID v7 convention; Aircraft-cross-tenant 2026-05-16 amendment; state-machine reshape from `flight.process_state_id + delivery.is_further_processed` to first-class `delivery.process_state_id`; `delivery_number` INTEGER reshape from legacy VARCHAR; `filter_config` jsonb per-discriminator typed shape; `expected_matched_filter_ids BIGINT[]` NOT FK-enforced; `aircraft_reservation_type` + `planning_day_assignment_type` reclassified TENANT_SCOPED; `delivery_creation_test_item` forward-looking table (NOT in legacy); `delivery_item.unit_price` + generated `total_amount` forward-looking (NOT in legacy).

FK ordering across 3 clusters + counter:
1. **Reservations:** `aircraft_reservation_type` → `aircraft_reservation`.
2. **Planning:** `planning_day_assignment_type` → `planning_day` → `planning_day_assignment`.
3. **Accounting:** `accounting_rule_filter_type` + `accounting_unit_type` → `accounting_rule_filter` → `delivery` → `delivery_item` → `delivery_creation_test` → `delivery_creation_test_item`.
4. **Operational:** `club_delivery_number_counter`.

Migration begins with `CREATE EXTENSION IF NOT EXISTS btree_gist;` (Postgres 17 contrib; required for the composite GiST index on `(aircraft_id, reservation_range)`).

### ID strategy (per ADR 0019; carries from S-012/S-013)

Every PK + FK column is `uuid`. No `DEFAULT gen_random_uuid()`. App generates at S-022 via Hibernate 7 + uuid-creator. The `@UuidV7` annotation + `FlsUuidV7Generator` wiring lands at S-022; S-014 ships only the schema. Audit columns: `created_on TIMESTAMPTZ NOT NULL DEFAULT now()`, `created_by_user_id uuid` (no FK; chicken-and-egg), `modified_on`, `modified_by_user_id`, plus `deleted_on TIMESTAMPTZ NULL` + `deleted_by_user_id uuid NULL` for soft-delete on every TENANT_SCOPED mutable table.

### Aggregate composition (per ADR 0018)

| Layer | Tables in S-014 | Tenant scope | Notes |
|---|---|---|---|
| **Aggregate roots** (5) | `aircraft_reservation` (`arv`), `planning_day` (`pln`), `accounting_rule_filter` (`arf`), `delivery` (`dlv`), `delivery_creation_test` (`dct`) | All TENANT_SCOPED via `operating_club_id` | Prefix at JSON/URL/log boundary; SQL `COMMENT ON COLUMN id` references ADR 0019. |
| **Internal entities under `Delivery`** | `delivery_item` | TENANT_SCOPED (denormalized) | CASCADE on Delivery delete. Carries `operating_club_id` denormalized for `@TenantId` symmetry. |
| **Internal entities under `PlanningDay`** | `planning_day_assignment` | TENANT_SCOPED (denormalized) | CASCADE on PlanningDay delete. Cross-tenant Person FK preserved as sacred cow. |
| **Internal entities under `DeliveryCreationTest`** | `delivery_creation_test_item` | TENANT_SCOPED (denormalized) | CASCADE on parent delete. Forward-looking table (NOT in legacy). |
| **TENANT_SCOPED per-club reference** (reclassified from S-011 `reference`) | `aircraft_reservation_type`, `planning_day_assignment_type` | TENANT_SCOPED via `operating_club_id` | Legacy carries `ClubId NOT NULL`; per-club lookups; not aggregate roots (Club-aggregate-internal lookups, per S-012 precedent on member_state/person_category). |
| **SYSTEM_GLOBAL reference** | `accounting_rule_filter_type`, `accounting_unit_type` | None | No legacy `ClubId`; seeded with canonical UUIDs + `legacy_int_id SMALLINT UNIQUE` for S-016 cutover. |
| **Operational** | `club_delivery_number_counter` | TENANT_SCOPED (PK is `operating_club_id` itself) | One row per Club; service-layer monotonic delivery-number allocator at S-064. |

Cross-aggregate FKs (UUIDs at boundary):
- `delivery.flight_id → flight(id) RESTRICT` (Flight is TENANT_SCOPED; service-layer re-verifies `flight.operating_club_id == delivery.operating_club_id`).
- `delivery_item.article_id → article(id) RESTRICT` (same-tenant; invoice integrity).
- `aircraft_reservation.aircraft_id → aircraft(id) RESTRICT` — **cross-tenant FK per 2026-05-16 amendment** (Aircraft has no `@TenantId`).
- `aircraft_reservation.location_id → location(id) RESTRICT` (cross-tenant; Location is shared sacred cow).
- `aircraft_reservation.flight_type_id → flight_type(id) RESTRICT` (per-club; service-layer same-tenant check).
- `aircraft_reservation.reservation_type_id → aircraft_reservation_type(id) RESTRICT` (per-club, denormalized).
- `planning_day.location_id → location(id) RESTRICT` (cross-tenant).
- `planning_day_assignment.assignment_type_id → planning_day_assignment_type(id) RESTRICT`.
- `accounting_rule_filter.filter_type_id → accounting_rule_filter_type(id) RESTRICT` (system-global discriminator).
- `accounting_rule_filter.accounting_unit_type_id → accounting_unit_type(id) RESTRICT` (system-global).
- `delivery_creation_test.flight_id → flight(id) CASCADE` (harness payload dies with flight).

Cross-tenant ride-through Person FKs (Hibernate `@TenantId` does NOT filter; sacred cow per ADR 0008):
- `delivery.recipient_person_id → person(id) ON DELETE SET NULL` (invoice recipient may be from another club).
- `aircraft_reservation.pilot_person_id → person(id) ON DELETE RESTRICT` (preserve reservation history; DSAR scrubs PII, not row).
- `aircraft_reservation.second_crew_person_id → person(id) ON DELETE SET NULL` (optional).
- `planning_day_assignment.assigned_person_id → person(id) ON DELETE RESTRICT` (preserve planning history).

### Per-table column inventory

**`aircraft_reservation`** (`AircraftReservation.cs:21-35`):
- `id uuid PRIMARY KEY` (aggregate root, `arv` prefix)
- `operating_club_id uuid NOT NULL → club(id) RESTRICT` (the booking club)
- `aircraft_id uuid NOT NULL → aircraft(id) RESTRICT` — **cross-tenant FK** per 2026-05-16 amendment
- `reservation_start TIMESTAMPTZ NOT NULL`, `reservation_end TIMESTAMPTZ NOT NULL` + `CHECK (reservation_end > reservation_start)`
- `reservation_range tsrange GENERATED ALWAYS AS (tsrange(reservation_start, reservation_end, '[)')) STORED`
- `is_all_day BOOLEAN NOT NULL DEFAULT false`
- `pilot_person_id uuid NOT NULL → person(id) RESTRICT` (cross-tenant)
- `second_crew_person_id uuid NULL → person(id) SET NULL` (cross-tenant)
- `location_id uuid NOT NULL → location(id) RESTRICT` (cross-tenant; Location shared)
- `reservation_type_id uuid NULL → aircraft_reservation_type(id) RESTRICT` (legacy nullable)
- `flight_type_id uuid NULL → flight_type(id) RESTRICT` (per-club)
- `info TEXT` (legacy `Remarks`; PII catalog)
- Audit + soft-delete columns

**`aircraft_reservation_type`** — **TENANT_SCOPED** per legacy `AircraftReservationType.cs:33` `ClubId NOT NULL`:
- `id uuid PRIMARY KEY`, `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `reservation_type_name VARCHAR(100) NOT NULL`
- `is_instructor_required BOOLEAN NOT NULL DEFAULT false`
- `is_maintenance BOOLEAN NOT NULL DEFAULT false`
- `is_active BOOLEAN NOT NULL DEFAULT true`
- `remarks TEXT`
- Audit + soft-delete

**`planning_day`** (`PlanningDay.cs:21-29`):
- `id uuid PRIMARY KEY` (aggregate root, `pln` prefix)
- `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `planning_date DATE NOT NULL` + `CHECK (planning_date BETWEEN '1990-01-01' AND '2100-01-01')`
- `location_id uuid NOT NULL → location(id) RESTRICT`
- `info TEXT` (PII catalog)
- Audit + soft-delete
- `UNIQUE (operating_club_id, planning_date, location_id) WHERE deleted_on IS NULL`

**`planning_day_assignment`** (`PlanningDayAssignment.cs:19-23`):
- `id uuid PRIMARY KEY` (internal entity; no prefix)
- `operating_club_id uuid NOT NULL → club(id) RESTRICT` (denormalized from planning_day)
- `planning_day_id uuid NOT NULL → planning_day(id) CASCADE`
- `assigned_person_id uuid NOT NULL → person(id) RESTRICT` (cross-tenant ride-through)
- `assignment_type_id uuid NOT NULL → planning_day_assignment_type(id) RESTRICT`
- `info TEXT` (PII catalog)
- Audit + soft-delete
- `UNIQUE (planning_day_id, assigned_person_id, assignment_type_id) WHERE deleted_on IS NULL`

**`planning_day_assignment_type`** — **TENANT_SCOPED** per legacy `PlanningDayAssignmentType.cs:21` `ClubId NOT NULL`:
- `id uuid PRIMARY KEY`, `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `assignment_type_name VARCHAR(100) NOT NULL`
- `required_nr_of_assignments SMALLINT NOT NULL DEFAULT 1` + `CHECK (>= 0)`
- Audit + soft-delete

**`accounting_rule_filter`** (`AccountingRuleFilter.cs:22-89`):
- `id uuid PRIMARY KEY` (aggregate root, `arf` prefix)
- `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `filter_type_id uuid NOT NULL → accounting_rule_filter_type(id) RESTRICT` (discriminator)
- `accounting_unit_type_id uuid NULL → accounting_unit_type(id) RESTRICT`
- `rule_filter_name VARCHAR(250) NOT NULL`
- `description TEXT`
- `is_active BOOLEAN NOT NULL DEFAULT true`
- `sort_indicator INTEGER NOT NULL DEFAULT 0` + `CHECK (>= 0)`
- `stop_rule_engine_when_applied BOOLEAN NOT NULL DEFAULT false`
- `is_charged_to_club_internal BOOLEAN NOT NULL DEFAULT false`
- `article_target VARCHAR(50)` (action: article-number rule emits)
- `recipient_target VARCHAR(50)` (action: recipient routing key)
- `filter_config jsonb NOT NULL DEFAULT '{}'::jsonb` — **all 30+ predicate columns from legacy collapse here**
- Audit + soft-delete

The `filter_config` jsonb bag holds (per `AccountingRuleFilter.cs:42-115`): `is_rule_for_glider/towing/motor_flights`, `use_*_except_listed` flags + matched-list strings (`MatchedAircraftImmatriculations`, `MatchedStartTypes`, `MatchedFlightTypeCodes`, `MatchedStartLocations`, `MatchedLdgLocations`, `MatchedClubMemberNumbers`, `MatchedFlightCrewTypes`, `MatchedAircraftsHomebase`, `MatchedMemberStates`, `MatchedPersonCategories`), min/max `flight_time_in_seconds`, min/max `engine_time_in_seconds`, `include_threshold_text`, `threshold_text`, `include_flight_type_name`, `no_landing_tax_for_glider/towing/aircraft`, `extend_matching_flight_type_codes_to_glider_and_tow_flight`. Per-discriminator typed shape validation at S-064.

**`accounting_rule_filter_type`** — **SYSTEM_GLOBAL** (`AccountingRuleFilterType.cs:18-32`, no `ClubId`):
- `id uuid PRIMARY KEY`, `code VARCHAR(50) NOT NULL UNIQUE`, `name VARCHAR(100) NOT NULL`, `description TEXT`, `legacy_int_id SMALLINT UNIQUE`
- Seed 8 canonical codes per legacy `database/FLSTest/3 insert/3 Insert Static Data.sql`: RECIPIENT (10), NO_LANDING_TAX (20), FLIGHT_TIME (30), INSTRUCTOR_FEE (40), ADDITIONAL_FUEL_FEE (50), LANDING_TAX (60), VSF_FEE (70), ENGINE_TIME (80). Code rule classes under `FLS.Server.Service/Accounting/Rules/*.cs` (e.g. DoNotInvoiceFlightRule, StartTaxRule) are strategy implementations, NOT seeded filter types.

**`accounting_unit_type`** — **SYSTEM_GLOBAL** (`AccountingUnitType.cs:18-32`):
- `id uuid PRIMARY KEY`, `code VARCHAR(50) NOT NULL UNIQUE`, `name VARCHAR(100) NOT NULL`, `legacy_int_id SMALLINT UNIQUE`
- Seed ≥3 canonical codes per legacy.

**`delivery`** (`Delivery.cs:13-94`):
- `id uuid PRIMARY KEY` (aggregate root, `dlv` prefix)
- `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `process_state_id SMALLINT NOT NULL DEFAULT 10` + `CHECK (process_state_id IN (10, 20, 30, 99))` — **new column, NOT legacy parity** (legacy stores state on flight + boolean)
- `flight_id uuid NULL → flight(id) RESTRICT` (nullable for manual deliveries; service-layer asserts same-tenant)
- `recipient_person_id uuid NULL → person(id) SET NULL` (cross-tenant ride-through)
- **Frozen recipient snapshot** (9 columns):
  - `recipient_name VARCHAR(250)`, `recipient_firstname VARCHAR(100)`, `recipient_lastname VARCHAR(100)`
  - `recipient_address_line1 VARCHAR(200)`, `recipient_address_line2 VARCHAR(200)`
  - `recipient_zip_code VARCHAR(10)`, `recipient_city VARCHAR(100)`
  - `recipient_country_name VARCHAR(100)` (frozen text; NOT FK)
  - `recipient_person_club_member_number VARCHAR(20)`
- `delivery_information VARCHAR(250)` (PII catalog), `additional_information VARCHAR(250)`
- `delivery_number INTEGER NULL` — reshape from legacy VARCHAR
- `delivered_on TIMESTAMPTZ NULL` + `CHECK (delivered_on IS NULL OR delivered_on <= now() + INTERVAL '1 day')`
- `batch_id BIGINT NOT NULL DEFAULT 0` — per-club booking-batch group key (operational sequence; NOT aggregate UUID per ADR 0019 escape hatch; column comment documents)
- Audit + soft-delete
- `UNIQUE (operating_club_id, delivery_number) WHERE delivery_number IS NOT NULL AND deleted_on IS NULL`
- `CHECK (process_state_id <> 20 OR delivery_number IS NOT NULL)` — Booked must carry number
- `CHECK (process_state_id <> 20 OR (recipient_lastname IS NOT NULL AND recipient_firstname IS NOT NULL))` — Booked must have recipient snapshot

**`delivery_item`** (`DeliveryItem.cs:13-66`):
- `id uuid PRIMARY KEY` (internal; no prefix)
- `operating_club_id uuid NOT NULL` (denormalized from delivery)
- `delivery_id uuid NOT NULL → delivery(id) CASCADE`
- `position INTEGER NOT NULL` + `CHECK (position >= 1)` + `UNIQUE (delivery_id, position) WHERE deleted_on IS NULL`
- `article_id uuid NOT NULL → article(id) RESTRICT` (forward addition; invoice integrity preserved by snapshot)
- `article_number VARCHAR(50) NOT NULL` — frozen snapshot from `article.article_number` at booking
- `item_text VARCHAR(250)` (PII catalog), `additional_information VARCHAR(250)`
- `quantity NUMERIC(12,4) NOT NULL` + `CHECK (>= 0)` (zero allowed for complimentary line items per legacy)
- `unit_price NUMERIC(12,4) NOT NULL DEFAULT 0` + `CHECK (>= 0)` (forward addition; legacy has no unit_price)
- `discount_in_percent INTEGER NOT NULL DEFAULT 0` + `CHECK (BETWEEN 0 AND 100)`
- `unit_type_code VARCHAR(50) NOT NULL` — frozen snapshot from `accounting_unit_type.code`
- `total_amount NUMERIC(14,4) GENERATED ALWAYS AS (quantity * unit_price * (100 - discount_in_percent) / 100.0) STORED`
- Audit + soft-delete

**`delivery_creation_test`** (`DeliveryCreationTest.cs:22-65`):
- `id uuid PRIMARY KEY` (aggregate root, `dct` prefix)
- `operating_club_id uuid NOT NULL → club(id) RESTRICT`
- `flight_id uuid NOT NULL → flight(id) CASCADE`
- `is_active BOOLEAN NOT NULL DEFAULT true`
- `test_name VARCHAR(250) NOT NULL`
- `description TEXT` (PII catalog)
- `expected_delivery jsonb NOT NULL` — snapshot of full `DeliveryDetails` graph (recipient + flight info + items + info fields)
- `expected_matched_filter_ids BIGINT[] NOT NULL DEFAULT '{}'` — array of `accounting_rule_filter.id` values; NOT FK-enforced (deleted filter is legitimate regression signal)
- `must_not_create_delivery_for_flight BOOLEAN NOT NULL DEFAULT false` — flips assertion polarity
- **9 ignore-on-compare boolean flags** (`DeliveryCreationTest.cs:39-55`): `ignore_recipient_name`, `ignore_recipient_address`, `ignore_recipient_person_id`, `ignore_recipient_club_member_number`, `ignore_delivery_information`, `ignore_additional_information`, `ignore_item_positioning`, `ignore_item_text`, `ignore_item_additional_information` — all `BOOLEAN NOT NULL DEFAULT false`
- **5 last_test_* result columns**: `last_test_run_on TIMESTAMPTZ NULL`, `last_test_successful BOOLEAN NULL`, `last_test_result_message TEXT`, `last_test_created_delivery jsonb NULL`, `last_test_matched_filter_ids BIGINT[] NULL`
- Audit + soft-delete
- `UNIQUE (operating_club_id, flight_id) WHERE deleted_on IS NULL`

**`delivery_creation_test_item`** — **forward-looking, NOT in legacy**:
Story prompt requests this table for structural per-line breakout of `expected_delivery` jsonb (admin query "find all tests expecting article X"). Recommend ship; alternative is jsonb-only (Open Q3).
- `id uuid PRIMARY KEY`, `operating_club_id uuid NOT NULL` (denormalized)
- `delivery_creation_test_id uuid NOT NULL → delivery_creation_test(id) CASCADE`
- `position INTEGER NOT NULL` + `CHECK (>= 1)`
- `article_number VARCHAR(50) NOT NULL`, `item_text VARCHAR(250)`, `additional_information VARCHAR(250)`
- `quantity NUMERIC(12,4) NOT NULL`, `unit_price NUMERIC(12,4) NULL`, `unit_type_code VARCHAR(50) NOT NULL`, `discount_in_percent INTEGER NOT NULL DEFAULT 0`
- Minimal audit; no soft-delete (snapshot rows)

**`club_delivery_number_counter`** — operational counter table (13th):
- `operating_club_id uuid PRIMARY KEY → club(id) ON DELETE CASCADE`
- `next_number INTEGER NOT NULL DEFAULT 1`
- `modified_on TIMESTAMPTZ NOT NULL DEFAULT now()`
- Service-layer allocator at S-064: `UPDATE club_delivery_number_counter SET next_number = next_number + 1 WHERE operating_club_id = ? RETURNING next_number - 1`

### SQL `COMMENT ON COLUMN` for forensic clarity

```sql
COMMENT ON COLUMN aircraft_reservation.id IS
  'UUID v7. Aggregate root (ADR 0018). External form: arv_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN planning_day.id IS
  'UUID v7. Aggregate root. External form: pln_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN accounting_rule_filter.id IS
  'UUID v7. Aggregate root. External form: arf_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN delivery.id IS
  'UUID v7. Aggregate root. External form: dlv_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN delivery_creation_test.id IS
  'UUID v7. Aggregate root. External form: dct_<crockford-base32>. See ADR 0019.';

COMMENT ON COLUMN aircraft_reservation.aircraft_id IS
  'Cross-tenant FK per 2026-05-16 Aircraft-cross-tenant amendment. FK loads NOT @TenantId-filtered. Service layer (S-026/S-064) enforces "may operating_club reserve this aircraft?" via owner / charter / public-rental check. Audit event carries cross_tenant: true when aircraft_reservation.operating_club_id != aircraft.owner_club_id.';

COMMENT ON COLUMN delivery.recipient_lastname IS
  'Frozen snapshot at invoice booking per Swiss OR Art. 957a (10-year retention). NEVER re-resolve from recipient_person_id. DSAR exempt once process_state_id >= 20.';
-- Same comment shape on the 8 other recipient_* columns.

COMMENT ON COLUMN delivery.delivery_number IS
  'Per-club gap-free invoice number per Swiss OR Art. 957a. Assigned at Book transition only. Hard DELETE forbidden once non-NULL (soft-delete via deleted_on). Gap-detection report at S-027.';

COMMENT ON COLUMN delivery.process_state_id IS
  'State machine: 10=Prepared, 20=Booked (terminal-on-mutation, gap-free numbering), 30=Error (retryable), 99=Cancelled. Reshape from legacy flight.process_state_id + delivery.is_further_processed; see S-016 cutover mapping.';

COMMENT ON COLUMN accounting_rule_filter.filter_config IS
  'jsonb predicate bag. Engine reads typed keys per filter_type_id; allow-list validated at S-064 write path. Jackson default-typing DISABLED globally; NEVER deserialize polymorphic types from this column (A03 injection mitigation). PII redaction: pii_blob: true.';

COMMENT ON COLUMN delivery_item.article_number IS
  'Frozen snapshot from article.article_number at booking. Invoice integrity per Swiss OR Art. 957a.';

COMMENT ON COLUMN delivery_item.total_amount IS
  'GENERATED ALWAYS AS (quantity * unit_price * (100 - discount_in_percent) / 100.0) STORED. Re-computation drift impossible.';

COMMENT ON COLUMN delivery.batch_id IS
  'Operational sequence for batch-cancel via DeliveryBatchDeleteRequest. NOT an aggregate UUID (ADR 0019 escape hatch for operational counters). Per-club via UNIQUE constraint or scoped service-layer numbering.';
```

### Index strategy (per-table)

```sql
-- delivery
CREATE INDEX ix_delivery_club_state_date          ON delivery (operating_club_id, process_state_id, delivered_on DESC);
CREATE UNIQUE INDEX ux_delivery_club_number_partial ON delivery (operating_club_id, delivery_number) WHERE delivery_number IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_delivery_flight                   ON delivery (flight_id) WHERE flight_id IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_delivery_club_batch               ON delivery (operating_club_id, batch_id);
CREATE INDEX ix_delivery_recipient_person         ON delivery (operating_club_id, recipient_person_id) WHERE recipient_person_id IS NOT NULL;

-- delivery_item
CREATE INDEX ix_delivery_item_delivery            ON delivery_item (delivery_id) INCLUDE (article_id, article_number, quantity, unit_price, total_amount);
CREATE UNIQUE INDEX ux_delivery_item_delivery_pos ON delivery_item (delivery_id, position) WHERE deleted_on IS NULL;

-- aircraft_reservation (GiST + B-tree)
CREATE INDEX ix_ar_aircraft_range_gist            ON aircraft_reservation USING gist (aircraft_id, reservation_range) WHERE deleted_on IS NULL;
CREATE INDEX ix_ar_club_start_end                 ON aircraft_reservation (operating_club_id, reservation_start, reservation_end) WHERE deleted_on IS NULL;
CREATE INDEX ix_ar_pilot                          ON aircraft_reservation (pilot_person_id, reservation_start DESC) WHERE pilot_person_id IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_ar_location                       ON aircraft_reservation (operating_club_id, location_id, reservation_start);

-- planning_day
CREATE UNIQUE INDEX ux_planning_day_club_date_loc ON planning_day (operating_club_id, planning_date, location_id) WHERE deleted_on IS NULL;

-- planning_day_assignment
CREATE INDEX ix_pda_planning_day                  ON planning_day_assignment (planning_day_id);
CREATE INDEX ix_pda_person                        ON planning_day_assignment (assigned_person_id, planning_day_id) WHERE deleted_on IS NULL;
CREATE INDEX ix_pda_club_person_type              ON planning_day_assignment (operating_club_id, assigned_person_id, assignment_type_id);
CREATE UNIQUE INDEX ux_pda_unique                 ON planning_day_assignment (planning_day_id, assigned_person_id, assignment_type_id) WHERE deleted_on IS NULL;

-- accounting_rule_filter
CREATE INDEX ix_arf_club_active_sort              ON accounting_rule_filter (operating_club_id, is_active, sort_indicator) WHERE deleted_on IS NULL;  -- THE hot index
CREATE INDEX ix_arf_club_type_sort                ON accounting_rule_filter (operating_club_id, filter_type_id, sort_indicator) WHERE is_active = true AND deleted_on IS NULL;
CREATE INDEX ix_arf_filter_config_gin             ON accounting_rule_filter USING gin (filter_config jsonb_path_ops);  -- admin search only

-- delivery_creation_test
CREATE UNIQUE INDEX ux_dct_club_flight_partial    ON delivery_creation_test (operating_club_id, flight_id) WHERE deleted_on IS NULL;
CREATE INDEX ix_dct_club_created                  ON delivery_creation_test (operating_club_id, created_on DESC);

-- delivery_creation_test_item
CREATE INDEX ix_dcti_test                         ON delivery_creation_test_item (delivery_creation_test_id);
```

### FK cascade rules (key entries)

| FK | ON DELETE | Rationale |
|---|---|---|
| `aircraft_reservation.operating_club_id → club.id` | RESTRICT | Cannot delete tenant with reservations |
| `aircraft_reservation.aircraft_id → aircraft.id` | RESTRICT | Preserve history; cross-tenant per amendment |
| `aircraft_reservation.pilot_person_id → person.id` | RESTRICT | Cross-tenant; preserve history |
| `aircraft_reservation.second_crew_person_id → person.id` | SET NULL | Cross-tenant; optional |
| `aircraft_reservation.location_id → location.id` | RESTRICT | Cross-tenant shared |
| `aircraft_reservation.reservation_type_id → aircraft_reservation_type.id` | RESTRICT | |
| `planning_day.location_id → location.id` | RESTRICT | |
| `planning_day_assignment.planning_day_id → planning_day.id` | CASCADE | Internal entity |
| `planning_day_assignment.assigned_person_id → person.id` | RESTRICT | Cross-tenant; preserve history |
| `planning_day_assignment.assignment_type_id → planning_day_assignment_type.id` | RESTRICT | |
| `accounting_rule_filter.filter_type_id → accounting_rule_filter_type.id` | RESTRICT | |
| `accounting_rule_filter.accounting_unit_type_id → accounting_unit_type.id` | RESTRICT | |
| `delivery.operating_club_id → club.id` | RESTRICT | |
| `delivery.flight_id → flight.id` | RESTRICT | Invoice trail integrity |
| `delivery.recipient_person_id → person.id` | SET NULL | Cross-tenant; snapshot survives |
| `delivery_item.delivery_id → delivery.id` | CASCADE | Internal |
| `delivery_item.article_id → article.id` | RESTRICT | Invoice integrity |
| `delivery_creation_test.flight_id → flight.id` | CASCADE | Harness payload |
| `delivery_creation_test.operating_club_id → club.id` | RESTRICT | |
| `delivery_creation_test_item.delivery_creation_test_id → delivery_creation_test.id` | CASCADE | Internal |
| `aircraft_reservation_type.operating_club_id → club.id` | RESTRICT | |
| `planning_day_assignment_type.operating_club_id → club.id` | RESTRICT | |
| `club_delivery_number_counter.operating_club_id → club.id` | CASCADE | Counter dies with club |

### `tenant-rules.yaml` updates

```yaml
# Reclassify (legacy carries ClubId):
AircraftReservationTypes: { kind: tenant-scoped, target_entity: AircraftReservationType, tenant_column: operating_club_id, emits_audit: true }
PlanningDayAssignmentTypes: { kind: tenant-scoped, target_entity: PlanningDayAssignmentType, tenant_column: operating_club_id, emits_audit: true }

# Keep SYSTEM_GLOBAL reference (no legacy ClubId):
AccountingRuleFilterTypes: { kind: reference, target_entity: AccountingRuleFilterType }
AccountingUnitTypes:       { kind: reference, target_entity: AccountingUnitType }

# Extend existing entries:
AircraftReservations:
  ride_through_targets: [Persons, Aircrafts]   # Aircrafts added per 2026-05-16 amendment
  pii_columns: [info]
  pii_ride_through: [pilot_person_id, second_crew_person_id, aircraft_id]
  preconditions:
    - "S-022 service layer enforces Aircraft.canBeReservedBy(operating_club_id) at create/update"
    - "Audit event carries cross_tenant: true when aircraft.owner_club_id != aircraft_reservation.operating_club_id"

PlanningDays:
  pii_columns: [info]
  emits_audit: true

PlanningDayAssignments:
  kind: tenant-scoped   # denormalized operating_club_id
  ride_through_targets: [Persons]
  pii_columns: [info]
  pii_ride_through: [assigned_person_id]
  emits_audit: true

AccountingRuleFilters:
  emits_audit: true
  pii_columns: [filter_config]   # whole-jsonb redaction (matched_club_member_numbers etc.)
  pii_blob: true

Deliveries:
  emits_audit: true
  ride_through_targets: [Persons, Flights]
  pii_columns: [recipient_name, recipient_firstname, recipient_lastname, recipient_address_line1, recipient_address_line2, recipient_zip_code, recipient_city, recipient_country_name, recipient_person_club_member_number, delivery_information, additional_information]
  pii_ride_through: [recipient_person_id]
  fadp_dsar_retention_exempt_when: "process_state_id >= 20"  # OR Art. 957a override

DeliveryItems:
  kind: tenant-scoped   # denormalized
  emits_audit: true
  pii_columns: [item_text, additional_information]
  preconditions:
    - "Denormalize operating_club_id from delivery.operating_club_id"

# New entries:
DeliveryCreationTest:
  kind: tenant-scoped
  target_entity: DeliveryCreationTest
  tenant_column: operating_club_id
  emits_audit: true
  ride_through_targets: [Flights]
  pii_columns: [description, last_test_result_message, expected_delivery, last_test_created_delivery]
  pii_blob: true   # jsonb columns may carry recipient + member-number snapshots

DeliveryCreationTestItem:
  kind: tenant-scoped   # denormalized
  target_entity: DeliveryCreationTestItem
  emits_audit: false

ClubDeliveryNumberCounter:
  kind: tenant-scoped   # PK is operating_club_id itself
  target_entity: ClubDeliveryNumberCounter
  emits_audit: false
```

### Module layout

- New: `next/server/src/main/resources/db/migration/V4__reservations_planning_accounting.sql` (~600-700 lines).
- Edit: `next/database/tenant-rules.yaml` (12 entries + reclassifications + Aircraft-cross-tenant ride-through addition).
- New: `next/server/src/test/java/ch/alpenflight/server/migration/ReservationsBaselineIntegrationTest.java` (~55 tests). (Package is `ch.alpenflight` per the S-128 FLS → AlpenFlight technical rebrand, not the legacy `ch.fls`.)
- Extend: `MigrationFolderConventionsTest`, `FlywayBootstrapIntegrationTest`, `TenantCatalogConsistencyTest`.
- Extend: `next/server/src/test/resources/reference-seeds-canonical-uuids.json` (canonical UUIDs for `accounting_rule_filter_type` ×10 + `accounting_unit_type` ≥3).
- Extend: `next/server/src/test/resources/scripts/generate-canonical-uuids.java` (committed generator).
- Edit: `next/server/src/test/resources/forbidden-migration-patterns.txt` — allowlist new reference seeds; deny `INSERT INTO delivery|delivery_item|aircraft_reservation|planning_day|planning_day_assignment|accounting_rule_filter|delivery_creation_test*`.

### Alternatives considered

- **Chosen — single V4 migration with 12 tables + counter + all aggregate roots + cross-tenant aircraft FK.** FK graph crosses 3 clusters; splitting forces fake bridge migrations. Single transactional unit.
- **Chosen — `filter_config` jsonb + `filter_type_id` discriminator** (per story Notes line 33). Legacy 30+ predicate columns are mostly NULL per filter; jsonb collapses cleanly. Per-discriminator typed shape validated at S-064; GIN index for admin search only (engine reads filter_config wholesale + interprets in Java).
- Rejected — per-type tables for AccountingRuleFilter. Fragments rules engine code path; legacy + S-064 instantiate `Rule` polymorphically from one base table.
- Rejected — `EXCLUDE USING gist (aircraft_id WITH =, reservation_range WITH &&)` for no-overlap on aircraft_reservation. Multiple legitimate-overlap business rules (maintenance vs. flight; multi-pilot; charter exemption); service layer (S-064) handles.
- Rejected — Postgres ENUM for `delivery.process_state_id`. SMALLINT + CHECK + lookup table at S-064 is more flexible (operator-editable display labels) without DDL migration.
- Rejected — `delivery.delivery_number VARCHAR` (legacy parity). Reshape to INTEGER for Swiss OR Art. 957a gap-free integer numbering + counter table; legacy text prefix lives on `club_extension` (S-012) or as `delivery.legacy_delivery_number_text VARCHAR(50)` parity column at S-016.
- Rejected — DB-level `EXCLUDE` or trigger to enforce `flight.operating_club_id == delivery.operating_club_id`. Cross-row CHECK with subquery not allowed in Postgres; service layer (S-022) enforces.
- Rejected — hard-DELETE on Booked deliveries. Swiss OR Art. 957a requires retention; soft-delete only; revoke DELETE on `delivery` from `app_runtime` role (S-019 ops config).
- Considered — `delivery_creation_test_item` as relational sibling vs jsonb-only. Recommend ship the table for admin query support; S-064 implementer may revisit.

## Edge cases & hidden requirements

### Per-AC edge cases

**AC1 (12 tables + counter):**
- `delivery_creation_test_item` is **forward-looking** — NOT in legacy (legacy stores items inside `ExpectedDeliveryDetails` JSON). Migration header documents.
- `aircraft_reservation_type` + `planning_day_assignment_type` reclassified TENANT_SCOPED (legacy `ClubId NOT NULL`).
- `accounting_rule_filter_type` + `accounting_unit_type` stay SYSTEM_GLOBAL (legacy has no `ClubId`).

**AC2 (UUID PKs):**
- All PKs `uuid`; all FKs `uuid`; tenant column `operating_club_id` is `uuid`.
- `delivery_creation_test.expected_matched_filter_ids` is `BIGINT[]` (Postgres array, NOT FK-enforced).

**AC3 (Delivery state machine reshape):**
- Legacy `Delivery.cs:13-95` has NO `process_state_id`; state lives on `flight.process_state_id` + `delivery.is_further_processed`. Promote to first-class on Delivery: `SMALLINT NOT NULL CHECK IN (10, 20, 30, 99)`.
- Migration header documents S-016 cutover mapping: `flight.process_state_id = 50 → delivery.process_state_id = 10`; `45 → 30`; `60 → 20`; `is_further_processed = true ↔ 20`.
- "Booked = terminal-on-mutation" enforced at S-064 service layer (not DB CHECK).

**AC4 (filter_config jsonb):**
- Per-discriminator typed shape; allow-list per `filter_type_id` validated at S-064.
- Jackson default-typing DISABLED globally; column comment forbids polymorphic deserialization (A03 mitigation).
- Engine loads jsonb wholesale + interprets in Java; GIN index serves admin search only.

**AC5 (DeliveryCreationTest):**
- `expected_delivery` is the full `DeliveryDetails` graph (recipient + flight info + items + info), not just items.
- 9 `ignore_*` boolean knobs control diff semantics (legacy parity).
- `expected_matched_filter_ids BIGINT[]` NOT FK-enforced.
- 5 `last_test_*` result fields for diff display.
- **Data origin: operator-driven via admin UI; migration does NOT seed.** Per-club seeds via S-016 cutover from legacy data.

### Hidden requirements (promoted)

- `club_delivery_number_counter` operational table (13th).
- `CREATE EXTENSION IF NOT EXISTS btree_gist` for composite GiST index on aircraft_reservation.
- AircraftReservation cross-tenant aircraft FK column comment (per 2026-05-16 amendment).
- `tenant-rules.yaml` reclassifications + PII catalog extension + Aircrafts added to ride_through_targets on AircraftReservations.
- `legacy_int_id SMALLINT UNIQUE` on `accounting_rule_filter_type` + `accounting_unit_type` for S-016 cutover.
- Reference-data canonical UUIDs committed in `reference-seeds-canonical-uuids.json` + generator script.
- Migration header sections: state-machine reshape; delivery_number VARCHAR→INTEGER reshape; filter_config 30-column→jsonb reshape; AircraftReservation cross-tenant aircraft FK; forward-looking unit_price + total_amount + delivery_creation_test_item.
- SQL `COMMENT ON COLUMN` on 5 aggregate-root IDs + 9 frozen-recipient columns + delivery_number gap-free comment + filter_config Jackson hardening + aircraft_id cross-tenant comment + batch_id operational-sequence-not-aggregate comment + total_amount generated-stored comment.
- `forbidden-migration-patterns.txt` extension (allowlist new reference seeds; deny INSERT into app tables).
- DSAR retention exemption documented in tenant-rules.yaml: `Deliveries.fadp_dsar_retention_exempt_when: "process_state_id >= 20"` (OR Art. 957a override).

### Scope clarifications

**In:** 12 tables + `club_delivery_number_counter` + `btree_gist` extension + indexes (GiST, GIN, B-tree per grid) + FKs + CHECK constraints + reference-data seeds (canonical UUIDs + legacy_int_id) + `tenant-rules.yaml` updates (12 entries + reclassifications + Aircrafts ride-through addition) + 5 aggregate-root column comments + frozen-recipient column comments + state-machine column comment + jsonb-hardening column comment + cross-tenant aircraft_id column comment + `ReservationsBaselineIntegrationTest` (~55 tests) + `TenantCatalogConsistencyTest` extensions.

**Out:** JPA entities + `@UuidV7` annotation + `FlsUuidV7Generator` wiring → S-022. `@TenantId` filter wiring → S-022. Strong-typed ID records + Crockford codec → S-022. Aggregate-method invariant enforcement (Delivery.book(), state-machine transitions, AircraftReservation.create() use-rights check) → S-022/S-064. Audit log table + AOP advice → S-027. Rules engine implementation (Rule polymorphism + DeliveryItemRulesEngine + FlightTime decrement loop + IgnoreFlightRulesEngine) → S-064. Per-club delivery numbering allocator implementation → S-064. Delivery PDF rendering → S-099. Email export job → S-066/S-090. Legacy data cutover → S-016. PostGIS reshape (none applicable here — Location/Aircraft already in S-013). DSAR cross-club cascade → S-051. `keycloak_sub` backfill → S-052.

### Things not the right shape

- AC frontmatter previously said `delivery.process_state_id enum (Prepared/Booked/Error)` — refinement adds `Cancelled` (value 99) for state-machine completeness.
- Story prompt's AC line "`DeliveryItem` with `article_id` + `quantity` + `unit_price` + `total`" — `unit_price` + `total_amount` are forward-looking (NOT in legacy `DeliveryItem.cs`); migration header documents.
- `delivery_creation_test_item` table — forward-looking; legacy stores items inside JSON.
- `adr_refs: [0001, 0002, 0003, 0007, 0008, 0018, 0019]
refined: true
refined_at: 2026-05-16
refined_speculative: false
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]` — refinement adds 0001/0007/0018/0019.
- Story prompt's "DeliveryCreationTest references a flight_id + a JSON snapshot of expected delivery items" — refinement clarifies: snapshot is the full `DeliveryDetails` graph (recipient + flight info + items + info fields), with 9 `ignore_*` boolean knobs + `expected_matched_filter_ids` array + 5 `last_test_*` result columns.
- Story prompt's "model the predicates as columns where natural, JSON for the bag of options" — refinement commits to `filter_config jsonb` (per Notes line 33); action columns remain structural (`sort_indicator`, `stop_rule_engine_when_applied`, `is_charged_to_club_internal`, `article_target`, `recipient_target`).

## Security plan

### Threat model

| # | Threat | Severity | Mitigation |
|---|---|---|---|
| (a) | Cross-tenant Delivery tampering — Club A POSTs Delivery with flight_id / recipient_person_id from Club B | High | `delivery.operating_club_id NOT NULL` + `@TenantId`; service-layer re-verify Flight under tenant filter; S-026 PersonClub membership check for recipient_person_id |
| (b) | Delivery price manipulation post-Booked | High (financial integrity) | State-machine in S-064 rejects DML on delivery_item.unit_price/quantity + delivery.recipient_* once Booked; audit log captures every attempt |
| (c) | `accounting_rule_filter.filter_config` jsonb injection / Jackson polymorphic-deserialization gadget | Critical | Globally disable Jackson default-typing; ban `@JsonTypeInfo(use=Id.CLASS)` via ArchUnit rule; allow-list `filter_type_id → JSON Schema` map at S-064; SQL column comment forbids; ADMIN-only mutation |
| (d) | Delivery recipient snapshot mutation post-issuance | High (legal — OR Art. 957a) | 9 frozen-recipient columns directly on row; NEVER re-resolve from recipient_person_id; aggregate-method `Delivery.book()` snapshots; CHECK `(process_state_id <> 20 OR recipient_lastname IS NOT NULL)` |
| (e) | `delivery_creation_test.expected_delivery` jsonb PII exposure | Med | Tenant-scoped; pii_blob: true; audit-blob redacts whole column; READER denied (ADMIN/FLIGHT_OPS only) |
| (f) | `planning_day_assignment.assigned_person_id` cross-tenant Person FK tampering | Med | Sacred-cow shape; service-layer PersonClub membership check at S-064 |
| (g) | **AircraftReservation cross-club aircraft authorization (NEW per 2026-05-16 amendment)** | High | Aircraft is cross-tenant per amendment; FK loads NOT @TenantId-filtered. S-022/S-064 service layer calls `Aircraft.canBeReservedBy(clubId)` consulting owner_club_id + charter agreements + public-rental allowlist. Audit event with `cross_tenant: true` marker when `aircraft.owner_club_id != aircraft_reservation.operating_club_id` |
| (h) | AircraftReservation availability spoofing / denial-of-booking | Med | Mitigated by (g) authorization check + soft-delete-only + audit log per create/update/cancel |
| (i) | Hibernate jsonb deserialization gadgets (filter_config, expected_delivery, last_test_created_delivery) | Critical | Same as (c) — globally disable default-typing; ArchUnit ban polymorphic typing |
| (j) | DSAR vs invoice-retention conflict (Swiss tax 10-year retention) | High (legal) | Recipient snapshot columns DSAR-exempt once `process_state_id >= 20`; documented in column comments + `tenant-rules.yaml.Deliveries.fadp_dsar_retention_exempt_when` |
| (k) | Per-club Delivery numbering — gap-free invariant (Swiss OR Art. 957a) | High (legal) | UNIQUE (operating_club_id, delivery_number); CHECK > 0; DELETE revoked at DB role on `delivery`; soft-delete only on numbered rows; gap-detection report at S-027 |
| (l) | `accounting_rule_filter` config drift across deliveries | High (financial blast radius) | HIGHEST audit priority; full before/after JSON; CLUB_ADMIN-only; soft-delete only; mandatory change_reason field at S-064 |
| (m) | Free-text PII spill on `aircraft_reservation.info`, `delivery_item.item_text`, `delivery.delivery_information`, etc. | Med | Classify in pii_columns; audit-blob redaction; column length caps ≤ 250 |
| (n) | UUID v7 timestamp leak in error messages | Low | Per ADR 0019; documented |
| (o) | Aggregate-prefix reveals entity type | Very Low | By design |
| (p) | `delivery.batch_id` cross-tenant collision | Low | `UNIQUE (operating_club_id, batch_id) WHERE batch_id IS NOT NULL` or per-club service-layer scope |
| (q) | `delivery_creation_test.flight_id` cross-tenant FK leak | Low | Service-layer check: flight.operating_club_id == test.operating_club_id |
| (r) | Reference table tampering | Med | ADMIN-only for per-club types; `migrator`-only for system-global |
| (s) | Negative quantity / discount-bomb fraud on delivery_item | High | Schema CHECKs: `quantity >= 0`, `unit_price >= 0`, `discount BETWEEN 0 AND 100`; total_amount is GENERATED STORED (no client-side override) |

### Authorization

- **DB-role split** (continues S-012/S-013): `migrator` owns DDL + reference-data INSERT on `accounting_rule_filter_type`, `accounting_unit_type`. `app_runtime` gets DML on tenant-scoped tables; **DELETE revoked on `delivery` + `delivery_item`** (soft-delete only); DELETE allowed on `aircraft_reservation`, `planning_day*`, `delivery_creation_test*`.
- **App-layer authz at aggregate-method boundary** (per ADR 0018):
  - `Delivery.create` / `update` (Prepared only): `FLIGHT_OPS` or `CLUB_ADMIN`; `@PreAuthorize("hasAnyRole('FLIGHT_OPS','CLUB_ADMIN')")`.
  - `Delivery.book` (state 10→20): `CLUB_ADMIN` only; HIGHEST audit; emits state_transitioned + booked events.
  - `Delivery.cancel` / `void`: `CLUB_ADMIN`; if Booked, void emits dedicated event.
  - `accounting_rule_filter.*` mutations: `CLUB_ADMIN` only; highest audit priority.
  - `AircraftReservation.create` / `update`: `PILOT`/`FLIGHT_OPS`/`CLUB_ADMIN`; **NEW per amendment** service-layer `Aircraft.canBeReservedBy(operating_club_id)` check.
  - `PlanningDay.*` + `planning_day_assignment.*`: `FLIGHT_OPS`/`CLUB_ADMIN`; PersonClub-membership check.
  - `DeliveryCreationTest.run`: `CLUB_ADMIN` only; flight_id same-tenant check.

### Input validation (schema-level)

- `delivery.process_state_id SMALLINT NOT NULL CHECK IN (10, 20, 30, 99)`.
- `delivery.delivery_number INTEGER CHECK (delivery_number IS NULL OR > 0)` + UNIQUE partial.
- `delivery.delivered_on TIMESTAMPTZ NULL` + `CHECK (<= now() + INTERVAL '1 day')`.
- `delivery.batch_id BIGINT NOT NULL` + `UNIQUE (operating_club_id, batch_id) WHERE batch_id IS NOT NULL`.
- `delivery_item.quantity NUMERIC(12,4) NOT NULL CHECK (>= 0)`; `unit_price NUMERIC(12,4) NOT NULL CHECK (>= 0)`; `discount_in_percent INTEGER NOT NULL DEFAULT 0 CHECK (BETWEEN 0 AND 100)`; `position INTEGER NOT NULL CHECK (>= 1)` + UNIQUE per delivery.
- `aircraft_reservation.reservation_end > reservation_start` CHECK.
- `aircraft_reservation.reservation_end <= reservation_start + INTERVAL '30 days'` CHECK (sanity cap; document).
- `planning_day.planning_date BETWEEN '1990-01-01' AND '2100-01-01'` CHECK.
- `planning_day_assignment_type.required_nr_of_assignments INTEGER NOT NULL CHECK (>= 0)`.
- `accounting_rule_filter.sort_indicator INTEGER NOT NULL CHECK (>= 0)`; UNIQUE `(operating_club_id, sort_indicator) WHERE deleted_on IS NULL` (deterministic engine output; Open Q12).
- `accounting_rule_filter.filter_config jsonb NOT NULL DEFAULT '{}'::jsonb`.
- All UUID columns reject malformed input at Postgres type level.

### PII handling

(Full catalog in tenant-rules.yaml block above.)
- **Direct PII frozen snapshot on delivery** (9 cols): `recipient_*`. **DSAR-exempt once Booked** (OR Art. 957a override; documented).
- **Free-text quasi-PII**: `delivery.delivery_information`, `additional_information`; `delivery_item.item_text`, `additional_information`; `aircraft_reservation.info`; `planning_day.info`; `planning_day_assignment.info`.
- **Whole-jsonb PII redaction (pii_blob: true)**: `accounting_rule_filter.filter_config`, `delivery_creation_test.expected_delivery`, `delivery_creation_test.last_test_created_delivery`. Redact via hashing values not keys (preserve structural diff).
- **Cross-tenant PII ride-through FKs**: `delivery.recipient_person_id`, `aircraft_reservation.pilot_person_id`, `second_crew_person_id`, `planning_day_assignment.assigned_person_id`. Audit emits prefixed `psn_<crockford>` form.
- **UUID v7 timestamp exposure**: per ADR 0019; documented in CONVENTIONS.md.

### Audit-log events (forward to S-027)

- `delivery.created/updated/state_transitioned/booked/cancelled/voided/soft_deleted` (state_transitioned + booked are HIGHEST priority; voided requires reason).
- `delivery_item.created/updated/deleted` (HIGHEST priority on price-affecting changes).
- `accounting_rule_filter.created/updated/deleted/reordered/activated/deactivated` — HIGHEST audit priority (rule drift affects all future invoices); full before/after JSON; mandatory `change_reason`.
- `aircraft_reservation.created/updated/deleted/cancelled` (cross_tenant marker per amendment).
- `aircraft_reservation.aircraft_swapped` (sub-event when aircraft_id changes).
- `planning_day.*`, `planning_day_assignment.assigned/removed`.
- `delivery_creation_test.created/updated/deleted/run_completed`.

All `target.id` fields carry prefixed external form (`arv_...`/`dlv_...` etc.).

### Cross-tenant leakage

- 9 TENANT_SCOPED tables via `operating_club_id uuid` (5 aggregate roots + 4 denormalized internal entities + counter). `@TenantId` at S-022 auto-filters.
- Sacred-cow cross-tenant FKs:
  - `delivery.recipient_person_id → person.id`
  - `delivery.flight_id → flight.id` (same-tenant by service-layer invariant)
  - `aircraft_reservation.aircraft_id → aircraft.id` (**NEW per amendment** — Aircraft cross-tenant)
  - `aircraft_reservation.pilot_person_id → person.id`
  - `aircraft_reservation.second_crew_person_id → person.id`
  - `aircraft_reservation.location_id → location.id`
  - `aircraft_reservation.flight_type_id → flight_type.id` (per-club; same-tenant in practice)
  - `planning_day.location_id → location.id`
  - `planning_day_assignment.assigned_person_id → person.id`
  - `delivery_creation_test.flight_id → flight.id`
- S-024 leakage CI parameterized over each. **S-024 refiner must add `aircraft_reservation.aircraft_id` to the cross-tenant FK roster per amendment.**

### OWASP applicability

- A01 Broken Access Control — applies (tenant tampering + cross-tenant aircraft authorization per amendment).
- A02 Cryptographic Failures — applies (free-text PII at rest; FDE at S-019).
- A03 Injection — applies (jsonb Jackson polymorphic-deserialization is the canonical vector; mitigated by globally disabled default-typing + ArchUnit ban).
- A04 Insecure Design — applies (frozen recipient snapshot for invoice integrity; gap-free numbering; soft-delete only on numbered deliveries; aggregate-method boundary narrows mutation surface).
- A05 Security Misconfiguration — `@TenantId` resolver fail-closed; Jackson default-typing off in production config; ArchUnit test asserts.
- A08 Software & Data Integrity — Flyway checksum + CODEOWNERS; gap-free delivery_number; generated total_amount STORED; rule-config drift audit.
- A09 Logging & Monitoring — state-transition + price-line + rule-mutation audit MANDATORY.
- A10 SSRF — N/A.

### Story-specific concerns

- Swiss tax law (OR Art. 957a/958f, MWSTG Art. 70): invoice numbers gap-free per fiscal year per club; recipient + article snapshot at issuance; retention 10 years overrides FADP DSAR.
- CODEOWNERS on `db/migration/**` (covered by S-009); reinforce for security review.
- `filter_config` Jackson hardening — ArchUnit rule in `next/server`: no `@JsonTypeInfo`, no `enableDefaultTyping`, no `activateDefaultTyping`. Asserted at build time.
- AircraftReservation cross-tenant authorization (NEW per 2026-05-16 amendment) — S-022/S-064 owns the `Aircraft.canBeReservedBy(clubId)` check; S-014 only declares the schema (cross-tenant FK + column comment + audit-event marker).
- S-024 leakage CI roster update — `aircraft_reservation.aircraft_id` cross-tenant FK MUST be added per amendment. Flagged for S-024 refiner.

## Test plan

### Coverage contract

**Owns:** 12 tables + counter + indexes + FKs + CHECK constraints + reference-data seeds (canonical UUIDs + legacy_int_id) + `tenant-rules.yaml` deltas + 5 aggregate-root column comments + state-machine CHECK + frozen-recipient columns + generated `total_amount` + GiST index on aircraft_reservation + cross-tenant aircraft_id column comment + `ReservationsBaselineIntegrationTest` (~55 tests) + `TenantCatalogConsistencyTest` extensions.

**Does NOT own:** JPA entities + `@UuidV7` wiring (S-022); `@TenantId` filter (S-022); aggregate-method invariants (S-022/S-064); aggregate prefix codec (S-022); live cross-tenant leakage CI (S-024 — **must add aircraft_reservation.aircraft_id parameter per amendment**); audit-log capture (S-027); rules engine evaluation (S-064); delivery state-machine transition rules (S-064); delivery numbering allocator implementation (S-064); delivery PDF (S-099); email export (S-066/S-090); legacy data cutover (S-016); production-scale perf (S-108); Swiss tax compliance review (operator UAT).

### Specific test cases

**Extensions to `MigrationFolderConventionsTest`:**
- `reservations_planning_accounting_migration_present` — exactly one `V<n>__reservations_planning_accounting.sql`; n >= 4.
- `vN_reservations_planning_accounting_is_non_empty`.

**Extensions to `FlywayBootstrapIntegrationTest`:**
- `current_version_at_least_4_after_s014` (`>=` not `==`; n=4 because chain on `main` is V1/V2/V3 before this story).
- `flyway_history_contains_reservations_planning_accounting_row`.

**New `ReservationsBaselineIntegrationTest`** (~55 tests; shares `PostgresTestContainerLifecycle` + identical `@DynamicPropertySource`):

Shape:
- `all_12_tables_plus_counter_present` (containsExactlyInAnyOrder).
- `all_pk_columns_are_uuid_not_null` (parameterized over 12).
- `all_fk_columns_are_uuid` (parameterized).
- `tenant_scoped_tables_have_operating_club_id_not_null_fk_restrict` (parameterized).

Delivery state machine + uniqueness + recipient snapshot:
- `delivery_process_state_id_check_pins_10_20_30_99` (provoke 999 → SQLSTATE 23514).
- `delivery_unique_per_club_delivery_number_partial` (provoke duplicate → 23505; cross-club same number → success).
- `delivery_has_9_recipient_snapshot_columns` (parameterized over 9 frozen columns; width assertions).
- `delivery_recipient_person_id_set_null_on_person_delete`.
- `delivery_flight_id_restrict_on_flight_delete`.
- `delivery_booked_requires_delivery_number_check` (provoke `process_state_id=20, delivery_number=NULL` → 23514).
- `delivery_booked_requires_recipient_lastname_check`.

Delivery item money math:
- `delivery_item_quantity_unit_price_total_columns_correct_decimal_precision`.
- `delivery_item_total_amount_is_generated_always_stored` (`pg_attribute.attgenerated = 's'`).
- `delivery_item_quantity_nonnegative_check`; `delivery_item_unit_price_nonnegative_check`; `delivery_item_discount_in_range_check`.
- `delivery_item_article_id_restrict_on_article_delete`.
- `delivery_item_position_unique_per_delivery_partial`.

Aircraft reservation (cross-tenant per amendment):
- `aircraft_reservation_end_after_start_check`.
- `aircraft_reservation_two_person_fks_pilot_required_second_crew_nullable`.
- `aircraft_reservation_has_generated_tsrange_column`.
- `aircraft_reservation_gist_index_on_aircraft_range_present` (`pg_indexes` regex `USING gist`).
- `aircraft_reservation_pilot_fk_restrict`; `aircraft_reservation_second_crew_fk_set_null`.
- **`aircraft_reservation_aircraft_id_cross_tenant_column_comment`** (NEW per amendment — `pg_description` contains "cross-tenant").

Planning:
- `planning_day_unique_per_club_date_location_partial`.
- `planning_day_assignment_person_fk_restrict` (sacred-cow cross-tenant).
- `planning_day_assignment_planning_day_fk_cascade`.
- `planning_day_assignment_unique_composite_partial`.
- `planning_day_assignment_has_operating_club_id_denormalized_from_parent`.

Accounting rule filter — JSON config:
- `accounting_rule_filter_filter_config_is_jsonb_not_null`.
- `accounting_rule_filter_filter_config_default_is_empty_object`.
- `accounting_rule_filter_gin_index_on_filter_config_jsonb_path_ops`.
- `accounting_rule_filter_sort_indicator_index_present`.
- `accounting_rule_filter_filter_type_id_fk_restrict`.
- `accounting_rule_filter_jsonb_shape_smoke` (round-trip + jsonb_typeof + `@>` containment).
- `accounting_rule_filter_sort_indicator_unique_per_club_partial` (provoke dup → 23505).

Reference-data seeds:
- `accounting_rule_filter_type_seeded_with_8_canonical_codes` (cite `database/FLSTest/3 insert/3 Insert Static Data.sql`).
- `accounting_unit_type_seeded_with_4_canonical_codes` (MINUTES, SECONDS, LANDINGS, START_OR_FLIGHT).
- `aircraft_reservation_type_NOT_seeded_in_migration` (per-club; operator creates via API).
- `planning_day_assignment_type_NOT_seeded_in_migration`.

DeliveryCreationTest:
- `delivery_creation_test_flight_fk_cascade`.
- `delivery_creation_test_unique_per_club_flight_partial`.
- `delivery_creation_test_expected_delivery_is_jsonb_not_null`.
- `delivery_creation_test_has_9_ignore_boolean_columns` (parameterized).
- `delivery_creation_test_has_5_last_test_result_columns`.
- `delivery_creation_test_expected_matched_filter_ids_is_bigint_array_not_fk`.
- `delivery_creation_test_item_fk_cascade`.

Counter table:
- `club_delivery_number_counter_pk_is_operating_club_id`.
- `club_delivery_number_counter_next_number_default_1`.

Aggregate-root column comments:
- `aggregate_root_column_comments_reference_adr_0019` (parameterized over `aircraft_reservation` arv_, `planning_day` pln_, `accounting_rule_filter` arf_, `delivery` dlv_, `delivery_creation_test` dct_).
- `non_aggregate_root_columns_do_not_carry_prefix_comments` (sample assertion).

**Extensions to `TenantCatalogConsistencyTest`:**
- `every_s014_tenant_scoped_table_has_operating_club_id` (parameterized).
- `every_s014_reference_table_has_no_operating_club_id`.
- `aircraft_reservation_type_reclassified_to_tenant_scoped`.
- `planning_day_assignment_type_reclassified_to_tenant_scoped`.
- **`aircraft_reservation_aircraft_id_is_cross_tenant_ride_through`** (NEW per amendment — yaml `AircraftReservations.ride_through_targets` contains `Aircrafts`).

### Parity strategy

N/A — schema reshape. Reference-seed enum values pinned via legacy-code citations.

### Test data + fixtures

- Shared `PostgresTestContainerLifecycle` + identical `@DynamicPropertySource` → context cache reuse.
- `reference-seeds-canonical-uuids.json` extends S-012/S-013's file with S-014 entries.
- Minimal-graph fixtures per provocation test (raw JDBC + savepoint/rollback; SQLSTATE-based assertions).
- `tenant-rules.yaml` Gradle processTestResources copy task (continues S-012 pattern).

### Coverage gaps (deferred)

- JPA entity correctness → S-022.
- `@TenantId` filter → S-022.
- Aggregate-method invariants → S-022/S-064.
- Aggregate prefix codec → S-022.
- Live cross-tenant leakage CI → S-024 (**add aircraft_reservation.aircraft_id parameter per amendment**).
- Audit-log capture → S-027.
- Delivery state-machine transitions → S-064.
- Rules engine evaluation → S-064.
- Delivery numbering allocator service → S-064.
- Delivery PDF → S-099.
- Email export → S-066/S-090.
- Production-scale perf → S-108.
- Swiss tax compliance review → operator UAT.

### Risks

- V4 ordering collision with S-018 (ShedLock could land between V3 and V4 and push S-014 to V5). Mitigation: implementer reads listing; tests assert `>= 4`.
- Reference-seed UUID immutability (Flyway checksum). Mitigation: committed generator script + JSON pin map + PR review.
- Test boot-time growth. Mitigation: identical `@DynamicPropertySource` → context cache hits.
- Generated-column vs stored-with-CHECK for `total_amount` — pinned as GENERATED STORED; legacy import edge cases may force flip to plain NUMERIC with deferred CHECK (S-016 may revisit).
- Locale-fragile message-text assertions — assert SQLSTATE codes, never message text.
- `btree_gist` extension dependency — explicit `CREATE EXTENSION` at top of migration; test catches omission.
- **Aircraft cross-tenant parameter not in S-024 roster** — load-bearing hand-off to S-024 refiner.

## Performance plan

### Hot paths

- AccountingRuleFilter evaluation per flight write (per-club ordered scan of active filters; called per flight create/update).
- Delivery list per club + state + fiscal year (operator dashboard).
- AircraftReservation conflict detection on submit (range-overlap on aircraft_id).
- AircraftReservation 30-day calendar (per-club window query; called on calendar render).
- PlanningDay 7-day view per club (home dashboard widget).
- DeliveryItem fetch alongside Delivery (always with delivery view).
- Delivery numbering claim via counter table (UPDATE ... RETURNING, sub-10ms).
- Per-pilot upcoming PlanningDayAssignment (cold).

### Required indexes

Full per-table grid in Design notes above. Load-bearing:
- `ix_delivery_club_state_date(operating_club_id, process_state_id, delivered_on DESC)` — primary dashboard.
- `ux_delivery_club_number_partial(operating_club_id, delivery_number) WHERE NOT NULL` — gap-free numbering uniqueness.
- `ix_delivery_item_delivery INCLUDE(article_id, article_number, quantity, unit_price, total_amount)` — index-only DTO assembly.
- `ix_ar_aircraft_range_gist USING gist(aircraft_id, reservation_range) WHERE deleted_on IS NULL` — conflict probe sub-10ms.
- `ix_ar_club_start_end(operating_club_id, reservation_start, reservation_end) WHERE deleted_on IS NULL` — 30-day calendar.
- `ux_planning_day_club_date_loc` — uniqueness + 7-day window.
- `ix_arf_club_active_sort(operating_club_id, is_active, sort_indicator) WHERE deleted_on IS NULL` — THE rules engine hot index.
- `ix_arf_filter_config_gin USING gin(filter_config jsonb_path_ops)` — admin search only.

### N+1 risks (forward S-022)

- Delivery → DeliveryItem always-eager via `@EntityGraph(attributePaths = "items")`; two-phase fetch for paged lists to avoid Cartesian.
- AccountingRuleFilter → AccountingRuleFilterType / AccountingUnitType: `@ManyToOne LAZY` + `@Cache(READ_ONLY)` 24h L2 on type entities; reference cache eliminates per-row DB hit during rule evaluation.
- AircraftReservation → Aircraft (cross-tenant per amendment) / PilotPerson / Location / Type: calendar uses `@EntityGraph` with explicit subgraph; split into Aircraft+Location fetch-join + `@BatchSize(64)` for Persons+Types.
- PlanningDay → PlanningDayAssignment → Person: fetch-join assignment only; `@BatchSize(64)` for Person.
- Delivery → Flight → Aircraft/FlightCrew: avoid eager fetch from Delivery list; carry flight_id scalar only.

### Cartesian / explosion risks

- `Delivery + DeliveryItem + PersonFlightTimeCreditTransaction` two-bag join: never single-query; split.
- Calendar view: `AircraftReservation × Aircraft × Location × 2 Person rows` is all `*ToOne`, safe; adding `assignments` collection would explode.

### Caching

| Entity | Cache | TTL | Reason |
|---|---|---|---|
| `accounting_rule_filter` query cache `(club_id, is_active=true)` | L2 (Caffeine) | 5min | CRITICAL — uncached at 200 filters × 500 flights = 100k row-fetches/club/night; invalidate on rule edit |
| `accounting_rule_filter_type`, `accounting_unit_type`, `aircraft_reservation_type`, `planning_day_assignment_type` | L2 READ_ONLY | 24h | Pure reference data |
| `delivery`, `delivery_item` | **NEVER** | — | Financial state; read-through; explicit `@Cache(usage=NONE)` |
| `aircraft_reservation` | **NEVER** (default) | — | Conflict-detection correctness; 60s SWR on calendar if S-108 measured hot |
| `planning_day`, `planning_day_assignment` | **NEVER** | — | Interactive edit traffic |
| `delivery_creation_test` | **NEVER** | — | Regression harness |

Client-side (Signal Store): AccountingRuleFilter (tab-lifetime, invalidate on save); Delivery list (30s SWR); Delivery detail (no cache); AircraftReservation calendar (60s SWR + optimistic update); PlanningDay 7-day (5min + manual refresh).

### Latency budget (forward S-108)

- Rules-engine filter load per flight (L2-hit): p95 < 5ms; cold p95 < 50ms DB.
- Per-flight rules evaluation in Java: p95 < 20ms in-memory.
- `POST /api/v1/deliveries` (create + items + numbering): p95 < 150ms.
- `GET /api/v1/deliveries?clubId&state&year=` (50 rows): p95 < 100ms.
- `POST /api/v1/aircraft-reservations` (conflict check + insert): p95 < 80ms; probe alone < 30ms DB.
- `GET /api/v1/aircraft-reservations?from&to&clubId` (30-day): p95 < 200ms.
- `GET /api/v1/planning-days?from&to&clubId` (7-day): p95 < 100ms.
- Delivery numbering claim (counter UPDATE...RETURNING): p95 < 10ms.

### Memory

- Schema footprint modest (~10K deliveries/year/club, ~5K reservations/year/club, ~100 rules/club); baseline tables < 50 MB per club at 5-year horizon.
- `accounting_rule_filter.filter_config` jsonb median ~500B per row; full per-club load < 100 KB — fits L2.
- `shared_buffers` 4 GB recommendation (carries from S-013).

### Performance test plan

EXPLAIN canaries on 6 hot queries at 10K-row fixture; force `enable_seqscan = off` / `enable_bitmapscan = off` for assertion:
1. AircraftReservation overlap probe → `Index Scan using ix_ar_aircraft_range_gist`.
2. AircraftReservation 30-day window → `Index Scan using ix_ar_club_start_end`.
3. Delivery list → `Index Scan using ix_delivery_club_state_date`.
4. DeliveryItem by delivery_id → `Index Only Scan using ix_delivery_item_delivery`.
5. AccountingRuleFilter per-club active → `Index Scan using ix_arf_club_active_sort`.
6. PlanningDay 7-day → `Index Scan using ux_planning_day_club_date_loc`.

Pass thresholds: each canary < 20ms on 10K fixture; rule-filter list < 5ms; GiST overlap < 10ms. Production-scale perf deferred to S-108.

### Configuration choices

- `uuid NOT NULL PRIMARY KEY` per ADR 0019.
- Two TIMESTAMPTZ + generated `reservation_range tsrange ... STORED` (chosen over functional GiST).
- `filter_config jsonb` evaluated in Java (NOT predicate-pushed); GIN admin-search-only.
- `delivery_item.total_amount NUMERIC(14,4) GENERATED ALWAYS AS ... STORED`.
- `delivery.process_state_id SMALLINT` + CHECK enum.
- `club_delivery_number_counter` per-row UPDATE...RETURNING for monotonic per-club delivery numbering.

### Open performance questions

- GiST functional vs stored tsrange — chosen stored generated column.
- EXCLUDE constraint for no-overlap — deferred to S-064 (legitimate business overlap rules).
- Delivery numbering counter table vs advisory lock — chosen counter table.
- DeliveryItem.total GENERATED STORED — chosen.
- filter_config evaluation path: Java in-process — locked.

## Open design questions

1. **Delivery state-machine reshape vs legacy parity (Q5):** Recommend reshape (delivery.process_state_id first-class); S-016 cutover maps. Operator confirms.
2. **`delivery.delivery_number` INTEGER vs legacy VARCHAR (Q5b):** Recommend INTEGER + counter table; legacy text format on `club_extension` (S-012) or `delivery.legacy_delivery_number_text VARCHAR(50)` parity column at S-016.
3. **`delivery_creation_test_item` table vs jsonb-only (Q3):** Recommend ship the table for admin query support; alternative is JSON-only (legacy parity). Operator picks.
4. **`accounting_rule_filter.filter_config` jsonb schema validation (Q6):** Recommend app-layer at S-064 (no `pg_jsonschema` extension dependency). Operator confirms.
5. **`accounting_rule_filter.sort_indicator` per-club UNIQUE (Q12):** Recommend yes (deterministic engine output; one-time UX cost at S-064 reorder operations). Operator confirms.
6. **Per-fiscal-year vs perpetual delivery numbering (Q7):** Swiss OR Art. 957a typically expects per-fiscal-year reset. Should `club_delivery_number_counter` carry `(operating_club_id, fiscal_year SMALLINT)` composite key or single-row-per-club? Affects S-064 allocator. **Operator decision.**
7. **`delivery_item.unit_price` + generated `total_amount` forward-looking (Q8):** Confirmed forward addition; S-016 cutover back-fills from article master at migration time. Operator confirms.
8. **`delivery.batch_id` reshape (Q):** Keep as BIGINT operational sequence (chosen, NOT aggregate UUID per ADR 0019 escape hatch). UNIQUE per club. Operator confirms.
9. **`accounting_rule_filter_type.schema jsonb`** column for per-discriminator JSON Schema (optional; allows DB-level validation if `pg_jsonschema` is later adopted). Recommend ship as nullable column (schema spec in column comment); operator decides whether to populate at S-064.
10. **DSAR retention exemption documentation** — `Deliveries.fadp_dsar_retention_exempt_when: "process_state_id >= 20"` in tenant-rules.yaml. Documented in column comments + DPO runbook (S-051). Operator confirms phrasing.

<!-- modernize-refine: end -->

## Implementation notes

Shipped as `V4__reservations_planning_accounting.sql` (~714 lines + 1142-line `ReservationsBaselineIntegrationTest` + extensions to 4 sibling test classes; 206 server tests total all green). Deviations from refinement design (each load-bearing rationale documented in-place; refinement sections preserved verbatim above as the spec record):

- **`tstzrange` instead of `tsrange`.** Refinement design notes specified `tsrange` for `aircraft_reservation.reservation_range`, but `tsrange` takes `TIMESTAMP WITHOUT TIME ZONE` and the implicit `TIMESTAMPTZ::timestamp` cast is session-TZ-dependent → not IMMUTABLE → Postgres rejects in a generated expression. `tstzrange` takes `TIMESTAMPTZ` directly and is immutable; same observable behavior (range overlap semantics identical), correct primitive for our TIMESTAMPTZ-everywhere schema. Migration header §"aircraft_reservation tstzrange + GiST" carries the long-form rationale; test `aircraft_reservation_has_generated_tstzrange_column` pins it.
- **`accounting_rule_filter_type` seeds 8 rows, not 10.** Refinement claimed 10 canonical codes including `DoNotInvoiceFlight` / `StartTaxRule` / etc. — these are CODE strategies under `flsserver/src/FLS.Server.Service/Accounting/Rules/*.cs` that execute when a filter matches, NOT seeded filter-type rows. Legacy DB `database/FLSTest/3 insert/3 Insert Static Data.sql` only seeds 8 actual filter types (RECIPIENT / NO_LANDING_TAX / FLIGHT_TIME / INSTRUCTOR_FEE / ADDITIONAL_FUEL_FEE / LANDING_TAX / VSF_FEE / ENGINE_TIME — `legacy_int_id` 10/20/30/40/50/60/70/80). Pinned to 8 rows here; canonical UUIDs at offsets 18_000..18_007.
- **`ch.alpenflight` package**, not `ch.fls.server`. S-128 (already merged to main) executed the FLS → AlpenFlight technical rebrand; all S-014 test code lives at `next/server/src/test/java/ch/alpenflight/server/migration/`.
- **Migration version V4**, not the refinement's predicted V5 (`V<n+2>`). Chain on `main` is V1 baseline / V2 identity / V3 flights, so S-014 lands at V4. Test assertions use `>= 4` to tolerate future S-018 ShedLock landing between V3 and V4.

Specialist consults: Step 6.7 `maintainability-reviewer` blockers-only — `(none)`. No Step 4.5 mid-implementation specialists needed.

Hand-offs forward:
- **S-024 leakage CI roster** must include `aircraft_reservation.aircraft_id` cross-tenant FK (column comment carries the marker; `tenant-rules.yaml` precondition records the hand-off).
- **S-022** wires JPA entities + `@TenantId` + `@UuidV7` over this schema.
- **S-064** owns rules engine, delivery state-machine transitions, per-club delivery-number allocator (via `club_delivery_number_counter`), filter_config jsonb shape allow-list.
- **S-016** legacy cutover maps `flight.process_state_id + delivery.is_further_processed` → first-class `delivery.process_state_id`; per-club ref tables (`aircraft_reservation_type` + `planning_day_assignment_type`) seeded from legacy data; back-fills `unit_price` on delivery_item rows.
