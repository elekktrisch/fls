-- V4__reservations_planning_accounting.sql
--
-- S-014: Reservations / planning / accounting baseline.
--   12 domain tables + 1 operational counter table
--   = aircraft_reservation cluster (2),
--     planning cluster (3),
--     accounting cluster (5),
--     delivery-test harness (2),
--     club_delivery_number_counter (1).
--
-- Append-only over S-013's V3. Once V4 is applied to any environment its
-- checksum is locked; never amend — ship V5. Convention is documented in
-- V2 + the no-amend-shipped-migrations rule in CLAUDE.md.
--
-- ============================================================================
-- ID strategy (ADR 0019 — carries from S-012/S-013)
-- ============================================================================
--   * Every PK is `uuid NOT NULL PRIMARY KEY`. Postgres native 16-byte type.
--   * Application generates IDs via Hibernate 7 + uuid-creator
--     UuidCreator.getTimeOrderedEpoch() — wired at S-022.
--   * NO `DEFAULT gen_random_uuid()` on any PK column.
--   * Aggregate-root rows in this migration (5): aircraft_reservation (arv_),
--     planning_day (pln_), accounting_rule_filter (arf_), delivery (dlv_),
--     delivery_creation_test (dct_).
--   * Aggregate-internal entities carry no prefix: delivery_item,
--     planning_day_assignment, delivery_creation_test_item.
--
-- ============================================================================
-- Multi-tenancy (ADR 0008 + 2026-05-16 Aircraft-cross-tenant amendment)
-- ============================================================================
--   * TENANT_SCOPED tables here (10): 5 aggregate roots + 3 internal entities
--     (denormalized) + 2 reclassified-per-club ref tables.
--   * SYSTEM_GLOBAL reference tables (2): accounting_rule_filter_type +
--     accounting_unit_type. No legacy ClubId — seeded with fixed canonical
--     UUID v7 literals + legacy_int_id SMALLINT UNIQUE for S-016 cutover.
--   * Cross-aggregate FKs:
--       - delivery.flight_id → flight.id RESTRICT (Flight TENANT_SCOPED;
--         service layer asserts same-tenant at S-022).
--       - delivery_item.article_id → article.id RESTRICT (same-tenant;
--         invoice integrity preserved by article_number snapshot column).
--   * Cross-tenant FKs (Hibernate @TenantId does NOT filter):
--       - aircraft_reservation.aircraft_id → aircraft.id RESTRICT
--         (Aircraft is cross-tenant per 2026-05-16 amendment; S-022/S-064
--         service layer enforces "may operating_club reserve this aircraft?"
--         via owner_club_id + charter agreement + public-rental checks;
--         audit event carries cross_tenant: true marker when
--         aircraft.owner_club_id != aircraft_reservation.operating_club_id).
--       - aircraft_reservation.location_id → location.id RESTRICT.
--       - planning_day.location_id → location.id RESTRICT.
--       - delivery.recipient_person_id → person.id SET NULL
--         (cross-tenant ride-through; snapshot survives via 9 frozen
--         recipient_* columns per OR Art. 957a).
--       - aircraft_reservation.pilot_person_id → person.id RESTRICT.
--       - aircraft_reservation.second_crew_person_id → person.id SET NULL.
--       - planning_day_assignment.assigned_person_id → person.id RESTRICT.
--       - delivery_creation_test.flight_id → flight.id CASCADE (harness
--         payload dies with the flight).
--
-- ============================================================================
-- Delivery state machine reshape (legacy → new)
-- ============================================================================
--   Legacy has no delivery.process_state_id; state lives on
--   flight.process_state_id + delivery.is_further_processed.
--   The new schema promotes state to first-class on Delivery:
--     SMALLINT NOT NULL CHECK (process_state_id IN (10, 20, 30, 99))
--       10 = Prepared (default)
--       20 = Booked   (terminal-on-mutation; gap-free numbering)
--       30 = Error    (retryable)
--       99 = Cancelled
--   S-016 cutover mapping (planned):
--     flight.process_state_id = 50 (DELIVERY_PREPARED) → delivery 10
--     flight.process_state_id = 45 (DELIVERY_PREPARATION_ERROR) → delivery 30
--     flight.process_state_id = 60 (DELIVERY_BOOKED) → delivery 20
--     delivery.is_further_processed = true ↔ delivery.process_state_id = 20
--   Terminal-on-Booked + state-transition rules enforced at S-064 service
--   layer (DB CHECK can't express transition semantics).
--
-- ============================================================================
-- delivery.delivery_number reshape (VARCHAR → INTEGER + counter)
-- ============================================================================
--   Legacy delivery.DeliveryNumber is VARCHAR with operator-formatted text
--   (e.g. "INV-2024-001"). The new schema reshapes to INTEGER + per-club
--   gap-free uniqueness per Swiss OR Art. 957a:
--     delivery.delivery_number INTEGER NULL
--     UNIQUE (operating_club_id, delivery_number)
--       WHERE delivery_number IS NOT NULL AND deleted_on IS NULL
--     CHECK (process_state_id <> 20 OR delivery_number IS NOT NULL)
--   The text format lives at S-016 in club_extension or as
--   delivery.legacy_delivery_number_text (parity column added on cutover).
--   Service-layer allocator at S-064 uses club_delivery_number_counter
--   (UPDATE...RETURNING for monotonic claim, sub-10ms).
--
-- ============================================================================
-- accounting_rule_filter.filter_config jsonb reshape
-- ============================================================================
--   Legacy AccountingRuleFilter carries 30+ predicate columns (most NULL per
--   filter type). The new schema collapses all predicates into a single
--   jsonb column + a filter_type_id discriminator:
--     filter_type_id  uuid NOT NULL → accounting_rule_filter_type (8 rows)
--     filter_config   jsonb NOT NULL DEFAULT '{}'::jsonb
--   Per-discriminator typed-shape validation runs at S-064 (Jackson
--   default-typing DISABLED globally; ArchUnit rule bans
--   @JsonTypeInfo(use=Id.CLASS) — A03 polymorphic-deserialization
--   mitigation). GIN index serves admin search only; engine reads jsonb
--   wholesale + interprets in Java.
--
-- ============================================================================
-- aircraft_reservation tstzrange + GiST
-- ============================================================================
--   Two TIMESTAMPTZ columns + a generated tstzrange:
--     reservation_start TIMESTAMPTZ NOT NULL,
--     reservation_end   TIMESTAMPTZ NOT NULL,
--     CHECK (reservation_end > reservation_start),
--     reservation_range tstzrange GENERATED ALWAYS AS
--       (tstzrange(reservation_start, reservation_end, '[)')) STORED
--   Note: the refinement design notes wrote `tsrange`, but tsrange takes
--   TIMESTAMP (no TZ) — the implicit ::timestamp cast from TIMESTAMPTZ is
--   session-TZ-dependent and therefore NOT IMMUTABLE, which Postgres rejects
--   in a generation expression. tstzrange is immutable when both args are
--   TIMESTAMPTZ; it's the right primitive for our schema.
--   GiST index on (aircraft_id, reservation_range) WHERE deleted_on IS NULL
--   serves sub-10ms conflict probes at S-064. EXCLUDE USING gist NOT applied
--   at DB level — multiple legitimate-overlap business rules (maintenance vs
--   flight; multi-pilot; charter exemption) enforced at S-064 service layer.
--
-- ============================================================================
-- delivery_item.total_amount generated STORED
-- ============================================================================
--   NUMERIC(14,4) GENERATED ALWAYS AS
--     (quantity * unit_price * (100 - discount_in_percent) / 100.0) STORED
--   Postgres 17 stored generated column — re-computation drift impossible.
--   unit_price is a forward-looking addition (legacy DeliveryItem has no
--   unit_price); S-016 cutover back-fills from article master.
--
-- ============================================================================
-- delivery.recipient_* 9 frozen snapshot columns
-- ============================================================================
--   Per Swiss OR Art. 957a (10-year invoice retention), recipient name +
--   address are snapshot at booking and NEVER re-resolved from
--   recipient_person_id. DSAR exempt once process_state_id >= 20.
--   Documented in tenant-rules.yaml.Deliveries.fadp_dsar_retention_exempt_when.
--
-- ============================================================================
-- Migration ordering
-- ============================================================================
--   1. CREATE EXTENSION btree_gist (composite GiST on aircraft_reservation).
--   2. Reservations cluster: aircraft_reservation_type → aircraft_reservation.
--   3. Planning cluster: planning_day_assignment_type → planning_day →
--      planning_day_assignment.
--   4. Accounting cluster — reference tables: accounting_rule_filter_type +
--      accounting_unit_type.
--   5. Accounting cluster — rule filter aggregate root: accounting_rule_filter.
--   6. Delivery aggregate root + DeliveryItem internal entity.
--   7. Delivery-creation-test harness: delivery_creation_test +
--      delivery_creation_test_item.
--   8. Operational: club_delivery_number_counter.
--   9. SQL COMMENT ON COLUMN block (forensic clarity).
--  10. Reference-data seeds (fixed canonical UUID v7 literals).
--  11. Update app_meta sentinel to S-014.
-- ============================================================================


-- =============================================================================
-- 1. Required extension
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;


-- =============================================================================
-- 2. Reservations cluster
-- =============================================================================

CREATE TABLE aircraft_reservation_type (
    id                          UUID          NOT NULL PRIMARY KEY,
    operating_club_id           UUID          NOT NULL,
    reservation_type_name       VARCHAR(100)  NOT NULL,
    is_instructor_required      BOOLEAN       NOT NULL DEFAULT false,
    is_maintenance              BOOLEAN       NOT NULL DEFAULT false,
    is_active                   BOOLEAN       NOT NULL DEFAULT true,
    remarks                     TEXT,
    created_on                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id          UUID,
    modified_on                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id         UUID,
    deleted_on                  TIMESTAMPTZ,
    deleted_by_user_id          UUID,
    CONSTRAINT fk_arvt_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES club (id) ON DELETE RESTRICT
);
CREATE INDEX ix_arvt_club ON aircraft_reservation_type (operating_club_id)
    WHERE deleted_on IS NULL;

CREATE TABLE aircraft_reservation (
    id                          UUID          NOT NULL PRIMARY KEY,
    operating_club_id           UUID          NOT NULL,
    aircraft_id                 UUID          NOT NULL,
    reservation_start           TIMESTAMPTZ   NOT NULL,
    reservation_end             TIMESTAMPTZ   NOT NULL,
    reservation_range           tstzrange     GENERATED ALWAYS AS
        (tstzrange(reservation_start, reservation_end, '[)')) STORED,
    is_all_day                  BOOLEAN       NOT NULL DEFAULT false,
    pilot_person_id             UUID          NOT NULL,
    second_crew_person_id       UUID,
    location_id                 UUID          NOT NULL,
    reservation_type_id         UUID,
    flight_type_id              UUID,
    info                        TEXT,
    created_on                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id          UUID,
    modified_on                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id         UUID,
    deleted_on                  TIMESTAMPTZ,
    deleted_by_user_id          UUID,
    CONSTRAINT fk_arv_operating_club_id
        FOREIGN KEY (operating_club_id)       REFERENCES club (id)                    ON DELETE RESTRICT,
    CONSTRAINT fk_arv_aircraft_id
        FOREIGN KEY (aircraft_id)             REFERENCES aircraft (id)                ON DELETE RESTRICT,
    CONSTRAINT fk_arv_pilot_person_id
        FOREIGN KEY (pilot_person_id)         REFERENCES person (id)                  ON DELETE RESTRICT,
    CONSTRAINT fk_arv_second_crew_person_id
        FOREIGN KEY (second_crew_person_id)   REFERENCES person (id)                  ON DELETE SET NULL,
    CONSTRAINT fk_arv_location_id
        FOREIGN KEY (location_id)             REFERENCES location (id)                ON DELETE RESTRICT,
    CONSTRAINT fk_arv_reservation_type_id
        FOREIGN KEY (reservation_type_id)     REFERENCES aircraft_reservation_type (id) ON DELETE RESTRICT,
    CONSTRAINT fk_arv_flight_type_id
        FOREIGN KEY (flight_type_id)          REFERENCES flight_type (id)             ON DELETE RESTRICT,
    CONSTRAINT ck_arv_end_after_start
        CHECK (reservation_end > reservation_start),
    CONSTRAINT ck_arv_max_30_days
        CHECK (reservation_end <= reservation_start + INTERVAL '30 days')
);
CREATE INDEX ix_arv_aircraft_range_gist
    ON aircraft_reservation USING gist (aircraft_id, reservation_range)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_arv_club_start_end
    ON aircraft_reservation (operating_club_id, reservation_start, reservation_end)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_arv_pilot
    ON aircraft_reservation (pilot_person_id, reservation_start DESC)
    WHERE pilot_person_id IS NOT NULL AND deleted_on IS NULL;
-- covers tombstones: deferred-perf-tuning S-108 — index shape (DESC ordering +
-- partial predicate) pending production-scale query-plan analysis. Full-range
-- scan acceptable at current per-club reservation counts; revisit at S-108.
CREATE INDEX ix_arv_location
    ON aircraft_reservation (operating_club_id, location_id, reservation_start);


-- =============================================================================
-- 3. Planning cluster
-- =============================================================================

CREATE TABLE planning_day_assignment_type (
    id                              UUID          NOT NULL PRIMARY KEY,
    operating_club_id               UUID          NOT NULL,
    assignment_type_name            VARCHAR(100)  NOT NULL,
    required_nr_of_assignments      SMALLINT      NOT NULL DEFAULT 1,
    created_on                      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id              UUID,
    modified_on                     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id             UUID,
    deleted_on                      TIMESTAMPTZ,
    deleted_by_user_id              UUID,
    CONSTRAINT fk_pdat_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES club (id) ON DELETE RESTRICT,
    CONSTRAINT ck_pdat_required_nr_nonnegative
        CHECK (required_nr_of_assignments >= 0)
);
CREATE INDEX ix_pdat_club ON planning_day_assignment_type (operating_club_id)
    WHERE deleted_on IS NULL;

CREATE TABLE planning_day (
    id                  UUID          NOT NULL PRIMARY KEY,
    operating_club_id   UUID          NOT NULL,
    planning_date       DATE          NOT NULL,
    location_id         UUID          NOT NULL,
    info                TEXT,
    created_on          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id UUID,
    deleted_on          TIMESTAMPTZ,
    deleted_by_user_id  UUID,
    CONSTRAINT fk_pln_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES club (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_pln_location_id
        FOREIGN KEY (location_id)       REFERENCES location (id) ON DELETE RESTRICT,
    CONSTRAINT ck_pln_planning_date_reasonable
        CHECK (planning_date BETWEEN DATE '1990-01-01' AND DATE '2100-01-01')
);
CREATE UNIQUE INDEX ux_pln_club_date_loc
    ON planning_day (operating_club_id, planning_date, location_id)
    WHERE deleted_on IS NULL;

CREATE TABLE planning_day_assignment (
    id                          UUID          NOT NULL PRIMARY KEY,
    operating_club_id           UUID          NOT NULL,
    planning_day_id             UUID          NOT NULL,
    assigned_person_id          UUID          NOT NULL,
    assignment_type_id          UUID          NOT NULL,
    info                        TEXT,
    created_on                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id          UUID,
    modified_on                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id         UUID,
    deleted_on                  TIMESTAMPTZ,
    deleted_by_user_id          UUID,
    CONSTRAINT fk_pda_operating_club_id
        FOREIGN KEY (operating_club_id)  REFERENCES club (id)                          ON DELETE RESTRICT,
    CONSTRAINT fk_pda_planning_day_id
        FOREIGN KEY (planning_day_id)    REFERENCES planning_day (id)                  ON DELETE CASCADE,
    CONSTRAINT fk_pda_assigned_person_id
        FOREIGN KEY (assigned_person_id) REFERENCES person (id)                        ON DELETE RESTRICT,
    CONSTRAINT fk_pda_assignment_type_id
        FOREIGN KEY (assignment_type_id) REFERENCES planning_day_assignment_type (id)  ON DELETE RESTRICT
);
-- covers tombstones: CASCADE join target on planning_day deletion needs to find
-- soft-deleted child rows too so the parent's ON DELETE CASCADE cleans them.
CREATE INDEX ix_pda_planning_day
    ON planning_day_assignment (planning_day_id);
CREATE INDEX ix_pda_person
    ON planning_day_assignment (assigned_person_id, planning_day_id)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_pda_club_person_type
    ON planning_day_assignment (operating_club_id, assigned_person_id, assignment_type_id)
    WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_pda_composite
    ON planning_day_assignment (planning_day_id, assigned_person_id, assignment_type_id)
    WHERE deleted_on IS NULL;


-- =============================================================================
-- 4. Accounting cluster — reference tables
-- =============================================================================

CREATE TABLE accounting_rule_filter_type (
    id              UUID         NOT NULL PRIMARY KEY,
    code            VARCHAR(50)  NOT NULL,
    legacy_int_id   SMALLINT     NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     TEXT
);
CREATE UNIQUE INDEX ux_arft_code            ON accounting_rule_filter_type (code);
CREATE UNIQUE INDEX ux_arft_legacy_int_id   ON accounting_rule_filter_type (legacy_int_id);

CREATE TABLE accounting_unit_type (
    id              UUID         NOT NULL PRIMARY KEY,
    code            VARCHAR(50)  NOT NULL,
    legacy_int_id   SMALLINT     NOT NULL,
    name            VARCHAR(100) NOT NULL,
    short_name      VARCHAR(30)
);
CREATE UNIQUE INDEX ux_aut_code             ON accounting_unit_type (code);
CREATE UNIQUE INDEX ux_aut_legacy_int_id    ON accounting_unit_type (legacy_int_id);


-- =============================================================================
-- 5. Accounting cluster — rule filter aggregate root
-- =============================================================================

CREATE TABLE accounting_rule_filter (
    id                                  UUID          NOT NULL PRIMARY KEY,
    operating_club_id                   UUID          NOT NULL,
    filter_type_id                      UUID          NOT NULL,
    accounting_unit_type_id             UUID,
    rule_filter_name                    VARCHAR(250)  NOT NULL,
    description                         TEXT,
    is_active                           BOOLEAN       NOT NULL DEFAULT true,
    sort_indicator                      INTEGER       NOT NULL DEFAULT 0,
    stop_rule_engine_when_applied       BOOLEAN       NOT NULL DEFAULT false,
    is_charged_to_club_internal         BOOLEAN       NOT NULL DEFAULT false,
    article_target                      VARCHAR(50),
    recipient_target                    VARCHAR(50),
    filter_config                       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_on                          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                  UUID,
    modified_on                         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                 UUID,
    deleted_on                          TIMESTAMPTZ,
    deleted_by_user_id                  UUID,
    CONSTRAINT fk_arf_operating_club_id
        FOREIGN KEY (operating_club_id)         REFERENCES club (id)                            ON DELETE RESTRICT,
    CONSTRAINT fk_arf_filter_type_id
        FOREIGN KEY (filter_type_id)            REFERENCES accounting_rule_filter_type (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_arf_accounting_unit_type_id
        FOREIGN KEY (accounting_unit_type_id)   REFERENCES accounting_unit_type (id)            ON DELETE RESTRICT,
    CONSTRAINT ck_arf_sort_indicator_nonnegative
        CHECK (sort_indicator >= 0)
);
CREATE INDEX ix_arf_club_active_sort
    ON accounting_rule_filter (operating_club_id, is_active, sort_indicator)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_arf_club_type_sort
    ON accounting_rule_filter (operating_club_id, filter_type_id, sort_indicator)
    WHERE is_active = true AND deleted_on IS NULL;
CREATE INDEX ix_arf_filter_config_gin
    ON accounting_rule_filter USING gin (filter_config jsonb_path_ops);
CREATE UNIQUE INDEX ux_arf_club_sort_partial
    ON accounting_rule_filter (operating_club_id, sort_indicator)
    WHERE deleted_on IS NULL;


-- =============================================================================
-- 6. Delivery aggregate root + DeliveryItem internal entity
-- =============================================================================

CREATE TABLE delivery (
    id                                          UUID          NOT NULL PRIMARY KEY,
    operating_club_id                           UUID          NOT NULL,
    process_state_id                            SMALLINT      NOT NULL DEFAULT 10,
    flight_id                                   UUID,
    recipient_person_id                         UUID,
    recipient_name                              VARCHAR(250),
    recipient_firstname                         VARCHAR(100),
    recipient_lastname                          VARCHAR(100),
    recipient_address_line1                     VARCHAR(200),
    recipient_address_line2                     VARCHAR(200),
    recipient_zip_code                          VARCHAR(10),
    recipient_city                              VARCHAR(100),
    recipient_country_name                      VARCHAR(100),
    recipient_person_club_member_number         VARCHAR(20),
    delivery_information                        VARCHAR(250),
    additional_information                      VARCHAR(250),
    delivery_number                             INTEGER,
    delivered_on                                TIMESTAMPTZ,
    batch_id                                    BIGINT        NOT NULL DEFAULT 0,
    created_on                                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                          UUID,
    modified_on                                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                         UUID,
    deleted_on                                  TIMESTAMPTZ,
    deleted_by_user_id                          UUID,
    CONSTRAINT fk_dlv_operating_club_id
        FOREIGN KEY (operating_club_id)     REFERENCES club (id)    ON DELETE RESTRICT,
    CONSTRAINT fk_dlv_flight_id
        FOREIGN KEY (flight_id)             REFERENCES flight (id)  ON DELETE RESTRICT,
    CONSTRAINT fk_dlv_recipient_person_id
        FOREIGN KEY (recipient_person_id)   REFERENCES person (id)  ON DELETE SET NULL,
    CONSTRAINT ck_dlv_process_state_in_set
        CHECK (process_state_id IN (10, 20, 30, 99)),
    CONSTRAINT ck_dlv_delivery_number_positive
        CHECK (delivery_number IS NULL OR delivery_number > 0),
    CONSTRAINT ck_dlv_batch_id_nonnegative
        CHECK (batch_id >= 0),
    -- ck_dlv_delivered_on_not_too_future dropped 2026-05-17 (S-014 rework, M#4):
    -- insert-time bound only (CHECK doesn't re-fire on UPDATE) → poor row-time
    -- invariant. S-064 state-machine enforces delivered_on temporal validity at
    -- the Book transition where the row-time guarantee actually matters.
    CONSTRAINT ck_dlv_booked_requires_number
        CHECK (process_state_id <> 20 OR delivery_number IS NOT NULL),
    CONSTRAINT ck_dlv_booked_requires_delivered_on
        CHECK (process_state_id <> 20 OR delivered_on IS NOT NULL),
    CONSTRAINT ck_dlv_booked_requires_recipient
        CHECK (process_state_id <> 20
               OR (recipient_lastname IS NOT NULL AND recipient_firstname IS NOT NULL))
    -- recipient address-tuple completeness (country / city / zip) NOT enforced
    -- at schema level — legacy invoices may lack these fields and per-club
    -- operator policy varies. S-064 service layer enforces full-address-on-Book
    -- per per-club configuration.
);
CREATE INDEX ix_dlv_club_state_date
    ON delivery (operating_club_id, process_state_id, delivered_on DESC)
    WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_dlv_club_number_partial
    ON delivery (operating_club_id, delivery_number)
    WHERE delivery_number IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_dlv_flight
    ON delivery (flight_id)
    WHERE flight_id IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_dlv_club_batch
    ON delivery (operating_club_id, batch_id)
    WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_dlv_club_batch_partial
    ON delivery (operating_club_id, batch_id)
    WHERE batch_id <> 0 AND deleted_on IS NULL;
CREATE INDEX ix_dlv_recipient_person
    ON delivery (operating_club_id, recipient_person_id)
    WHERE recipient_person_id IS NOT NULL;

CREATE TABLE delivery_item (
    id                          UUID            NOT NULL PRIMARY KEY,
    operating_club_id           UUID            NOT NULL,
    delivery_id                 UUID            NOT NULL,
    position                    INTEGER         NOT NULL,
    article_id                  UUID            NOT NULL,
    article_number              VARCHAR(50)     NOT NULL,
    item_text                   VARCHAR(250),
    additional_information      VARCHAR(250),
    quantity                    NUMERIC(12, 4)  NOT NULL,
    unit_price                  NUMERIC(12, 4)  NOT NULL DEFAULT 0,
    discount_in_percent         INTEGER         NOT NULL DEFAULT 0,
    unit_type_code              VARCHAR(50)     NOT NULL,
    total_amount                NUMERIC(14, 4)  GENERATED ALWAYS AS
        (quantity * unit_price * (100 - discount_in_percent) / 100.0) STORED,
    created_on                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by_user_id          UUID,
    modified_on                 TIMESTAMPTZ     NOT NULL DEFAULT now(),
    modified_by_user_id         UUID,
    deleted_on                  TIMESTAMPTZ,
    deleted_by_user_id          UUID,
    CONSTRAINT fk_dli_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES club (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_dli_delivery_id
        FOREIGN KEY (delivery_id)       REFERENCES delivery (id) ON DELETE CASCADE,
    CONSTRAINT fk_dli_article_id
        FOREIGN KEY (article_id)        REFERENCES article (id)  ON DELETE RESTRICT,
    CONSTRAINT ck_dli_position_positive
        CHECK (position >= 1),
    CONSTRAINT ck_dli_quantity_nonnegative
        CHECK (quantity >= 0),
    CONSTRAINT ck_dli_unit_price_nonnegative
        CHECK (unit_price >= 0),
    CONSTRAINT ck_dli_discount_range
        CHECK (discount_in_percent BETWEEN 0 AND 100)
);
CREATE INDEX ix_dli_delivery
    ON delivery_item (delivery_id)
    INCLUDE (article_id, article_number, quantity, unit_price, total_amount);
CREATE UNIQUE INDEX ux_dli_delivery_pos
    ON delivery_item (delivery_id, position)
    WHERE deleted_on IS NULL;


-- =============================================================================
-- 7. Delivery-creation-test harness (aggregate + internal)
-- =============================================================================

CREATE TABLE delivery_creation_test (
    id                                      UUID          NOT NULL PRIMARY KEY,
    operating_club_id                       UUID          NOT NULL,
    flight_id                               UUID          NOT NULL,
    is_active                               BOOLEAN       NOT NULL DEFAULT true,
    test_name                               VARCHAR(250)  NOT NULL,
    description                             TEXT,
    expected_delivery                       JSONB         NOT NULL,
    expected_matched_filter_ids             BIGINT[]      NOT NULL DEFAULT '{}',
    must_not_create_delivery_for_flight     BOOLEAN       NOT NULL DEFAULT false,
    ignore_recipient_name                   BOOLEAN       NOT NULL DEFAULT false,
    ignore_recipient_address                BOOLEAN       NOT NULL DEFAULT false,
    ignore_recipient_person_id              BOOLEAN       NOT NULL DEFAULT false,
    ignore_recipient_club_member_number     BOOLEAN       NOT NULL DEFAULT false,
    ignore_delivery_information             BOOLEAN       NOT NULL DEFAULT false,
    ignore_additional_information           BOOLEAN       NOT NULL DEFAULT false,
    ignore_item_positioning                 BOOLEAN       NOT NULL DEFAULT false,
    ignore_item_text                        BOOLEAN       NOT NULL DEFAULT false,
    ignore_item_additional_information      BOOLEAN       NOT NULL DEFAULT false,
    last_test_run_on                        TIMESTAMPTZ,
    last_test_successful                    BOOLEAN,
    last_test_result_message                TEXT,
    last_test_created_delivery              JSONB,
    last_test_matched_filter_ids            BIGINT[],
    created_on                              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                      UUID,
    modified_on                             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                     UUID,
    deleted_on                              TIMESTAMPTZ,
    deleted_by_user_id                      UUID,
    CONSTRAINT fk_dct_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES club (id)   ON DELETE RESTRICT,
    CONSTRAINT fk_dct_flight_id
        FOREIGN KEY (flight_id)         REFERENCES flight (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_dct_club_flight_partial
    ON delivery_creation_test (operating_club_id, flight_id)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_dct_club_created
    ON delivery_creation_test (operating_club_id, created_on DESC)
    WHERE deleted_on IS NULL;

CREATE TABLE delivery_creation_test_item (
    id                              UUID            NOT NULL PRIMARY KEY,
    operating_club_id               UUID            NOT NULL,
    delivery_creation_test_id       UUID            NOT NULL,
    position                        INTEGER         NOT NULL,
    article_number                  VARCHAR(50)     NOT NULL,
    item_text                       VARCHAR(250),
    additional_information          VARCHAR(250),
    quantity                        NUMERIC(12, 4)  NOT NULL,
    unit_price                      NUMERIC(12, 4),
    unit_type_code                  VARCHAR(50)     NOT NULL,
    discount_in_percent             INTEGER         NOT NULL DEFAULT 0,
    created_on                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by_user_id              UUID,
    CONSTRAINT fk_dcti_operating_club_id
        FOREIGN KEY (operating_club_id)         REFERENCES club (id)                    ON DELETE RESTRICT,
    CONSTRAINT fk_dcti_delivery_creation_test_id
        FOREIGN KEY (delivery_creation_test_id) REFERENCES delivery_creation_test (id)  ON DELETE CASCADE,
    CONSTRAINT ck_dcti_position_positive
        CHECK (position >= 1),
    CONSTRAINT ck_dcti_quantity_nonnegative
        CHECK (quantity >= 0),
    CONSTRAINT ck_dcti_unit_price_nonnegative
        CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT ck_dcti_discount_range
        CHECK (discount_in_percent BETWEEN 0 AND 100)
);
-- delivery_creation_test_item has no soft-delete (snapshot rows die with parent
-- on CASCADE); index covers all rows by design.
CREATE INDEX ix_dcti_test ON delivery_creation_test_item (delivery_creation_test_id);


-- =============================================================================
-- 8. Operational counter — per-club monotonic delivery numbering
-- =============================================================================

CREATE TABLE club_delivery_number_counter (
    operating_club_id   UUID          NOT NULL PRIMARY KEY,
    next_number         INTEGER       NOT NULL DEFAULT 1,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_cdnc_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES club (id) ON DELETE CASCADE,
    CONSTRAINT ck_cdnc_next_number_positive
        CHECK (next_number >= 1)
);


-- =============================================================================
-- 9. SQL COMMENT ON COLUMN — forensic clarity
-- =============================================================================

-- Aggregate-root id columns (prefix + ADR 0019).
COMMENT ON COLUMN aircraft_reservation.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: arv_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN planning_day.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: pln_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN accounting_rule_filter.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: arf_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN delivery.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: dlv_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN delivery_creation_test.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: dct_<crockford-base32>. See ADR 0019.';

-- Cross-tenant aircraft FK (2026-05-16 amendment).
COMMENT ON COLUMN aircraft_reservation.aircraft_id IS
    'Cross-tenant FK per 2026-05-16 Aircraft-cross-tenant amendment. FK loads NOT @TenantId-filtered. Service layer (S-026/S-064) enforces "may operating_club reserve this aircraft?" via owner / charter / public-rental check. Audit event carries cross_tenant: true when aircraft.owner_club_id != aircraft_reservation.operating_club_id. S-024 leakage CI must include this column in the cross-tenant FK roster.';

COMMENT ON COLUMN aircraft_reservation.pilot_person_id IS
    'Cross-tenant Person FK (sacred cow per ADR 0008). RESTRICT on delete preserves reservation history; DSAR scrubs PII on Person row, not row-delete.';
COMMENT ON COLUMN aircraft_reservation.second_crew_person_id IS
    'Cross-tenant ride-through; SET NULL on delete.';
COMMENT ON COLUMN aircraft_reservation.location_id IS
    'Cross-tenant Location FK (sacred-cow shared resource); RESTRICT on delete.';

COMMENT ON COLUMN planning_day_assignment.assigned_person_id IS
    'Cross-tenant Person FK (sacred cow per ADR 0008). RESTRICT on delete preserves planning history. Service layer (S-064) must verify PersonClub membership before INSERT.';
COMMENT ON COLUMN planning_day.location_id IS
    'Cross-tenant Location FK (sacred-cow shared resource); RESTRICT on delete.';

-- Delivery state machine + numbering + frozen-recipient PII.
COMMENT ON COLUMN delivery.process_state_id IS
    'State machine: 10=Prepared, 20=Booked (terminal-on-mutation, gap-free numbering), 30=Error (retryable), 99=Cancelled. Reshape from legacy flight.process_state_id + delivery.is_further_processed; see S-016 cutover mapping in migration header.';
COMMENT ON COLUMN delivery.delivery_number IS
    'Per-club gap-free invoice number per Swiss OR Art. 957a. Assigned at Book transition only (S-064 allocator via club_delivery_number_counter). Hard DELETE forbidden once non-NULL (soft-delete via deleted_on; gap-detection report at S-027).';
COMMENT ON COLUMN delivery.flight_id IS
    'Same-tenant FK (Flight is TENANT_SCOPED). Service layer (S-022) asserts flight.operating_club_id == delivery.operating_club_id on write. RESTRICT preserves invoice trail integrity.';
COMMENT ON COLUMN delivery.recipient_person_id IS
    'Cross-tenant ride-through; SET NULL on delete. Frozen recipient_* snapshot survives Person deletion per Swiss OR Art. 957a.';
-- Canonical comment for the recipient_* snapshot column family (9 cols total).
-- All other recipient_* columns share this invariant — comment lives on
-- recipient_lastname rather than 9 lockstep copies. recipient_country_name
-- carries its own distinct comment (NOT FK to country).
COMMENT ON COLUMN delivery.recipient_lastname IS
    'Frozen snapshot at invoice booking per Swiss OR Art. 957a (10-year retention). Same invariant applies to recipient_firstname / recipient_name / recipient_address_line1 / recipient_address_line2 / recipient_zip_code / recipient_city / recipient_person_club_member_number. NEVER re-resolve from recipient_person_id. DSAR-exempt once process_state_id >= 20.';
COMMENT ON COLUMN delivery.recipient_country_name IS
    'Frozen snapshot at invoice booking per Swiss OR Art. 957a (10-year retention). NOT FK to country — text is preserved verbatim from the booking-time resolution. Same OR Art. 957a invariant as recipient_lastname (see column comment there).';
COMMENT ON COLUMN delivery.batch_id IS
    'Operational sequence for batch-cancel via DeliveryBatchDeleteRequest. NOT an aggregate UUID (ADR 0019 escape hatch for operational counters). Per-club scoping enforced at schema level via ux_dlv_club_batch_partial UNIQUE (batch_id <> 0 AND deleted_on IS NULL) + service-layer allocator at S-064.';

-- delivery_item — generated total + article snapshot.
COMMENT ON COLUMN delivery_item.article_number IS
    'Frozen snapshot from article.article_number at booking. Invoice integrity per Swiss OR Art. 957a — never re-resolved from article_id.';
COMMENT ON COLUMN delivery_item.total_amount IS
    'GENERATED STORED — drift-proof by construction (Postgres 17); formula visible in column DDL above.';
COMMENT ON COLUMN delivery_item.unit_type_code IS
    'Frozen snapshot from accounting_unit_type.code at booking. Invoice integrity.';

-- accounting_rule_filter jsonb hardening (A03 mitigation).
COMMENT ON COLUMN accounting_rule_filter.filter_config IS
    'jsonb predicate bag. Engine reads typed keys per filter_type_id; allow-list validated at S-064 write path. Jackson default-typing DISABLED globally; NEVER deserialize polymorphic types from this column (A03 injection mitigation). PII redaction: pii_blob: true.';
COMMENT ON COLUMN accounting_rule_filter.filter_type_id IS
    'Discriminator FK to accounting_rule_filter_type (8 canonical rows). Drives the filter_config jsonb shape allow-list at S-064.';

-- delivery_creation_test jsonb hardening (same A03 concern).
COMMENT ON COLUMN delivery_creation_test.expected_delivery IS
    'jsonb snapshot of the expected DeliveryDetails graph (recipient + flight info + items + info fields). PII redaction: pii_blob: true. Jackson default-typing DISABLED.';
COMMENT ON COLUMN delivery_creation_test.last_test_created_delivery IS
    'jsonb snapshot of the most recent test run''s actually-created delivery. PII redaction: pii_blob: true.';
COMMENT ON COLUMN delivery_creation_test.expected_matched_filter_ids IS
    'BIGINT[] of accounting_rule_filter.legacy_int_id values (NOT .id; type is BIGINT, not UUID per ADR 0019) — intentional for S-016 legacy-test-data import where harness fixtures reference the legacy integer ID. NOT FK-enforced — a deleted filter is a legitimate regression signal (the test fails loudly rather than silently dropping).';
COMMENT ON COLUMN delivery_creation_test.flight_id IS
    'Same-tenant FK. CASCADE on flight delete — the harness payload dies with its subject.';


-- =============================================================================
-- 10. Reference-data seeds — fixed canonical UUID v7 literals
-- =============================================================================
-- Generator: next/server/src/test/resources/scripts/GenerateCanonicalUuids.java
-- Ground truth: next/server/src/test/resources/reference-seeds-canonical-uuids.json
-- Re-running the generator must produce bit-identical UUIDs (deterministic by
-- construction). DO NOT regenerate after this migration has shipped to any
-- environment — Flyway checksum-locks V4.

-- accounting_rule_filter_type (8 rows per legacy
-- database/FLSTest/3 insert/3 Insert Static Data.sql; AccountingRuleFilterTypeId
-- values 10, 20, 30, 40, 50, 60, 70, 80).
INSERT INTO accounting_rule_filter_type (id, code, legacy_int_id, name, description) VALUES
    ('019e2e15-2c00-7650-8000-000000004650'::uuid, 'RECIPIENT',           10, 'Recipient accounting rule filter',           'Routes the recipient/invoice target for matching flights'),
    ('019e2e15-2c00-7651-8000-000000004651'::uuid, 'NO_LANDING_TAX',      20, 'No landing tax accounting rule filter',      'Suppresses landing-tax line items for matching flights'),
    ('019e2e15-2c00-7652-8000-000000004652'::uuid, 'FLIGHT_TIME',         30, 'Flight time accounting rule filter',         'Emits flight-time-based line item for matching flights'),
    ('019e2e15-2c00-7653-8000-000000004653'::uuid, 'INSTRUCTOR_FEE',      40, 'Instructor fee accounting rule filter',      'Emits instructor-fee line item for matching flights'),
    ('019e2e15-2c00-7654-8000-000000004654'::uuid, 'ADDITIONAL_FUEL_FEE', 50, 'Additional fuel fee accounting rule filter', 'Emits additional-fuel surcharge line item'),
    ('019e2e15-2c00-7655-8000-000000004655'::uuid, 'LANDING_TAX',         60, 'Landing tax accounting rule filter',         'Emits landing-tax line item for matching flights'),
    ('019e2e15-2c00-7656-8000-000000004656'::uuid, 'VSF_FEE',             70, 'VSF fee accounting rule filter',             'Emits Swiss VSF association fee line item'),
    ('019e2e15-2c00-7657-8000-000000004657'::uuid, 'ENGINE_TIME',         80, 'Engine time accounting rule filter',         'Emits engine-time-based line item for matching flights');

-- accounting_unit_type (4 rows per legacy
-- database/FLSTest/3 insert/3 Insert Static Data.sql; AccountingUnitTypeId
-- values 10, 20, 30, 40).
INSERT INTO accounting_unit_type (id, code, legacy_int_id, name, short_name) VALUES
    ('019e2e15-2c00-7a38-8000-000000004a38'::uuid, 'MINUTES',         10, 'Minuten',         'Min'),
    ('019e2e15-2c00-7a39-8000-000000004a39'::uuid, 'SECONDS',         20, 'Sekunden',        'Sec'),
    ('019e2e15-2c00-7a3a-8000-000000004a3a'::uuid, 'LANDINGS',        30, 'Landungen',       'Ldgs'),
    ('019e2e15-2c00-7a3b-8000-000000004a3b'::uuid, 'START_OR_FLIGHT', 40, 'Start oder Flug', 'StartOrFlight');


-- =============================================================================
-- 11. Update app_meta sentinel to reflect the S-014 generation.
-- =============================================================================

UPDATE app_meta SET meta_value = 'S-014' WHERE meta_key = 'schema_baseline_version';
