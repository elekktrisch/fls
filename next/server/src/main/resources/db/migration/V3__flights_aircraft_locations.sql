-- V3__flights_aircraft_locations.sql
--
-- S-013: Flights / Aircraft / Locations baseline (16 domain tables + 5
-- deferred FK columns on club).
--
-- This migration is append-only over S-012's V2. Once V3 is applied to any
-- environment its checksum is locked; never amend — ship V4. Convention is
-- documented in V2 + the no-SHAs / no-amend-shipped-migrations rule in
-- CLAUDE.md.
--
-- ============================================================================
-- ID strategy (ADR 0019 — carries from S-012)
-- ============================================================================
--   * Every PK is `uuid NOT NULL PRIMARY KEY`. Postgres native 16-byte type.
--   * Application generates IDs via Hibernate 7 + uuid-creator
--     UuidCreator.getTimeOrderedEpoch() — wired at S-022.
--   * NO `DEFAULT gen_random_uuid()` on any PK column. The application-owns-
--     generation contract must not be bypassed.
--   * Aggregate-root rows (5 in this migration: flight, aircraft, location,
--     flight_type, article) carry a 3-letter prefix at every external
--     boundary (REST URLs, JSON, structured logs, audit-log target.id).
--     Prefix is a presentation concern — DB stays pure uuid.
--   * Aggregate-internal entities (flight_crew, aircraft_aircraft_state,
--     aircraft_operating_counter, inoutbound_point) carry no prefix.
--
-- ============================================================================
-- Aggregate composition (ADR 0018)
-- ============================================================================
--   * Aggregate roots in S-013: flight (flt_), aircraft (acf_),
--     location (loc_), flight_type (fty_), article (art_).
--   * Internal entities:
--       - under Flight: flight_crew
--       - under Aircraft: aircraft_aircraft_state, aircraft_operating_counter
--       - under Location: inoutbound_point
--   * System-global reference / lookup tables (anemic JPA shape OK):
--       aircraft_type, aircraft_state, location_type, flight_crew_type,
--       flight_process_state, flight_air_state, flight_cost_balance_type.
--
-- ============================================================================
-- Multi-tenancy (ADR 0008 + 2026-05-16 aircraft-cross-tenant amendment)
-- ============================================================================
--   * Tenant discriminator column on TENANT_SCOPED tables is the
--     `operating_club_id uuid` column (renamed from the legacy `ClubId` /
--     `OwnerClubId` per the new-schema convention; S-022 wires
--     Hibernate @TenantId on the matching entity property).
--   * Flight is TENANT_SCOPED via `operating_club_id` set per-flight by the
--     operator (NOT denormalized from aircraft). Charter case:
--       Club B operates Club A's aircraft → flight.operating_club_id = B,
--                                            aircraft.owner_club_id      = A.
--   * Aircraft is CROSS_TENANT (no @TenantId) per 2026-05-16 amendment:
--     `aircraft.owner_club_id uuid NULL → club.id ON DELETE SET NULL`.
--     Nullable so aircraft may be Person-owned, charter-shared, or
--     rental-fleet. Service layer (S-022 / S-026) enforces use-rights.
--   * AircraftAircraftState + AircraftOperatingCounter inherit Aircraft's
--     cross-tenant classification (state/counter records belong to the
--     physical airframe, not to a club's operations).
--   * Location is cross-tenant shared (sacred cow — LSZH used by multiple
--     clubs); SYSTEM_ADMIN-only mutation at the service layer.
--   * FlightType is TENANT_SCOPED via `operating_club_id` (reclassified
--     from S-011's `reference` classification; legacy `FlightType.cs:25`
--     carries `ClubId NOT NULL`).
--   * Article is TENANT_SCOPED via `operating_club_id`.
--
-- ============================================================================
-- Sparse-enum sacred cow (`flight.flight_aircraft_type_id`)
-- ============================================================================
--   * Modelled as `SMALLINT NOT NULL CHECK (flight_aircraft_type_id IN
--     (1, 2, 4))` per legacy `FlightAircraftTypeValue.cs:5-7`
--     (1=Glider, 2=Tow, 4=Motor; value 3 deliberately skipped).
--   * NOT a FK to a lookup table — the value set never changes without code
--     change, and SMALLINT + CHECK saves ~14 bytes/row × ~5M rows ≈ 70 MB.
--   * GliderWithMotor lives on aircraft.aircraft_type_id, NOT on flight.
--
-- ============================================================================
-- aircraft_aircraft_state reshape (legacy → new)
-- ============================================================================
--   * Legacy composite PK: (AircraftId, AircraftStateId, ValidFrom).
--   * New: surrogate `id uuid PRIMARY KEY` + `UNIQUE (aircraft_id, valid_from)`
--     + partial `UNIQUE (aircraft_id) WHERE valid_to IS NULL AND
--     deleted_on IS NULL` (at most one current state per aircraft).
--   * JPA composite-key handling is awkward; the surrogate gives every
--     repository a uniform `findById(UUID)` contract (same pattern as
--     S-012's `person_club`).
--
-- ============================================================================
-- Forward-looking columns on club (deviation from legacy)
-- ============================================================================
--   * `club.default_glider_with_motor_flight_type_id` is NOT present in
--     legacy `Club.cs:77-81`. The new schema adds it so the four
--     flight-aircraft-type axes (Glider, Tow, Motor, GliderWithMotor) all
--     have a defaultable type slot. Operator may drop this column for strict
--     parity if cutover demands.
--
-- ============================================================================
-- Migration ordering
-- ============================================================================
--   1. Reference / lookup tables (no FKs out, FKs in only from S-013 tables).
--   2. Aggregate roots in dependency order: location → flight_type →
--      aircraft → flight → article.
--   3. Aggregate-internal entities: flight_crew, aircraft_aircraft_state,
--      aircraft_operating_counter, inoutbound_point.
--   4. ALTER TABLE club to add 5 deferred FK columns (deferred from S-012's
--      V2 because flight_type + location didn't exist yet).
--   5. Reference-data seeds with fixed canonical UUID v7 literals
--      (generator: next/server/src/test/resources/scripts/GenerateCanonicalUuids.java;
--      ground truth: next/server/src/test/resources/reference-seeds-canonical-uuids.json).
--
-- ============================================================================
-- Reference-table `legacy_int_id` column
-- ============================================================================
--   Every reference row carries a `legacy_int_id SMALLINT UNIQUE` mapping
--   back to the legacy integer code (e.g. AircraftStateKey.OK = 1,
--   FlightProcessState.Locked = 40). S-016 cutover uses this to remap
--   `legacy.AircraftStateId INT → new.aircraft_state_id UUID`. Retain
--   forever (forensic traceability).
-- ============================================================================


-- =============================================================================
-- 1. Reference / lookup tables (no FK out; lightweight; populated via seeds)
-- =============================================================================

CREATE TABLE aircraft_type (
    id                       UUID         NOT NULL PRIMARY KEY,
    code                     VARCHAR(32)  NOT NULL,
    legacy_int_id            SMALLINT     NOT NULL,
    description              VARCHAR(200) NOT NULL,
    has_engine               BOOLEAN,
    requires_towing_info     BOOLEAN,
    may_be_towing_aircraft   BOOLEAN
);
CREATE UNIQUE INDEX ux_aircraft_type_code           ON aircraft_type (code);
CREATE UNIQUE INDEX ux_aircraft_type_legacy_int_id  ON aircraft_type (legacy_int_id);

CREATE TABLE aircraft_state (
    id                  UUID         NOT NULL PRIMARY KEY,
    code                VARCHAR(32)  NOT NULL,
    legacy_int_id       SMALLINT     NOT NULL,
    description         VARCHAR(200) NOT NULL,
    is_aircraft_flyable BOOLEAN      NOT NULL
);
CREATE UNIQUE INDEX ux_aircraft_state_code           ON aircraft_state (code);
CREATE UNIQUE INDEX ux_aircraft_state_legacy_int_id  ON aircraft_state (legacy_int_id);

CREATE TABLE location_type (
    id            UUID         NOT NULL PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL,
    legacy_int_id SMALLINT     NOT NULL,
    description   VARCHAR(200) NOT NULL,
    is_airfield   BOOLEAN      NOT NULL
);
CREATE UNIQUE INDEX ux_location_type_code           ON location_type (code);
CREATE UNIQUE INDEX ux_location_type_legacy_int_id  ON location_type (legacy_int_id);

CREATE TABLE flight_crew_type (
    id            UUID         NOT NULL PRIMARY KEY,
    code          VARCHAR(48)  NOT NULL,
    legacy_int_id SMALLINT     NOT NULL,
    description   VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX ux_flight_crew_type_code           ON flight_crew_type (code);
CREATE UNIQUE INDEX ux_flight_crew_type_legacy_int_id  ON flight_crew_type (legacy_int_id);

CREATE TABLE flight_process_state (
    id            UUID         NOT NULL PRIMARY KEY,
    code          VARCHAR(48)  NOT NULL,
    legacy_int_id SMALLINT     NOT NULL,
    description   VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX ux_flight_process_state_code           ON flight_process_state (code);
CREATE UNIQUE INDEX ux_flight_process_state_legacy_int_id  ON flight_process_state (legacy_int_id);

CREATE TABLE flight_air_state (
    id            UUID         NOT NULL PRIMARY KEY,
    code          VARCHAR(48)  NOT NULL,
    legacy_int_id SMALLINT     NOT NULL,
    description   VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX ux_flight_air_state_code           ON flight_air_state (code);
CREATE UNIQUE INDEX ux_flight_air_state_legacy_int_id  ON flight_air_state (legacy_int_id);

CREATE TABLE flight_cost_balance_type (
    id                          UUID         NOT NULL PRIMARY KEY,
    code                        VARCHAR(48)  NOT NULL,
    legacy_int_id               SMALLINT     NOT NULL,
    description                 VARCHAR(200) NOT NULL,
    person_for_invoice_required BOOLEAN      NOT NULL DEFAULT false,
    is_for_glider               BOOLEAN      NOT NULL DEFAULT false,
    is_for_tow                  BOOLEAN      NOT NULL DEFAULT false,
    is_for_motor                BOOLEAN      NOT NULL DEFAULT false
    -- ck_fcbt_at_least_one_flag removed per ADR 0022 directive 2: the
    -- at-least-one-flag invariant lives on FlightCostBalanceType aggregate
    -- (constructor + flag mutators) at S-058.
);
CREATE UNIQUE INDEX ux_fcbt_code           ON flight_cost_balance_type (code);
CREATE UNIQUE INDEX ux_fcbt_legacy_int_id  ON flight_cost_balance_type (legacy_int_id);


-- =============================================================================
-- 2. Aggregate root: location (CROSS_TENANT shared resource)
-- =============================================================================

CREATE TABLE location (
    id                              UUID          NOT NULL PRIMARY KEY,
    location_name                   VARCHAR(100)  NOT NULL,
    location_short_name             VARCHAR(50),
    country_id                      UUID          NOT NULL,
    location_type_id                UUID          NOT NULL,
    icao_code                       VARCHAR(10),
    latitude                        VARCHAR(10),
    longitude                       VARCHAR(10),
    elevation                       INTEGER,
    elevation_unit_type_id          UUID,
    runway_direction                VARCHAR(50),
    runway_length                   INTEGER,
    runway_length_unit_type_id      UUID,
    airport_frequency               VARCHAR(50),
    description                     TEXT,
    sort_indicator                  INTEGER,
    is_inbound_route_required       BOOLEAN       NOT NULL DEFAULT false,
    is_outbound_route_required      BOOLEAN       NOT NULL DEFAULT false,
    is_fast_entry_record            BOOLEAN       NOT NULL DEFAULT false,
    created_on                      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id              UUID,
    modified_on                     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id             UUID,
    deleted_on                      TIMESTAMPTZ,
    deleted_by_user_id              UUID,
    CONSTRAINT fk_location_country_id
        FOREIGN KEY (country_id)              REFERENCES country (id)            ON DELETE RESTRICT,
    CONSTRAINT fk_location_location_type_id
        FOREIGN KEY (location_type_id)        REFERENCES location_type (id)      ON DELETE RESTRICT,
    CONSTRAINT fk_location_elevation_unit_type_id
        FOREIGN KEY (elevation_unit_type_id)  REFERENCES elevation_unit_type (id) ON DELETE RESTRICT,
    CONSTRAINT fk_location_runway_length_unit_type_id
        FOREIGN KEY (runway_length_unit_type_id) REFERENCES length_unit_type (id) ON DELETE RESTRICT
    -- ck_location_icao_uppercase, ck_location_latitude_shape,
    -- ck_location_longitude_shape removed per ADR 0022 directive 2:
    -- format invariants live on IcaoCode / Latitude / Longitude value
    -- objects at S-068 (Location aggregate).
);
CREATE UNIQUE INDEX ux_location_icao        ON location (icao_code) WHERE icao_code IS NOT NULL;
CREATE        INDEX ix_location_country_type ON location (country_id, location_type_id);
CREATE        INDEX ix_location_name_lower   ON location (LOWER(location_name));


-- =============================================================================
-- 3. Aggregate root: flight_type (TENANT_SCOPED per-club; reclassified)
-- =============================================================================

CREATE TABLE flight_type (
    id                                       UUID          NOT NULL PRIMARY KEY,
    operating_club_id                        UUID          NOT NULL,
    flight_type_name                         VARCHAR(100)  NOT NULL,
    flight_code                              VARCHAR(30),
    instructor_required                      BOOLEAN       NOT NULL DEFAULT false,
    observer_pilot_or_instructor_required    BOOLEAN       NOT NULL DEFAULT false,
    is_check_flight                          BOOLEAN       NOT NULL DEFAULT false,
    is_passenger_flight                      BOOLEAN       NOT NULL DEFAULT false,
    is_solo_flight                           BOOLEAN       NOT NULL DEFAULT false,
    is_for_glider_flights                    BOOLEAN       NOT NULL DEFAULT false,
    is_for_tow_flights                       BOOLEAN       NOT NULL DEFAULT false,
    is_for_motor_flights                     BOOLEAN       NOT NULL DEFAULT false,
    is_flight_cost_balance_selectable        BOOLEAN       NOT NULL DEFAULT false,
    is_coupon_number_required                BOOLEAN       NOT NULL DEFAULT false,
    is_for_aircraft_reservation_type         BOOLEAN       NOT NULL DEFAULT false,
    min_nr_of_aircraft_seats_required        INTEGER,
    created_on                               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                       UUID,
    modified_on                              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                      UUID,
    deleted_on                               TIMESTAMPTZ,
    deleted_by_user_id                       UUID,
    CONSTRAINT fk_flight_type_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES club (id) ON DELETE RESTRICT
    -- ck_flight_type_min_seats_positive removed per ADR 0022 directive 2:
    -- AircraftSeatsCount VO at S-022.
);
CREATE        INDEX ix_flight_type_club        ON flight_type (operating_club_id) WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_flight_type_club_code
    ON flight_type (operating_club_id, flight_code)
    WHERE flight_code IS NOT NULL AND deleted_on IS NULL;


-- =============================================================================
-- 4. Aggregate root: aircraft (CROSS_TENANT per 2026-05-16 amendment)
-- =============================================================================

CREATE TABLE aircraft (
    id                                          UUID          NOT NULL PRIMARY KEY,
    owner_club_id                               UUID,
    aircraft_type_id                            UUID          NOT NULL,
    manufacturer_name                           VARCHAR(100),
    aircraft_model                              VARCHAR(50),
    immatriculation                             VARCHAR(15)   NOT NULL,
    competition_sign                            VARCHAR(5),
    flarm_id                                    VARCHAR(50),
    aircraft_serial_number                      VARCHAR(20),
    year_of_manufacture                         DATE,
    noise_class                                 CHAR(1),
    noise_level                                 NUMERIC(6, 2),
    mtom                                        INTEGER,
    nr_of_seats                                 INTEGER,
    aircraft_owner_person_id                    UUID,
    flight_operating_counter_unit_type_id       UUID,
    engine_operating_counter_unit_type_id       UUID,
    homebase_id                                 UUID,
    spot_link                                   VARCHAR(250),
    is_towing_or_winch_required                 BOOLEAN       NOT NULL DEFAULT false,
    is_towing_start_allowed                     BOOLEAN       NOT NULL DEFAULT false,
    is_winch_start_allowed                      BOOLEAN       NOT NULL DEFAULT false,
    is_towing_aircraft                          BOOLEAN       NOT NULL DEFAULT false,
    is_fast_entry_record                        BOOLEAN       NOT NULL DEFAULT false,
    comment                                     VARCHAR(250),
    daec_index                                  INTEGER,
    created_on                                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                          UUID,
    modified_on                                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                         UUID,
    deleted_on                                  TIMESTAMPTZ,
    deleted_by_user_id                          UUID,
    CONSTRAINT fk_aircraft_owner_club_id
        FOREIGN KEY (owner_club_id)            REFERENCES club (id)            ON DELETE SET NULL,
    CONSTRAINT fk_aircraft_aircraft_type_id
        FOREIGN KEY (aircraft_type_id)         REFERENCES aircraft_type (id)   ON DELETE RESTRICT,
    CONSTRAINT fk_aircraft_aircraft_owner_person_id
        FOREIGN KEY (aircraft_owner_person_id) REFERENCES person (id)          ON DELETE SET NULL,
    CONSTRAINT fk_aircraft_flight_counter_unit_type_id
        FOREIGN KEY (flight_operating_counter_unit_type_id) REFERENCES counter_unit_type (id) ON DELETE RESTRICT,
    CONSTRAINT fk_aircraft_engine_counter_unit_type_id
        FOREIGN KEY (engine_operating_counter_unit_type_id) REFERENCES counter_unit_type (id) ON DELETE RESTRICT,
    CONSTRAINT fk_aircraft_homebase_id
        FOREIGN KEY (homebase_id)              REFERENCES location (id)        ON DELETE SET NULL,
    -- ck_aircraft_year_of_manufacture_sane / ck_aircraft_mtom_sane /
    -- ck_aircraft_nr_of_seats_positive / ck_aircraft_flarm_id_regex
    -- removed per ADR 0022 directive 2: range + shape invariants live on
    -- Year / Mtom / SeatsCount / FlarmId value objects at S-058.
    CONSTRAINT ck_aircraft_spot_link_https
        CHECK (spot_link IS NULL OR spot_link ~* '^https://')
);
COMMENT ON CONSTRAINT ck_aircraft_spot_link_https ON aircraft IS
    'ADR 0022 retained: A10 SSRF defense-in-depth — a non-https spot_link '
    'sneaking past the SpotLink value-object via direct SQL must not silently '
    'persist; the URL is later rendered as a clickable link in the UI.';
-- GLOBAL UNIQUE — aircraft immatriculation is unique by aviation regulator
-- convention; the per-club composite UNIQUE from the prior refinement is
-- incompatible with Aircraft as CROSS_TENANT (no operating_club_id column).
CREATE UNIQUE INDEX ux_aircraft_immatriculation ON aircraft (immatriculation) WHERE deleted_on IS NULL;
CREATE        INDEX ix_aircraft_owner_club      ON aircraft (owner_club_id)
    WHERE owner_club_id IS NOT NULL AND deleted_on IS NULL;
CREATE        INDEX ix_aircraft_type            ON aircraft (aircraft_type_id);
CREATE        INDEX ix_aircraft_homebase        ON aircraft (homebase_id) WHERE homebase_id IS NOT NULL;
CREATE        INDEX ix_aircraft_owner_person    ON aircraft (aircraft_owner_person_id)
    WHERE aircraft_owner_person_id IS NOT NULL;


-- =============================================================================
-- 5. Aggregate root: flight (TENANT_SCOPED; self-FK after table create)
-- =============================================================================

CREATE TABLE flight (
    id                                            UUID          NOT NULL PRIMARY KEY,
    operating_club_id                             UUID          NOT NULL,
    aircraft_id                                   UUID          NOT NULL,
    flight_date                                   DATE,
    start_date_time                               TIMESTAMPTZ,
    ldg_date_time                                 TIMESTAMPTZ,
    block_start_date_time                         TIMESTAMPTZ,
    block_end_date_time                           TIMESTAMPTZ,
    start_location_id                             UUID,
    ldg_location_id                               UUID,
    start_runway                                  VARCHAR(5),
    ldg_runway                                    VARCHAR(5),
    outbound_route                                VARCHAR(50),
    inbound_route                                 VARCHAR(50),
    flight_type_id                                UUID,
    is_solo_flight                                BOOLEAN       NOT NULL DEFAULT false,
    start_type_id                                 UUID,
    tow_flight_id                                 UUID,
    nr_of_ldgs                                    SMALLINT,
    nr_of_ldgs_on_start_location                  SMALLINT,
    no_start_time_information                     BOOLEAN       NOT NULL DEFAULT false,
    no_ldg_time_information                       BOOLEAN       NOT NULL DEFAULT false,
    air_state_id                                  UUID          NOT NULL,
    process_state_id                              UUID          NOT NULL,
    flight_aircraft_type_id                       SMALLINT      NOT NULL,
    engine_start_operating_counter_in_seconds     BIGINT,
    engine_end_operating_counter_in_seconds       BIGINT,
    comment                                       TEXT,
    incident_comment                              TEXT,
    validation_errors                             TEXT,
    coupon_number                                 VARCHAR(20),
    flight_cost_balance_type_id                   UUID,
    delivery_created_on                           TIMESTAMPTZ,
    validated_on                                  TIMESTAMPTZ,
    nr_of_passengers                              SMALLINT,
    start_position                                SMALLINT,
    flight_report_sent_on                         TIMESTAMPTZ,
    created_on                                    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                            UUID,
    modified_on                                   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                           UUID,
    deleted_on                                    TIMESTAMPTZ,
    deleted_by_user_id                            UUID,
    CONSTRAINT fk_flight_operating_club_id
        FOREIGN KEY (operating_club_id)           REFERENCES club (id)                     ON DELETE RESTRICT,
    CONSTRAINT fk_flight_aircraft_id
        FOREIGN KEY (aircraft_id)                 REFERENCES aircraft (id)                 ON DELETE RESTRICT,
    CONSTRAINT fk_flight_start_location_id
        FOREIGN KEY (start_location_id)           REFERENCES location (id)                 ON DELETE SET NULL,
    CONSTRAINT fk_flight_ldg_location_id
        FOREIGN KEY (ldg_location_id)             REFERENCES location (id)                 ON DELETE SET NULL,
    CONSTRAINT fk_flight_flight_type_id
        FOREIGN KEY (flight_type_id)              REFERENCES flight_type (id)              ON DELETE RESTRICT,
    CONSTRAINT fk_flight_start_type_id
        FOREIGN KEY (start_type_id)               REFERENCES start_type (id)               ON DELETE RESTRICT,
    CONSTRAINT fk_flight_tow_flight_id
        FOREIGN KEY (tow_flight_id)               REFERENCES flight (id)                   ON DELETE SET NULL,
    CONSTRAINT fk_flight_air_state_id
        FOREIGN KEY (air_state_id)                REFERENCES flight_air_state (id)         ON DELETE RESTRICT,
    CONSTRAINT fk_flight_process_state_id
        FOREIGN KEY (process_state_id)            REFERENCES flight_process_state (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_flight_flight_cost_balance_type_id
        FOREIGN KEY (flight_cost_balance_type_id) REFERENCES flight_cost_balance_type (id) ON DELETE RESTRICT
    -- All 14 CHECK constraints previously on flight removed per ADR 0022
    -- directive 2. Sacred-cow invariants migrate to the Flight aggregate at
    -- S-058 (port of FlightValidator):
    --   * flight_aircraft_type_id IN (1,2,4) — FlightAircraftType enum
    --     (@Enumerated(STRING) or sparse SMALLINT in the Hibernate mapping).
    --   * tow_flight_id ≠ id + tow_flight requires glider — Flight.linkTow().
    --   * ldg_date_time ≥ start_date_time + block ordering — TimeWindow VO +
    --     Flight constructor guards.
    --   * date / counter / passenger / position / runway / coupon range +
    --     format invariants — FlightDate / RunwayCode / CouponNumber /
    --     EngineCounterSeconds / LandingCount / PassengerCount VOs.
);

-- Hot-path indexes per design notes' load-bearing inventory.
CREATE INDEX ix_flight_club_date
    ON flight (operating_club_id, flight_date DESC);
CREATE INDEX ix_flight_club_state
    ON flight (operating_club_id, process_state_id)
    INCLUDE (flight_date, id);
CREATE INDEX ix_flight_aircraft_date
    ON flight (aircraft_id, flight_date DESC);
CREATE INDEX ix_flight_tow_flight
    ON flight (tow_flight_id)
    WHERE tow_flight_id IS NOT NULL;
CREATE INDEX ix_flight_start_location
    ON flight (operating_club_id, start_location_id, flight_date)
    WHERE start_location_id IS NOT NULL;
CREATE INDEX ix_flight_ldg_location
    ON flight (operating_club_id, ldg_location_id, flight_date)
    WHERE ldg_location_id IS NOT NULL;
CREATE INDEX ix_flight_flight_type
    ON flight (operating_club_id, flight_type_id)
    WHERE flight_type_id IS NOT NULL;
CREATE INDEX ix_flight_club_aircraft_type
    ON flight (operating_club_id, flight_aircraft_type_id, flight_date DESC);
CREATE INDEX ix_flight_validated_on
    ON flight (operating_club_id, validated_on)
    WHERE validated_on IS NULL;
CREATE INDEX ix_flight_coupon
    ON flight (operating_club_id, coupon_number)
    WHERE coupon_number IS NOT NULL;


-- =============================================================================
-- 6. Aggregate root: article (TENANT_SCOPED)
-- =============================================================================

CREATE TABLE article (
    id                  UUID          NOT NULL PRIMARY KEY,
    operating_club_id   UUID          NOT NULL,
    article_number      VARCHAR(50)   NOT NULL,
    article_name        VARCHAR(250)  NOT NULL,
    article_info        VARCHAR(250),
    description         TEXT,
    is_active           BOOLEAN       NOT NULL DEFAULT true,
    created_on          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id UUID,
    deleted_on          TIMESTAMPTZ,
    deleted_by_user_id  UUID,
    CONSTRAINT fk_article_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES club (id) ON DELETE RESTRICT
);
CREATE        INDEX ix_article_club        ON article (operating_club_id) WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_article_club_number
    ON article (operating_club_id, article_number)
    WHERE deleted_on IS NULL;


-- =============================================================================
-- 7. Aggregate-internal: flight_crew (under Flight)
-- =============================================================================

CREATE TABLE flight_crew (
    id                          UUID          NOT NULL PRIMARY KEY,
    flight_id                   UUID          NOT NULL,
    person_id                   UUID          NOT NULL,
    flight_crew_type_id         UUID          NOT NULL,
    begin_flight_datetime       TIMESTAMPTZ,
    end_flight_datetime         TIMESTAMPTZ,
    begin_instruction_datetime  TIMESTAMPTZ,
    end_instruction_datetime    TIMESTAMPTZ,
    nr_of_ldgs                  SMALLINT,
    nr_of_starts                SMALLINT,
    deleted_on                  TIMESTAMPTZ,
    deleted_by_user_id          UUID,
    CONSTRAINT fk_flight_crew_flight_id
        FOREIGN KEY (flight_id)            REFERENCES flight (id)           ON DELETE CASCADE,
    CONSTRAINT fk_flight_crew_person_id
        FOREIGN KEY (person_id)            REFERENCES person (id)           ON DELETE RESTRICT,
    CONSTRAINT fk_flight_crew_flight_crew_type_id
        FOREIGN KEY (flight_crew_type_id)  REFERENCES flight_crew_type (id) ON DELETE RESTRICT
    -- ck_flight_crew_nr_of_ldgs_nonnegative + ck_flight_crew_nr_of_starts_nonnegative
    -- removed per ADR 0022 directive 2: LandingCount / StartCount VOs at S-058.
);
CREATE UNIQUE INDEX ux_flight_crew_unique
    ON flight_crew (flight_id, person_id, flight_crew_type_id)
    WHERE deleted_on IS NULL;
CREATE        INDEX ix_flight_crew_flight       ON flight_crew (flight_id);
CREATE        INDEX ix_flight_crew_person_type  ON flight_crew (person_id, flight_crew_type_id) INCLUDE (flight_id);


-- =============================================================================
-- 8. Aggregate-internal: aircraft_aircraft_state (under Aircraft)
-- =============================================================================

CREATE TABLE aircraft_aircraft_state (
    id                      UUID          NOT NULL PRIMARY KEY,
    aircraft_id             UUID          NOT NULL,
    aircraft_state_id       UUID          NOT NULL,
    valid_from              TIMESTAMPTZ   NOT NULL,
    valid_to                TIMESTAMPTZ,
    noticed_by_person_id    UUID,
    remarks                 TEXT,
    created_on              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id      UUID,
    modified_on             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id     UUID,
    deleted_on              TIMESTAMPTZ,
    deleted_by_user_id      UUID,
    CONSTRAINT fk_aas_aircraft_id
        FOREIGN KEY (aircraft_id)         REFERENCES aircraft (id)         ON DELETE CASCADE,
    CONSTRAINT fk_aas_aircraft_state_id
        FOREIGN KEY (aircraft_state_id)   REFERENCES aircraft_state (id)   ON DELETE RESTRICT,
    CONSTRAINT fk_aas_noticed_by_person_id
        FOREIGN KEY (noticed_by_person_id) REFERENCES person (id)          ON DELETE SET NULL
    -- ck_aas_valid_to_at_or_after_valid_from removed per ADR 0022 directive 2:
    -- valid_to ≥ valid_from is an AircraftStatePeriod VO invariant at S-058.
);
CREATE UNIQUE INDEX ux_aas_aircraft_valid_from
    ON aircraft_aircraft_state (aircraft_id, valid_from);
CREATE UNIQUE INDEX ux_aas_current_state_per_aircraft
    ON aircraft_aircraft_state (aircraft_id)
    WHERE valid_to IS NULL AND deleted_on IS NULL;
CREATE INDEX ix_aas_aircraft_valid
    ON aircraft_aircraft_state (aircraft_id, valid_from DESC);


-- =============================================================================
-- 9. Aggregate-internal: aircraft_operating_counter (under Aircraft)
-- =============================================================================

CREATE TABLE aircraft_operating_counter (
    id                                                          UUID          NOT NULL PRIMARY KEY,
    aircraft_id                                                 UUID          NOT NULL,
    at_date_time                                                TIMESTAMPTZ   NOT NULL,
    total_towed_glider_starts                                   INTEGER,
    total_winch_launch_starts                                   INTEGER,
    total_self_starts                                           INTEGER,
    flight_operating_counter_in_seconds                         BIGINT,
    engine_operating_counter_in_seconds                         BIGINT,
    next_maintenance_at_flight_operating_counter_in_seconds     BIGINT,
    next_maintenance_at_engine_operating_counter_in_seconds     BIGINT,
    created_on                                                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                                          UUID,
    modified_on                                                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                                         UUID,
    deleted_on                                                  TIMESTAMPTZ,
    deleted_by_user_id                                          UUID,
    CONSTRAINT fk_aoc_aircraft_id
        FOREIGN KEY (aircraft_id) REFERENCES aircraft (id) ON DELETE CASCADE
    -- All 8 CHECK constraints previously on aircraft_operating_counter
    -- removed per ADR 0022 directive 2: at_date_time future-bound sanity +
    -- per-counter range guards live on AircraftOperatingCounter VOs +
    -- constructor at S-058.
);
CREATE UNIQUE INDEX ux_aoc_aircraft_at_date_time
    ON aircraft_operating_counter (aircraft_id, at_date_time);
-- Covering index for "latest counter per aircraft" Index Only Scan.
CREATE INDEX ix_aoc_aircraft_recorded
    ON aircraft_operating_counter (aircraft_id, at_date_time DESC)
    INCLUDE (flight_operating_counter_in_seconds, engine_operating_counter_in_seconds);


-- =============================================================================
-- 10. Aggregate-internal: inoutbound_point (under Location)
-- =============================================================================

CREATE TABLE inoutbound_point (
    id                      UUID          NOT NULL PRIMARY KEY,
    location_id             UUID          NOT NULL,
    point_name              VARCHAR(100)  NOT NULL,
    point_type              VARCHAR(50),
    direction               VARCHAR(50),
    description             TEXT,
    created_on              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id      UUID,
    modified_on             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id     UUID,
    deleted_on              TIMESTAMPTZ,
    deleted_by_user_id      UUID,
    CONSTRAINT fk_iop_location_id
        FOREIGN KEY (location_id) REFERENCES location (id) ON DELETE CASCADE
);
CREATE INDEX ix_iop_location ON inoutbound_point (location_id);


-- =============================================================================
-- 11. ALTER TABLE club — add 5 deferred FK columns (from S-012)
-- =============================================================================
-- The 4 default_*_flight_type_id columns are documented in legacy
-- Club.cs:77-81 (Glider, Tow, Motor). The 5th — default_glider_with_motor_*
-- — is NOT in legacy; added as a forward-looking column so all 4 axes of
-- flight aircraft type have a defaultable slot. Operator may drop it for
-- strict legacy parity if cutover demands.

ALTER TABLE club
    ADD COLUMN homebase_id                              UUID,
    ADD COLUMN default_glider_flight_type_id            UUID,
    ADD COLUMN default_tow_flight_type_id               UUID,
    ADD COLUMN default_motor_flight_type_id             UUID,
    ADD COLUMN default_glider_with_motor_flight_type_id UUID;

ALTER TABLE club
    ADD CONSTRAINT fk_club_homebase_id
        FOREIGN KEY (homebase_id) REFERENCES location (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_club_default_glider_flight_type_id
        FOREIGN KEY (default_glider_flight_type_id) REFERENCES flight_type (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_club_default_tow_flight_type_id
        FOREIGN KEY (default_tow_flight_type_id) REFERENCES flight_type (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_club_default_motor_flight_type_id
        FOREIGN KEY (default_motor_flight_type_id) REFERENCES flight_type (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_club_default_glider_with_motor_flight_type_id
        FOREIGN KEY (default_glider_with_motor_flight_type_id) REFERENCES flight_type (id) ON DELETE SET NULL;


-- =============================================================================
-- 12. Aggregate-root + cross-tenant SQL COMMENT ON COLUMN forensic block
-- =============================================================================

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
COMMENT ON COLUMN aircraft_aircraft_state.noticed_by_person_id IS
    'Cross-tenant ride-through; SET NULL on delete.';
COMMENT ON COLUMN aircraft.spot_link IS
    'External URL; NEVER fetched server-side (A10 SSRF mitigation). https-only CHECK enforced. Render link only in UI.';

-- Aircraft ownership-exclusivity invariant (service-layer enforced — schema can't express
-- with-OR cheaply; cheap-CHECK would be brittle). One of: owner_club_id NOT NULL,
-- aircraft_owner_person_id NOT NULL, or both NULL (charter pool / public rental fleet).
-- NEVER both set on the same row. S-022 / S-026 enforces; this comment is the contract.
COMMENT ON COLUMN aircraft.aircraft_owner_person_id IS
    'Cross-tenant ride-through; SET NULL on delete for FADP erasure. Exclusive with aircraft.owner_club_id: one of owner_club_id / aircraft_owner_person_id may be NOT NULL or both NULL (charter pool); NEVER both set. Service layer (S-022/S-026) enforces — schema can''t express the with-OR cheaply.';

-- Cross-tenant invariant on club.default_*_flight_type_id (4 FKs added at section 11).
-- Each default_*_flight_type_id MUST point at a flight_type row whose
-- operating_club_id = club.id. Schema FK only enforces target existence; the
-- tenant-alignment invariant lives at the S-022/S-026 service layer.
COMMENT ON COLUMN club.default_glider_flight_type_id IS
    'Cross-tenant invariant: target flight_type.operating_club_id MUST equal this club.id. Service layer (S-022) enforces on update; schema cannot.';
COMMENT ON COLUMN club.default_tow_flight_type_id IS
    'Cross-tenant invariant: target flight_type.operating_club_id MUST equal this club.id. Service layer (S-022) enforces on update; schema cannot.';
COMMENT ON COLUMN club.default_motor_flight_type_id IS
    'Cross-tenant invariant: target flight_type.operating_club_id MUST equal this club.id. Service layer (S-022) enforces on update; schema cannot.';
COMMENT ON COLUMN club.default_glider_with_motor_flight_type_id IS
    'Cross-tenant invariant: target flight_type.operating_club_id MUST equal this club.id. Service layer (S-022) enforces on update; schema cannot. NOT in legacy Club.cs:77-81 — forward-looking; operator may drop for strict parity.';

-- Free-text PII columns (S-027 audit-blob redaction policy).
COMMENT ON COLUMN flight.comment            IS 'Free text; PII-spill risk; redact in audit blob and never log raw';
COMMENT ON COLUMN flight.incident_comment   IS 'Free text; PII-spill risk; redact in audit blob and never log raw';
COMMENT ON COLUMN flight.validation_errors  IS 'Free text; PII-spill risk; redact in audit blob and never log raw';
COMMENT ON COLUMN flight.outbound_route     IS 'Free text; PII-spill risk; redact in audit blob';
COMMENT ON COLUMN flight.inbound_route      IS 'Free text; PII-spill risk; redact in audit blob';
COMMENT ON COLUMN aircraft.comment          IS 'Free text; PII-spill risk; redact in audit blob';
COMMENT ON COLUMN aircraft_aircraft_state.remarks IS 'Free text; PII-spill risk; redact in audit blob';
COMMENT ON COLUMN location.description      IS 'Free text shared cross-tenant; PII-spill risk; redact in audit blob; review on edit';


-- =============================================================================
-- 13. Reference-data seeds — fixed canonical UUID v7 literals
-- =============================================================================
-- Generator: next/server/src/test/resources/scripts/GenerateCanonicalUuids.java
-- Ground truth: next/server/src/test/resources/reference-seeds-canonical-uuids.json
-- Re-running the generator must produce bit-identical UUIDs (deterministic by
-- construction). DO NOT regenerate after this migration has shipped to any
-- environment — Flyway checksum-locks V3.

-- aircraft_type (8 rows; bit-field codes preserved as legacy_int_id)
INSERT INTO aircraft_type (id, code, legacy_int_id, description, has_engine, requires_towing_info, may_be_towing_aircraft) VALUES
    ('019e2e15-2c00-7af8-8000-000000002af8'::uuid, 'UNKNOWN',           0,  'Unknown — type not set',                   NULL,  NULL,  NULL),
    ('019e2e15-2c00-7af9-8000-000000002af9'::uuid, 'GLIDER',            1,  'Pure glider, no engine',                   false, true,  false),
    ('019e2e15-2c00-7afa-8000-000000002afa'::uuid, 'GLIDER_WITH_MOTOR', 2,  'Glider with motor or turbo',               true,  true,  false),
    ('019e2e15-2c00-7afb-8000-000000002afb'::uuid, 'MOTOR_GLIDER',      4,  'Touring motor glider (TMG)',               true,  false, true),
    ('019e2e15-2c00-7afc-8000-000000002afc'::uuid, 'MOTOR_AIRCRAFT',    8,  'Motor aircraft or tow plane',              true,  false, true),
    ('019e2e15-2c00-7afd-8000-000000002afd'::uuid, 'MULTI_ENGINE',      16, 'Multi-engine motor aircraft',              true,  false, false),
    ('019e2e15-2c00-7afe-8000-000000002afe'::uuid, 'JET',               32, 'Jet aircraft',                             true,  false, false),
    ('019e2e15-2c00-7aff-8000-000000002aff'::uuid, 'HELICOPTER',        64, 'Helicopter',                               true,  false, false);

-- aircraft_state (7 rows per AircraftStateKey.cs; legacy_int_id sparse 1..6, 99)
INSERT INTO aircraft_state (id, code, legacy_int_id, description, is_aircraft_flyable) VALUES
    ('019e2e15-2c00-7ee0-8000-000000002ee0'::uuid, 'OK',          1,  'Aircraft is fully operational',                          true),
    ('019e2e15-2c00-7ee1-8000-000000002ee1'::uuid, 'INFORMATION', 2,  'Informational note recorded; aircraft remains flyable', true),
    ('019e2e15-2c00-7ee2-8000-000000002ee2'::uuid, 'ATTENTION',   3,  'Attention required; aircraft remains flyable',          true),
    ('019e2e15-2c00-7ee3-8000-000000002ee3'::uuid, 'MALFUNCTION', 4,  'Aircraft malfunction; grounded',                         false),
    ('019e2e15-2c00-7ee4-8000-000000002ee4'::uuid, 'MAINTENANCE', 5,  'Aircraft in maintenance; grounded',                      false),
    ('019e2e15-2c00-7ee5-8000-000000002ee5'::uuid, 'UNINSURED',   6,  'Insurance lapsed; grounded',                             false),
    ('019e2e15-2c00-7ee6-8000-000000002ee6'::uuid, 'END_OF_LIFE', 99, 'Aircraft retired / decommissioned',                      false);

-- location_type (6 rows from legacy "3 Insert Static Data.sql"; LocationTypeCupId in {1..5, 99}).
-- Design notes anticipated 17 rows ("legacy snapshot") but the test-data file ships only 6;
-- ship the 6 known rows here. S-016 cutover can backfill richer per-club rows from production
-- snapshots if any exist.
INSERT INTO location_type (id, code, legacy_int_id, description, is_airfield) VALUES
    ('019e2e15-2c00-72c8-8000-0000000032c8'::uuid, 'WAYPOINT',         1,  'Navigation waypoint (non-airfield)',     false),
    ('019e2e15-2c00-72c9-8000-0000000032c9'::uuid, 'GRASS_RUNWAY',     2,  'Airfield with grass runway',             true),
    ('019e2e15-2c00-72ca-8000-0000000032ca'::uuid, 'EXTERNAL_FIELD',   3,  'Outlanding field (non-airfield)',        false),
    ('019e2e15-2c00-72cb-8000-0000000032cb'::uuid, 'GLIDER_AIRFIELD',  4,  'Glider-only airfield',                   true),
    ('019e2e15-2c00-72cc-8000-0000000032cc'::uuid, 'CONCRETE_RUNWAY',  5,  'Airfield with concrete / hard runway',   true),
    ('019e2e15-2c00-72cd-8000-0000000032cd'::uuid, 'OTHER',            99, 'Other location type',                    false);

-- flight_crew_type (7 rows from legacy seed; legacy_int_id in {1..6, 10})
INSERT INTO flight_crew_type (id, code, legacy_int_id, description) VALUES
    ('019e2e15-2c00-76b0-8000-0000000036b0'::uuid, 'PILOT_OR_STUDENT',              1,  'Pilot or student pilot'),
    ('019e2e15-2c00-76b1-8000-0000000036b1'::uuid, 'CO_PILOT',                      2,  'Co-pilot / second crew member'),
    ('019e2e15-2c00-76b2-8000-0000000036b2'::uuid, 'FLIGHT_INSTRUCTOR',             3,  'Flight instructor / examiner'),
    ('019e2e15-2c00-76b3-8000-0000000036b3'::uuid, 'PASSENGER',                     4,  'Passenger'),
    ('019e2e15-2c00-76b4-8000-0000000036b4'::uuid, 'WINCH_OPERATOR',                5,  'Winch operator'),
    ('019e2e15-2c00-76b5-8000-0000000036b5'::uuid, 'OBSERVER',                      6,  'Observing pilot or instructor'),
    ('019e2e15-2c00-76b6-8000-0000000036b6'::uuid, 'FLIGHT_COST_INVOICE_RECIPIENT', 10, 'Recipient of flight cost invoice');

-- flight_process_state (8 rows per FlightProcessState.cs; legacy_int_id in {0,28,30,40,45,50,60,99})
INSERT INTO flight_process_state (id, code, legacy_int_id, description) VALUES
    ('019e2e15-2c00-7a98-8000-000000003a98'::uuid, 'NOT_PROCESSED',                  0,  'No processing has run on this flight yet'),
    ('019e2e15-2c00-7a99-8000-000000003a99'::uuid, 'INVALID',                        28, 'Flight validated but data is invalid or implausible'),
    ('019e2e15-2c00-7a9a-8000-000000003a9a'::uuid, 'VALID',                          30, 'Flight validated and data is valid'),
    ('019e2e15-2c00-7a9b-8000-000000003a9b'::uuid, 'LOCKED',                         40, 'Flight cannot be edited; ready for billing'),
    ('019e2e15-2c00-7a9c-8000-000000003a9c'::uuid, 'DELIVERY_PREPARATION_ERROR',     45, 'Delivery preparation failed (no rule matched / no items)'),
    ('019e2e15-2c00-7a9d-8000-000000003a9d'::uuid, 'DELIVERY_PREPARED',              50, 'Delivery / invoice prepared; flight no longer editable'),
    ('019e2e15-2c00-7a9e-8000-000000003a9e'::uuid, 'DELIVERY_BOOKED',                60, 'Delivery booked in external finance system; terminal'),
    ('019e2e15-2c00-7a9f-8000-000000003a9f'::uuid, 'EXCLUDED_FROM_DELIVERY_PROCESS', 99, 'Excluded from delivery process; reversible to LOCKED');

-- flight_air_state (7 rows per FlightAirState.cs; legacy_int_id in {0,5,8,10,15,20,25})
INSERT INTO flight_air_state (id, code, legacy_int_id, description) VALUES
    ('019e2e15-2c00-7e80-8000-000000003e80'::uuid, 'NEW',                       0,  'New flight; not started yet'),
    ('019e2e15-2c00-7e81-8000-000000003e81'::uuid, 'FLIGHT_PLAN_OPEN',          5,  'Flight plan opened'),
    ('019e2e15-2c00-7e82-8000-000000003e82'::uuid, 'MIGHT_BE_STARTED',          8,  'No start information available'),
    ('019e2e15-2c00-7e83-8000-000000003e83'::uuid, 'STARTED',                   10, 'Aircraft started / in flight'),
    ('019e2e15-2c00-7e84-8000-000000003e84'::uuid, 'MIGHT_BE_LANDED_OR_IN_AIR', 15, 'No landing information available'),
    ('019e2e15-2c00-7e85-8000-000000003e85'::uuid, 'LANDED',                    20, 'Aircraft landed'),
    ('019e2e15-2c00-7e86-8000-000000003e86'::uuid, 'FLIGHT_PLAN_CLOSED',        25, 'Flight / flight plan closed');

-- flight_cost_balance_type (5 rows from legacy seed; legacy_int_id in 1..5)
-- Per design notes: at-least-one CHECK on {is_for_glider, is_for_tow, is_for_motor}.
-- Legacy seed's TOW_PILOT_PAYS_TOW carried all 3 flags = 0 (structurally wrong); the
-- new schema flips glider+tow to true (the type is structurally glider-and-tow only).
INSERT INTO flight_cost_balance_type (id, code, legacy_int_id, description,
        person_for_invoice_required, is_for_glider, is_for_tow, is_for_motor) VALUES
    ('019e2e15-2c00-7268-8000-000000004268'::uuid, 'PILOT_PAYS_ALL',             1, 'Pilot pays all flight costs incl. landing fees',
        false, true, true,  true),
    ('019e2e15-2c00-7269-8000-000000004269'::uuid, 'FIFTY_FIFTY_PILOT_COPILOT',  2, 'Costs split 50:50 between pilot and co-pilot',
        false, true, false, false),
    ('019e2e15-2c00-726a-8000-00000000426a'::uuid, 'TOW_PILOT_PAYS_TOW',         3, 'Tow pilot covers tow cost; glider pilot pays glider cost',
        false, true, true,  false),
    ('019e2e15-2c00-726b-8000-00000000426b'::uuid, 'NO_INSTRUCTOR_FEE',          4, 'No instructor fee charged to pilot/student',
        false, true, true,  true),
    ('019e2e15-2c00-726c-8000-00000000426c'::uuid, 'INVOICE_TO_PERSON',          5, 'Invoice issued to named person',
        true,  true, true,  true);


-- =============================================================================
-- 14. Update app_meta sentinel to reflect the S-013 generation.
-- =============================================================================

UPDATE app_meta SET meta_value = 'S-013' WHERE meta_key = 'schema_baseline_version';
