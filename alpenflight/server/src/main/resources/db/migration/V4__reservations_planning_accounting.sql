


CREATE EXTENSION IF NOT EXISTS btree_gist;



CREATE TABLE t_aircraft_reservation_type (
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
        FOREIGN KEY (operating_club_id) REFERENCES t_club (id) ON DELETE RESTRICT
);
CREATE INDEX ix_arvt_club ON t_aircraft_reservation_type (operating_club_id)
    WHERE deleted_on IS NULL;

CREATE TABLE t_aircraft_reservation (
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
        FOREIGN KEY (operating_club_id)       REFERENCES t_club (id)                    ON DELETE RESTRICT,
    CONSTRAINT fk_arv_aircraft_id
        FOREIGN KEY (aircraft_id)             REFERENCES t_aircraft (id)                ON DELETE RESTRICT,
    CONSTRAINT fk_arv_pilot_person_id
        FOREIGN KEY (pilot_person_id)         REFERENCES t_person (id)                  ON DELETE RESTRICT,
    CONSTRAINT fk_arv_second_crew_person_id
        FOREIGN KEY (second_crew_person_id)   REFERENCES t_person (id)                  ON DELETE SET NULL,
    CONSTRAINT fk_arv_location_id
        FOREIGN KEY (location_id)             REFERENCES t_location (id)                ON DELETE RESTRICT,
    CONSTRAINT fk_arv_reservation_type_id
        FOREIGN KEY (reservation_type_id)     REFERENCES t_aircraft_reservation_type (id) ON DELETE RESTRICT,
    CONSTRAINT fk_arv_flight_type_id
        FOREIGN KEY (flight_type_id)          REFERENCES t_flight_type (id)             ON DELETE RESTRICT
);
CREATE INDEX ix_arv_aircraft_range_gist
    ON t_aircraft_reservation USING gist (aircraft_id, reservation_range)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_arv_club_start_end
    ON t_aircraft_reservation (operating_club_id, reservation_start, reservation_end)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_arv_pilot
    ON t_aircraft_reservation (pilot_person_id, reservation_start DESC)
    WHERE pilot_person_id IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_arv_location
    ON t_aircraft_reservation (operating_club_id, location_id, reservation_start);



CREATE TABLE t_planning_day_assignment_type (
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
        FOREIGN KEY (operating_club_id) REFERENCES t_club (id) ON DELETE RESTRICT
);
CREATE INDEX ix_pdat_club ON t_planning_day_assignment_type (operating_club_id)
    WHERE deleted_on IS NULL;

CREATE TABLE t_planning_day (
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
        FOREIGN KEY (operating_club_id) REFERENCES t_club (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_pln_location_id
        FOREIGN KEY (location_id)       REFERENCES t_location (id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX ux_pln_club_date_loc
    ON t_planning_day (operating_club_id, planning_date, location_id)
    WHERE deleted_on IS NULL;

CREATE TABLE t_planning_day_assignment (
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
        FOREIGN KEY (operating_club_id)  REFERENCES t_club (id)                          ON DELETE RESTRICT,
    CONSTRAINT fk_pda_planning_day_id
        FOREIGN KEY (planning_day_id)    REFERENCES t_planning_day (id)                  ON DELETE CASCADE,
    CONSTRAINT fk_pda_assigned_person_id
        FOREIGN KEY (assigned_person_id) REFERENCES t_person (id)                        ON DELETE RESTRICT,
    CONSTRAINT fk_pda_assignment_type_id
        FOREIGN KEY (assignment_type_id) REFERENCES t_planning_day_assignment_type (id)  ON DELETE RESTRICT
);
CREATE INDEX ix_pda_planning_day
    ON t_planning_day_assignment (planning_day_id);
CREATE INDEX ix_pda_person
    ON t_planning_day_assignment (assigned_person_id, planning_day_id)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_pda_club_person_type
    ON t_planning_day_assignment (operating_club_id, assigned_person_id, assignment_type_id)
    WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_pda_composite
    ON t_planning_day_assignment (planning_day_id, assigned_person_id, assignment_type_id)
    WHERE deleted_on IS NULL;



CREATE TABLE t_accounting_rule_filter_type (
    id              UUID         NOT NULL PRIMARY KEY,
    code            VARCHAR(50)  NOT NULL,
    legacy_int_id   SMALLINT     NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     TEXT
);
CREATE UNIQUE INDEX ux_arft_code            ON t_accounting_rule_filter_type (code);
CREATE UNIQUE INDEX ux_arft_legacy_int_id   ON t_accounting_rule_filter_type (legacy_int_id);

CREATE TABLE t_accounting_unit_type (
    id              UUID         NOT NULL PRIMARY KEY,
    code            VARCHAR(50)  NOT NULL,
    legacy_int_id   SMALLINT     NOT NULL,
    name            VARCHAR(100) NOT NULL,
    short_name      VARCHAR(30)
);
CREATE UNIQUE INDEX ux_aut_code             ON t_accounting_unit_type (code);
CREATE UNIQUE INDEX ux_aut_legacy_int_id    ON t_accounting_unit_type (legacy_int_id);



CREATE TABLE t_accounting_rule_filter (
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
        FOREIGN KEY (operating_club_id)         REFERENCES t_club (id)                            ON DELETE RESTRICT,
    CONSTRAINT fk_arf_filter_type_id
        FOREIGN KEY (filter_type_id)            REFERENCES t_accounting_rule_filter_type (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_arf_accounting_unit_type_id
        FOREIGN KEY (accounting_unit_type_id)   REFERENCES t_accounting_unit_type (id)            ON DELETE RESTRICT
);
CREATE INDEX ix_arf_club_active_sort
    ON t_accounting_rule_filter (operating_club_id, is_active, sort_indicator)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_arf_club_type_sort
    ON t_accounting_rule_filter (operating_club_id, filter_type_id, sort_indicator)
    WHERE is_active = true AND deleted_on IS NULL;
CREATE INDEX ix_arf_filter_config_gin
    ON t_accounting_rule_filter USING gin (filter_config jsonb_path_ops);
CREATE UNIQUE INDEX ux_arf_club_sort_partial
    ON t_accounting_rule_filter (operating_club_id, sort_indicator)
    WHERE deleted_on IS NULL;



CREATE TABLE t_delivery (
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
        FOREIGN KEY (operating_club_id)     REFERENCES t_club (id)    ON DELETE RESTRICT,
    CONSTRAINT fk_dlv_flight_id
        FOREIGN KEY (flight_id)             REFERENCES t_flight (id)  ON DELETE RESTRICT,
    CONSTRAINT fk_dlv_recipient_person_id
        FOREIGN KEY (recipient_person_id)   REFERENCES t_person (id)  ON DELETE SET NULL
);
CREATE INDEX ix_dlv_club_state_date
    ON t_delivery (operating_club_id, process_state_id, delivered_on DESC)
    WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_dlv_club_number_partial
    ON t_delivery (operating_club_id, delivery_number)
    WHERE delivery_number IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_dlv_flight
    ON t_delivery (flight_id)
    WHERE flight_id IS NOT NULL AND deleted_on IS NULL;
CREATE INDEX ix_dlv_club_batch
    ON t_delivery (operating_club_id, batch_id)
    WHERE deleted_on IS NULL;
CREATE UNIQUE INDEX ux_dlv_club_batch_partial
    ON t_delivery (operating_club_id, batch_id)
    WHERE batch_id <> 0 AND deleted_on IS NULL;
CREATE INDEX ix_dlv_recipient_person
    ON t_delivery (operating_club_id, recipient_person_id)
    WHERE recipient_person_id IS NOT NULL;

CREATE TABLE t_delivery_item (
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
    created_on                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by_user_id          UUID,
    modified_on                 TIMESTAMPTZ     NOT NULL DEFAULT now(),
    modified_by_user_id         UUID,
    deleted_on                  TIMESTAMPTZ,
    deleted_by_user_id          UUID,
    CONSTRAINT fk_dli_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES t_club (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_dli_delivery_id
        FOREIGN KEY (delivery_id)       REFERENCES t_delivery (id) ON DELETE CASCADE,
    CONSTRAINT fk_dli_article_id
        FOREIGN KEY (article_id)        REFERENCES t_article (id)  ON DELETE RESTRICT
);
CREATE INDEX ix_dli_delivery
    ON t_delivery_item (delivery_id)
    INCLUDE (article_id, article_number, quantity, unit_price);
CREATE UNIQUE INDEX ux_dli_delivery_pos
    ON t_delivery_item (delivery_id, position)
    WHERE deleted_on IS NULL;



CREATE TABLE t_delivery_creation_test (
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
        FOREIGN KEY (operating_club_id) REFERENCES t_club (id)   ON DELETE RESTRICT,
    CONSTRAINT fk_dct_flight_id
        FOREIGN KEY (flight_id)         REFERENCES t_flight (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_dct_club_flight_partial
    ON t_delivery_creation_test (operating_club_id, flight_id)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_dct_club_created
    ON t_delivery_creation_test (operating_club_id, created_on DESC)
    WHERE deleted_on IS NULL;

CREATE TABLE t_delivery_creation_test_item (
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
        FOREIGN KEY (operating_club_id)         REFERENCES t_club (id)                    ON DELETE RESTRICT,
    CONSTRAINT fk_dcti_delivery_creation_test_id
        FOREIGN KEY (delivery_creation_test_id) REFERENCES t_delivery_creation_test (id)  ON DELETE CASCADE
);
CREATE INDEX ix_dcti_test ON t_delivery_creation_test_item (delivery_creation_test_id);



CREATE TABLE t_club_delivery_number_counter (
    operating_club_id   UUID          NOT NULL PRIMARY KEY,
    next_number         INTEGER       NOT NULL DEFAULT 1,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_cdnc_operating_club_id
        FOREIGN KEY (operating_club_id) REFERENCES t_club (id) ON DELETE CASCADE
);



COMMENT ON COLUMN t_aircraft_reservation.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: arv_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN t_planning_day.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: pln_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN t_accounting_rule_filter.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: arf_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN t_delivery.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: dlv_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN t_delivery_creation_test.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: dct_<crockford-base32>. See ADR 0019.';

COMMENT ON COLUMN t_aircraft_reservation.aircraft_id IS
    'Cross-tenant FK per 2026-05-16 Aircraft-cross-tenant amendment. FK loads NOT @TenantId-filtered. Service layer (S-026/S-064) enforces "may operating_club reserve this aircraft?" via owner / charter / public-rental check. Audit event carries cross_tenant: true when aircraft.owner_club_id != aircraft_reservation.operating_club_id. S-024 leakage CI must include this column in the cross-tenant FK roster.';

COMMENT ON COLUMN t_aircraft_reservation.pilot_person_id IS
    'Cross-tenant Person FK (sacred cow per ADR 0008). RESTRICT on delete preserves reservation history; DSAR scrubs PII on Person row, not row-delete.';
COMMENT ON COLUMN t_aircraft_reservation.second_crew_person_id IS
    'Cross-tenant ride-through; SET NULL on delete.';
COMMENT ON COLUMN t_aircraft_reservation.location_id IS
    'Cross-tenant Location FK (sacred-cow shared resource); RESTRICT on delete.';

COMMENT ON COLUMN t_planning_day_assignment.assigned_person_id IS
    'Cross-tenant Person FK (sacred cow per ADR 0008). RESTRICT on delete preserves planning history. Service layer (S-064) must verify PersonClub membership before INSERT.';
COMMENT ON COLUMN t_planning_day.location_id IS
    'Cross-tenant Location FK (sacred-cow shared resource); RESTRICT on delete.';

COMMENT ON COLUMN t_delivery.process_state_id IS
    'State machine: 10=Prepared, 20=Booked (terminal-on-mutation, gap-free numbering), 30=Error (retryable), 99=Cancelled. Reshape from legacy flight.process_state_id + delivery.is_further_processed; see S-016 cutover mapping in migration header.';
COMMENT ON COLUMN t_delivery.delivery_number IS
    'Per-club gap-free invoice number per Swiss OR Art. 957a. Assigned at Book transition only (S-064 allocator via club_delivery_number_counter). Hard DELETE forbidden once non-NULL (soft-delete via deleted_on; gap-detection report at S-027).';
COMMENT ON COLUMN t_delivery.flight_id IS
    'Same-tenant FK (Flight is TENANT_SCOPED). Service layer (S-022) asserts flight.operating_club_id == delivery.operating_club_id on write. RESTRICT preserves invoice trail integrity.';
COMMENT ON COLUMN t_delivery.recipient_person_id IS
    'Cross-tenant ride-through; SET NULL on delete. Frozen recipient_* snapshot survives Person deletion per Swiss OR Art. 957a.';
COMMENT ON COLUMN t_delivery.recipient_lastname IS
    'Frozen snapshot at invoice booking per Swiss OR Art. 957a (10-year retention). Same invariant applies to recipient_firstname / recipient_name / recipient_address_line1 / recipient_address_line2 / recipient_zip_code / recipient_city / recipient_person_club_member_number. NEVER re-resolve from recipient_person_id. DSAR-exempt once process_state_id >= 20.';
COMMENT ON COLUMN t_delivery.recipient_country_name IS
    'Frozen snapshot at invoice booking per Swiss OR Art. 957a (10-year retention). NOT FK to country — text is preserved verbatim from the booking-time resolution. Same OR Art. 957a invariant as recipient_lastname (see column comment there).';
COMMENT ON COLUMN t_delivery.batch_id IS
    'Operational sequence for batch-cancel via DeliveryBatchDeleteRequest. NOT an aggregate UUID (ADR 0019 escape hatch for operational counters). Per-club scoping enforced at schema level via ux_dlv_club_batch_partial UNIQUE (batch_id <> 0 AND deleted_on IS NULL) + service-layer allocator at S-064.';

COMMENT ON COLUMN t_delivery_item.article_number IS
    'Frozen snapshot from article.article_number at booking. Invoice integrity per Swiss OR Art. 957a — never re-resolved from article_id.';
COMMENT ON COLUMN t_delivery_item.unit_type_code IS
    'Frozen snapshot from accounting_unit_type.code at booking. Invoice integrity.';

COMMENT ON COLUMN t_accounting_rule_filter.filter_config IS
    'jsonb predicate bag. Engine reads typed keys per filter_type_id; allow-list validated at S-064 write path. Jackson default-typing DISABLED globally; NEVER deserialize polymorphic types from this column (A03 injection mitigation). PII redaction: pii_blob: true.';
COMMENT ON COLUMN t_accounting_rule_filter.filter_type_id IS
    'Discriminator FK to accounting_rule_filter_type (8 canonical rows). Drives the filter_config jsonb shape allow-list at S-064.';

COMMENT ON COLUMN t_delivery_creation_test.expected_delivery IS
    'jsonb snapshot of the expected DeliveryDetails graph (recipient + flight info + items + info fields). PII redaction: pii_blob: true. Jackson default-typing DISABLED.';
COMMENT ON COLUMN t_delivery_creation_test.last_test_created_delivery IS
    'jsonb snapshot of the most recent test run''s actually-created delivery. PII redaction: pii_blob: true.';
COMMENT ON COLUMN t_delivery_creation_test.expected_matched_filter_ids IS
    'BIGINT[] of accounting_rule_filter.legacy_int_id values (NOT .id; type is BIGINT, not UUID per ADR 0019) — intentional for S-016 legacy-test-data import where harness fixtures reference the legacy integer ID. NOT FK-enforced — a deleted filter is a legitimate regression signal (the test fails loudly rather than silently dropping).';
COMMENT ON COLUMN t_delivery_creation_test.flight_id IS
    'Same-tenant FK. CASCADE on flight delete — the harness payload dies with its subject.';



INSERT INTO t_accounting_rule_filter_type (id, code, legacy_int_id, name, description) VALUES
    ('019e2e15-2c00-7650-8000-000000004650'::uuid, 'RECIPIENT',           10, 'Recipient accounting rule filter',           'Routes the recipient/invoice target for matching flights'),
    ('019e2e15-2c00-7651-8000-000000004651'::uuid, 'NO_LANDING_TAX',      20, 'No landing tax accounting rule filter',      'Suppresses landing-tax line items for matching flights'),
    ('019e2e15-2c00-7652-8000-000000004652'::uuid, 'FLIGHT_TIME',         30, 'Flight time accounting rule filter',         'Emits flight-time-based line item for matching flights'),
    ('019e2e15-2c00-7653-8000-000000004653'::uuid, 'INSTRUCTOR_FEE',      40, 'Instructor fee accounting rule filter',      'Emits instructor-fee line item for matching flights'),
    ('019e2e15-2c00-7654-8000-000000004654'::uuid, 'ADDITIONAL_FUEL_FEE', 50, 'Additional fuel fee accounting rule filter', 'Emits additional-fuel surcharge line item'),
    ('019e2e15-2c00-7655-8000-000000004655'::uuid, 'LANDING_TAX',         60, 'Landing tax accounting rule filter',         'Emits landing-tax line item for matching flights'),
    ('019e2e15-2c00-7656-8000-000000004656'::uuid, 'VSF_FEE',             70, 'VSF fee accounting rule filter',             'Emits Swiss VSF association fee line item'),
    ('019e2e15-2c00-7657-8000-000000004657'::uuid, 'ENGINE_TIME',         80, 'Engine time accounting rule filter',         'Emits engine-time-based line item for matching flights');

INSERT INTO t_accounting_unit_type (id, code, legacy_int_id, name, short_name) VALUES
    ('019e2e15-2c00-7a38-8000-000000004a38'::uuid, 'MINUTES',         10, 'Minuten',         'Min'),
    ('019e2e15-2c00-7a39-8000-000000004a39'::uuid, 'SECONDS',         20, 'Sekunden',        'Sec'),
    ('019e2e15-2c00-7a3a-8000-000000004a3a'::uuid, 'LANDINGS',        30, 'Landungen',       'Ldgs'),
    ('019e2e15-2c00-7a3b-8000-000000004a3b'::uuid, 'START_OR_FLIGHT', 40, 'Start oder Flug', 'StartOrFlight');
