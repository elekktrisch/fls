-- J-7 RM-1 — flight-report read-model tables (ADR 0027 §2).
--
-- Redundant, domain-maintained projection of the flight-report row shape the
-- report screen reads (column set = the SELECT of the native-SQL oracle in
-- JpaFlightReportRepository, page + summary queries). Rows are written
-- synchronously by FlightReportProjector on every Flight save (Spring Data
-- @DomainEvents) — never by triggers (ADR 0022 directive 2: business logic in
-- the Java domain, not the database).
--
-- STRUCTURAL ONLY per ADR 0022: PK / FK / NOT NULL / indexes. No business
-- CHECKs — projection invariants live on the FlightReportRow aggregate.
--
-- AIR-STATE IS NEVER STORED (sacred cow): only the raw timestamp/flag columns
-- are carried; services compute air-state via FlightAirState.compute.
--
-- These tables have no deleted_on — rows are hard-deleted / rebuilt by the
-- projector, so every index below covers all rows by construction (S-014
-- tombstone rule: no-soft-delete table).

CREATE TABLE t_flight_report_row (
    flight_id                     UUID         NOT NULL PRIMARY KEY,
    operating_club_id             UUID         NOT NULL,
    flight_aircraft_type_id      SMALLINT     NOT NULL,
    flight_date                   DATE,
    start_date_time               TIMESTAMPTZ,
    ldg_date_time                 TIMESTAMPTZ,
    no_start_time_information     BOOLEAN      NOT NULL DEFAULT false,
    no_ldg_time_information       BOOLEAN      NOT NULL DEFAULT false,
    flight_plan_opened_on         TIMESTAMPTZ,
    process_state_id              UUID         NOT NULL,
    is_solo_flight                BOOLEAN      NOT NULL DEFAULT false,
    comment                       TEXT,
    start_type_code               VARCHAR(32),
    immatriculation               TEXT,
    pilot_name                    TEXT,
    second_crew_name              TEXT,
    flight_code                   TEXT,
    flight_type_name              TEXT,
    start_location_id             UUID,
    start_location_name           TEXT,
    ldg_location_id               UUID,
    ldg_location_name             TEXT,
    nr_of_ldgs                    SMALLINT,
    nr_of_ldgs_on_start_location  SMALLINT,
    duration_seconds              BIGINT,
    towed_glider_flight_id        UUID,
    -- Tow block: the paired aerotow flight's denormalized copy. All columns
    -- NULL when the flight has no tow link.
    tow_flight_id                 UUID,
    tow_start_date_time           TIMESTAMPTZ,
    tow_ldg_date_time             TIMESTAMPTZ,
    tow_no_start_time_information BOOLEAN,
    tow_no_ldg_time_information   BOOLEAN,
    tow_flight_plan_opened_on     TIMESTAMPTZ,
    tow_process_state_id          UUID,
    tow_immatriculation           TEXT,
    tow_pilot_name                TEXT,
    tow_flight_code               TEXT,
    tow_flight_type_name          TEXT,
    tow_start_location_name       TEXT,
    tow_ldg_location_name         TEXT,
    CONSTRAINT fk_flight_report_row_flight_id
        FOREIGN KEY (flight_id)         REFERENCES t_flight (id) ON DELETE CASCADE,
    CONSTRAINT fk_flight_report_row_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES t_club (id)   ON DELETE RESTRICT
);

COMMENT ON TABLE t_flight_report_row IS
    'Domain-maintained flight-report read-model (ADR 0027). One row per live '
    'Flight, written redundantly in the same transaction as the Flight save '
    'by FlightReportProjector. Decoration columns are denormalized copies; '
    'air-state is never stored.';

-- covers tombstones: no deleted_on on this table (projection rows are hard-deleted/rebuilt)
CREATE INDEX ix_frr_club_date  ON t_flight_report_row (operating_club_id, flight_date);
-- covers tombstones: no deleted_on on this table (projection rows are hard-deleted/rebuilt)
CREATE INDEX ix_frr_club_start ON t_flight_report_row (operating_club_id, start_date_time);
-- Projector reverse lookups (refresh both sides of a tow link on save).
-- covers tombstones: no deleted_on on this table (projection rows are hard-deleted/rebuilt)
CREATE INDEX ix_frr_tow_flight ON t_flight_report_row (tow_flight_id)
    WHERE tow_flight_id IS NOT NULL;
-- covers tombstones: no deleted_on on this table (projection rows are hard-deleted/rebuilt)
CREATE INDEX ix_frr_towed_glider ON t_flight_report_row (towed_glider_flight_id)
    WHERE towed_glider_flight_id IS NOT NULL;

CREATE TABLE t_flight_report_crew (
    flight_id           UUID NOT NULL,
    person_id           UUID NOT NULL,
    flight_crew_type_id UUID NOT NULL,
    operating_club_id   UUID NOT NULL,
    CONSTRAINT pk_flight_report_crew
        PRIMARY KEY (flight_id, person_id, flight_crew_type_id),
    CONSTRAINT fk_flight_report_crew_flight_id
        FOREIGN KEY (flight_id)         REFERENCES t_flight_report_row (flight_id) ON DELETE CASCADE,
    CONSTRAINT fk_flight_report_crew_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES t_club (id)                     ON DELETE RESTRICT
);

COMMENT ON TABLE t_flight_report_crew IS
    'Live crew (person, crew-type) pairs per flight-report row — serves the '
    'report person-filter and person-role summary flags at query time (RM-3).';

-- covers tombstones: no deleted_on on this table (projection rows are hard-deleted/rebuilt)
CREATE INDEX ix_frc_person ON t_flight_report_crew (person_id);
