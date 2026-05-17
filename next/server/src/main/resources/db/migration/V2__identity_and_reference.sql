-- V2__identity_and_reference.sql
--
-- S-012: Identity + reference data baseline (19 tables).
--
-- This migration is the first real schema content; V1 was a placeholder.
-- Once V2 is applied to any environment its checksum is locked. Adding /
-- removing / amending content here would require flyway:repair on every
-- affected DB. Convention: never amend a shipped migration — ship V3.
--
-- ID strategy (ADR 0019):
--   * Every PK is `uuid NOT NULL PRIMARY KEY`. Postgres native 16-byte type.
--   * Application generates IDs via Hibernate 7 + uuid-creator
--     UuidCreator.getTimeOrderedEpoch() — wired at S-022.
--   * NO `DEFAULT gen_random_uuid()` on any PK column. The application-owns-
--     generation contract must not be bypassed.
--   * Aggregate-root rows (person/club/user/...) carry a 3-letter prefix at
--     every external boundary (REST URLs, JSON, structured logs, audit-log
--     target.id). The prefix is a presentation concern — DB stays pure uuid.
--     See COMMENT ON COLUMN clauses near the bottom.
--   * Internal entities (person_club, user_role, club_extension, ...) keep
--     raw UUIDs — no prefix; they rarely cross boundaries.
--
-- Aggregate composition (ADR 0018):
--   * Aggregate roots: club, person, user.
--   * Internal entities:
--       - under Person: person_club
--       - under Club: club_extension, member_state, person_category,
--         email_template/extension_value rows where club_id IS NOT NULL
--       - under User: user_role
--   * Plain JPA / system-global lookups: country, language, start_type,
--     club_state, role, extension_type, length_unit_type,
--     elevation_unit_type, counter_unit_type, and system-default
--     email_template/extension_value rows where club_id IS NULL.
--
-- Multi-tenancy (ADR 0008):
--   * Tenant discriminator column is `club_id uuid` on TENANT_SCOPED tables.
--     S-022 wires Hibernate `@TenantId` on the matching entity properties.
--   * Person + PersonClub are cross-tenant by design (sacred cow): no
--     @TenantId; cross-club lookups by primary key remain functional.
--   * User is the principal subject: carries `club_id` (home club) but NOT
--     @TenantId-filtered. SQL comment on user.club_id flags this so an
--     S-022 implementer doesn't accidentally add @TenantId there.
--
-- person_club PK reshape (legacy → new):
--   * Legacy: composite PK (PersonId, ClubId).
--   * New: surrogate `id uuid PRIMARY KEY` + partial UNIQUE
--     (person_id, club_id) WHERE deleted_on IS NULL. JPA composite-key
--     handling is awkward; the surrogate gives every JPA repository a
--     uniform `findById(UUID)` contract.
--
-- Reference-data seeds: fixed canonical UUID v7 literals generated once via
-- next/server/src/test/resources/scripts/GenerateCanonicalUuids.java
-- (committed; deterministic; re-running produces bit-identical output).
-- Same UUIDs across every installation forever — forensic traceability
-- via grep. S-016 cutover builds the legacy-int / legacy-Guid → canonical
-- UUID lookup table from these literals.
--
-- Per-club seeds NOT in this migration: member_state, person_category.
-- These are TENANT_SCOPED (legacy carries ClubId NOT NULL); S-016 seeds
-- them per club from legacy data during cutover.
--
-- ============================================================================
-- AUTH ARTIFACTS — OWNED BY KEYCLOAK (ADR 0007). DO NOT ADD TO `user`.
-- ============================================================================
-- The `user` table here is a principal-subject row that maps an FLS identity
-- to a Keycloak `sub` (uuid). It is NOT an authentication store.
--
-- DO NOT add columns named `password_hash`, `password_salt`, `password`,
-- `refresh_token`, `access_token`, `mfa_secret`, `totp_seed`, `security_stamp`,
-- or any equivalent. Keycloak owns password storage, rotation, MFA, and
-- session lifecycle. The S-052 backfill that populates `keycloak_sub`
-- finalises the contract. A future PR adding a password-shaped column on
-- `user` is wrong — flag it at PR review and re-direct to Keycloak realm
-- config.
-- ============================================================================

-- =============================================================================
-- 1. Reference tables (no FKs; loaded first so subsequent FKs can resolve)
-- =============================================================================

CREATE TABLE country (
    id          UUID         NOT NULL PRIMARY KEY,
    iso2_code   CHAR(2)      NOT NULL,
    iso3_code   CHAR(3)      NOT NULL,
    name        VARCHAR(100) NOT NULL,
    full_name   VARCHAR(250)
    -- ck_country_iso2_upper / ck_country_iso3_upper removed per ADR 0022
    -- directive 2: case enforcement is a value-object invariant
    -- (Country.iso2Code() / iso3Code() constructor).
);
CREATE UNIQUE INDEX ux_country_iso2 ON country (iso2_code);
CREATE UNIQUE INDEX ux_country_iso3 ON country (iso3_code);

CREATE TABLE language (
    id          UUID         NOT NULL PRIMARY KEY,
    code        VARCHAR(10)  NOT NULL,
    name        VARCHAR(50)  NOT NULL
    -- ck_language_bcp47 removed per ADR 0022 directive 2: BCP-47 format
    -- enforcement is a value-object invariant (Language.code()) at S-022.
);
CREATE UNIQUE INDEX ux_language_code ON language (code);

CREATE TABLE club_state (
    id    UUID         NOT NULL PRIMARY KEY,
    code  VARCHAR(32)  NOT NULL,
    name  VARCHAR(50)  NOT NULL
);
CREATE UNIQUE INDEX ux_club_state_code ON club_state (code);

CREATE TABLE start_type (
    id                    UUID         NOT NULL PRIMARY KEY,
    code                  VARCHAR(32)  NOT NULL,
    name                  VARCHAR(100) NOT NULL,
    applicable_categories TEXT[]       NOT NULL
    -- ADR 0020 rule 4: SET-MEMBERSHIP shape. Replaces the legacy
    -- is_for_glider/is_for_tow/is_for_motor boolean trio. No DB CHECK on
    -- subset / non-empty — Java enum + service layer are the only enforcer
    -- so adding a category requires no migration in lock-step (ADR 0020).
);
CREATE UNIQUE INDEX ux_start_type_code ON start_type (code);

CREATE TABLE length_unit_type (
    id         UUID         NOT NULL PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    short_name VARCHAR(20),
    comment    VARCHAR(200)
);
CREATE UNIQUE INDEX ux_length_unit_type_code ON length_unit_type (code);

CREATE TABLE elevation_unit_type (
    id         UUID         NOT NULL PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    short_name VARCHAR(20),
    comment    VARCHAR(200)
);
CREATE UNIQUE INDEX ux_elevation_unit_type_code ON elevation_unit_type (code);

CREATE TABLE counter_unit_type (
    id         UUID         NOT NULL PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    short_name VARCHAR(20),
    comment    VARCHAR(200)
);
CREATE UNIQUE INDEX ux_counter_unit_type_code ON counter_unit_type (code);

CREATE TABLE extension_type (
    id      UUID         NOT NULL PRIMARY KEY,
    code    VARCHAR(32)  NOT NULL,
    name    VARCHAR(100) NOT NULL,
    comment TEXT
);
CREATE UNIQUE INDEX ux_extension_type_code ON extension_type (code);

CREATE TABLE role (
    id          UUID         NOT NULL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    description VARCHAR(250)
);
CREATE UNIQUE INDEX ux_role_code ON role (code);


-- =============================================================================
-- 2. Aggregate roots: club, person (cross-tenant), user (principal subject)
-- =============================================================================

CREATE TABLE club (
    id                                            UUID          NOT NULL PRIMARY KEY,
    clubname                                      VARCHAR(100)  NOT NULL,
    club_key                                      VARCHAR(10)   NOT NULL,
    address                                       VARCHAR(200),
    zip                                           VARCHAR(10),
    city                                          VARCHAR(100),
    country_id                                    UUID          NOT NULL,
    phone                                         VARCHAR(30),
    fax_number                                    VARCHAR(30),
    email                                         VARCHAR(256),
    web_page                                      VARCHAR(200),
    contact                                       VARCHAR(100),
    club_state_id                                 UUID          NOT NULL,
    send_aircraft_statistic_report_to             VARCHAR(250),
    send_planning_day_info_mail_to                VARCHAR(250),
    send_delivery_mail_export_to                  VARCHAR(250),
    send_trial_flight_registration_operator_email VARCHAR(250),
    send_passenger_flight_registration_operator_email VARCHAR(250),
    reply_to_email_address                        VARCHAR(250),
    run_delivery_creation_job                     BOOLEAN       NOT NULL DEFAULT false,
    run_delivery_mail_export_job                  BOOLEAN       NOT NULL DEFAULT false,
    last_person_synchronisation_on                TIMESTAMPTZ,
    last_delivery_synchronisation_on              TIMESTAMPTZ,
    last_article_synchronisation_on               TIMESTAMPTZ,
    is_club_member_number_readonly                BOOLEAN       NOT NULL DEFAULT false,
    created_on                                    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                            UUID,
    modified_on                                   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                           UUID,
    deleted_on                                    TIMESTAMPTZ,
    deleted_by_user_id                            UUID,
    CONSTRAINT fk_club_country_id    FOREIGN KEY (country_id)    REFERENCES country (id)    ON DELETE RESTRICT,
    CONSTRAINT fk_club_club_state_id FOREIGN KEY (club_state_id) REFERENCES club_state (id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX ux_club_key      ON club (club_key);
CREATE        INDEX ix_club_state    ON club (club_state_id);
CREATE        INDEX ix_club_country  ON club (country_id);

CREATE TABLE person (
    id                                  UUID          NOT NULL PRIMARY KEY,
    lastname                            VARCHAR(100)  NOT NULL,
    firstname                           VARCHAR(100)  NOT NULL,
    midname                             VARCHAR(100),
    company_name                        VARCHAR(100),
    address_line1                       VARCHAR(200),
    address_line2                       VARCHAR(200),
    zip                                 VARCHAR(10),
    city                                VARCHAR(100),
    region                              VARCHAR(100),
    country_id                          UUID,
    private_phone                       VARCHAR(30),
    mobile_phone                        VARCHAR(30),
    business_phone                      VARCHAR(30),
    fax_number                          VARCHAR(30),
    email_private                       VARCHAR(256),
    email_business                      VARCHAR(256),
    prefer_mail_to_business_mail        BOOLEAN       NOT NULL DEFAULT false,
    birthday                            DATE,
    has_motor_pilot_licence             BOOLEAN       NOT NULL DEFAULT false,
    has_tow_pilot_licence               BOOLEAN       NOT NULL DEFAULT false,
    has_glider_instructor_licence       BOOLEAN       NOT NULL DEFAULT false,
    has_glider_pilot_licence            BOOLEAN       NOT NULL DEFAULT false,
    has_glider_trainee_licence          BOOLEAN       NOT NULL DEFAULT false,
    has_glider_pax_licence              BOOLEAN       NOT NULL DEFAULT false,
    has_tmg_licence                     BOOLEAN       NOT NULL DEFAULT false,
    has_winch_operator_licence          BOOLEAN       NOT NULL DEFAULT false,
    has_motor_instructor_licence        BOOLEAN       NOT NULL DEFAULT false,
    has_part_m_licence                  BOOLEAN       NOT NULL DEFAULT false,
    licence_number                      VARCHAR(20),
    medical_class1_expire_date          DATE,
    medical_class2_expire_date          DATE,
    medical_lapl_expire_date            DATE,
    glider_instructor_licence_expire_date DATE,
    motor_instructor_licence_expire_date DATE,
    part_m_licence_expire_date          DATE,
    has_glider_towing_start_permission  BOOLEAN       NOT NULL DEFAULT false,
    has_glider_self_start_permission    BOOLEAN       NOT NULL DEFAULT false,
    has_glider_winch_start_permission   BOOLEAN       NOT NULL DEFAULT false,
    spot_link                           VARCHAR(250),
    receive_owned_aircraft_statistic_reports BOOLEAN  NOT NULL DEFAULT false,
    enable_address                      BOOLEAN       NOT NULL DEFAULT false,
    is_fast_entry_record                BOOLEAN       NOT NULL DEFAULT false,
    created_on                          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                  UUID,
    modified_on                         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                 UUID,
    deleted_on                          TIMESTAMPTZ,
    deleted_by_user_id                  UUID,
    CONSTRAINT fk_person_country_id FOREIGN KEY (country_id) REFERENCES country (id) ON DELETE SET NULL,
    -- ck_person_birthday_not_future removed per ADR 0022 directive 2:
    -- date-bound sanity check is a value-object invariant (Birthday VO at S-022).
    CONSTRAINT ck_person_email_private_shape
        CHECK (email_private IS NULL OR email_private LIKE '%_@_%._%'),
    CONSTRAINT ck_person_email_business_shape
        CHECK (email_business IS NULL OR email_business LIKE '%_@_%._%')
);
COMMENT ON CONSTRAINT ck_person_email_private_shape ON person IS
    'ADR 0022 retained: input-shape defense-in-depth (a malformed e-mail bypasses '
    'the Email value-object only via direct SQL; cheap belt-and-braces guard).';
COMMENT ON CONSTRAINT ck_person_email_business_shape ON person IS
    'ADR 0022 retained: input-shape defense-in-depth — pairs with the private '
    'e-mail shape check; same rationale.';
CREATE INDEX ix_person_name ON person (lastname, firstname);
CREATE INDEX ix_person_email_priv_lower
    ON person (lower(email_private))
    WHERE email_private IS NOT NULL;

-- user is a reserved word in Postgres; quote consistently.
CREATE TABLE "user" (
    id                          UUID          NOT NULL PRIMARY KEY,
    club_id                     UUID          NOT NULL,
    username                    VARCHAR(256)  NOT NULL,
    friendly_name               VARCHAR(100)  NOT NULL,
    person_id                   UUID,
    notification_email          VARCHAR(256)  NOT NULL,
    email_confirmed             BOOLEAN       NOT NULL DEFAULT false,
    phone_number                VARCHAR(30),
    phone_number_confirmed      BOOLEAN       NOT NULL DEFAULT false,
    two_factor_enabled          BOOLEAN       NOT NULL DEFAULT false,
    lockout_enabled             BOOLEAN       NOT NULL DEFAULT false,
    lockout_end_date_utc        TIMESTAMPTZ,
    access_failed_count         INTEGER       NOT NULL DEFAULT 0,
    remarks                     VARCHAR(250),
    account_state_id            SMALLINT      NOT NULL DEFAULT 1,
    -- Legacy `last_password_change_on` + `force_password_change_next` are
    -- intentionally NOT carried over. Keycloak owns password lifecycle
    -- (ADR 0007). See the header block: no password-shaped columns on `user`.
    language_id                 UUID          NOT NULL,
    keycloak_sub                UUID,
    created_on                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id          UUID,
    modified_on                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id         UUID,
    deleted_on                  TIMESTAMPTZ,
    deleted_by_user_id          UUID,
    CONSTRAINT fk_user_club_id     FOREIGN KEY (club_id)     REFERENCES club (id)     ON DELETE RESTRICT,
    CONSTRAINT fk_user_person_id   FOREIGN KEY (person_id)   REFERENCES person (id)   ON DELETE SET NULL,
    CONSTRAINT fk_user_language_id FOREIGN KEY (language_id) REFERENCES language (id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX ux_user_username_lower ON "user" (lower(username));
CREATE UNIQUE INDEX ux_user_keycloak_sub   ON "user" (keycloak_sub) WHERE keycloak_sub IS NOT NULL;
CREATE        INDEX ix_user_club           ON "user" (club_id);
CREATE        INDEX ix_user_person         ON "user" (person_id) WHERE person_id IS NOT NULL;


-- =============================================================================
-- 3. Aggregate-internal entities
-- =============================================================================

CREATE TABLE member_state (
    id                  UUID          NOT NULL PRIMARY KEY,
    club_id             UUID          NOT NULL,
    name                VARCHAR(50)   NOT NULL,
    remarks             VARCHAR(250),
    created_on          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id UUID,
    deleted_on          TIMESTAMPTZ,
    deleted_by_user_id  UUID,
    CONSTRAINT fk_member_state_club_id FOREIGN KEY (club_id) REFERENCES club (id) ON DELETE CASCADE
);
CREATE INDEX ix_member_state_club ON member_state (club_id);

CREATE TABLE person_category (
    id                          UUID          NOT NULL PRIMARY KEY,
    club_id                     UUID          NOT NULL,
    category_name               VARCHAR(100)  NOT NULL,
    remarks                     VARCHAR(250),
    parent_person_category_id   UUID,
    created_on                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id          UUID,
    modified_on                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id         UUID,
    deleted_on                  TIMESTAMPTZ,
    deleted_by_user_id          UUID,
    CONSTRAINT fk_person_category_club_id   FOREIGN KEY (club_id)                   REFERENCES club (id)             ON DELETE CASCADE,
    CONSTRAINT fk_person_category_parent_id FOREIGN KEY (parent_person_category_id) REFERENCES person_category (id)  ON DELETE RESTRICT
);
CREATE INDEX ix_person_category_club   ON person_category (club_id);
CREATE INDEX ix_person_category_parent ON person_category (parent_person_category_id)
    WHERE parent_person_category_id IS NOT NULL;

CREATE TABLE person_club (
    id                                          UUID          NOT NULL PRIMARY KEY,
    person_id                                   UUID          NOT NULL,
    club_id                                     UUID          NOT NULL,
    member_number                               VARCHAR(20),
    member_state_id                             UUID,
    is_motor_pilot                              BOOLEAN       NOT NULL DEFAULT false,
    is_tow_pilot                                BOOLEAN       NOT NULL DEFAULT false,
    is_glider_instructor                        BOOLEAN       NOT NULL DEFAULT false,
    is_glider_pilot                             BOOLEAN       NOT NULL DEFAULT false,
    is_glider_trainee                           BOOLEAN       NOT NULL DEFAULT false,
    is_passenger                                BOOLEAN       NOT NULL DEFAULT false,
    is_winch_operator                           BOOLEAN       NOT NULL DEFAULT false,
    is_motor_instructor                         BOOLEAN       NOT NULL DEFAULT false,
    receive_flight_reports                      BOOLEAN       NOT NULL DEFAULT false,
    receive_aircraft_reservation_notifications  BOOLEAN       NOT NULL DEFAULT false,
    receive_planning_day_role_reminder          BOOLEAN       NOT NULL DEFAULT false,
    is_active                                   BOOLEAN       NOT NULL DEFAULT false,
    created_on                                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                          UUID,
    modified_on                                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                         UUID,
    deleted_on                                  TIMESTAMPTZ,
    deleted_by_user_id                          UUID,
    CONSTRAINT fk_person_club_person_id       FOREIGN KEY (person_id)       REFERENCES person (id)       ON DELETE CASCADE,
    CONSTRAINT fk_person_club_club_id         FOREIGN KEY (club_id)         REFERENCES club (id)         ON DELETE RESTRICT,
    CONSTRAINT fk_person_club_member_state_id FOREIGN KEY (member_state_id) REFERENCES member_state (id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX ux_person_club_alive
    ON person_club (person_id, club_id)
    WHERE deleted_on IS NULL;
CREATE INDEX ix_person_club_club_person
    ON person_club (club_id, person_id)
    INCLUDE (member_state_id, is_glider_pilot, is_glider_instructor);
CREATE INDEX ix_person_club_member_number
    ON person_club (club_id, member_number)
    WHERE member_number IS NOT NULL;

CREATE TABLE user_role (
    id      UUID NOT NULL PRIMARY KEY,
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    CONSTRAINT fk_user_role_user_id FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role_id FOREIGN KEY (role_id) REFERENCES role    (id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX ux_user_role_pair ON user_role (user_id, role_id);
CREATE        INDEX ix_user_role_role ON user_role (role_id);

CREATE TABLE club_extension (
    id                  UUID          NOT NULL PRIMARY KEY,
    club_id             UUID          NOT NULL,
    extension_type_id   UUID          NOT NULL,
    is_active           BOOLEAN       NOT NULL DEFAULT false,
    created_on          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id UUID,
    deleted_on          TIMESTAMPTZ,
    deleted_by_user_id  UUID,
    CONSTRAINT fk_club_extension_club_id           FOREIGN KEY (club_id)           REFERENCES club (id)            ON DELETE CASCADE,
    CONSTRAINT fk_club_extension_extension_type_id FOREIGN KEY (extension_type_id) REFERENCES extension_type (id)  ON DELETE RESTRICT
);
CREATE UNIQUE INDEX ux_club_extension_pair
    ON club_extension (club_id, extension_type_id)
    WHERE deleted_on IS NULL;

CREATE TABLE email_template (
    id                  UUID          NOT NULL PRIMARY KEY,
    club_id             UUID,
    template_code       VARCHAR(64)   NOT NULL,
    subject             VARCHAR(256)  NOT NULL,
    from_address        VARCHAR(256),
    reply_to_addresses  VARCHAR(256),
    html_body           TEXT,
    text_body           TEXT,
    description         TEXT,
    language_id         UUID,
    is_system_template  BOOLEAN       NOT NULL DEFAULT false,
    is_customizable     BOOLEAN       NOT NULL DEFAULT true,
    created_on          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id UUID,
    deleted_on          TIMESTAMPTZ,
    deleted_by_user_id  UUID,
    CONSTRAINT fk_email_template_club_id     FOREIGN KEY (club_id)     REFERENCES club (id)     ON DELETE CASCADE,
    CONSTRAINT fk_email_template_language_id FOREIGN KEY (language_id) REFERENCES language (id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX ux_email_template_club_code
    ON email_template (club_id, template_code)
    WHERE club_id IS NOT NULL;
CREATE UNIQUE INDEX ux_email_template_default
    ON email_template (template_code)
    WHERE club_id IS NULL;

CREATE TABLE extension_value (
    id                          UUID          NOT NULL PRIMARY KEY,
    extension_type_id           UUID          NOT NULL,
    club_id                     UUID,
    extension_value_name        VARCHAR(100)  NOT NULL,
    extension_value_key_name    VARCHAR(100)  NOT NULL,
    extension_string_value      TEXT,
    extension_binary_value      BYTEA,
    is_default                  BOOLEAN       NOT NULL DEFAULT false,
    comment                     TEXT,
    created_on                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id          UUID,
    modified_on                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id         UUID,
    deleted_on                  TIMESTAMPTZ,
    deleted_by_user_id          UUID,
    CONSTRAINT fk_extension_value_extension_type_id FOREIGN KEY (extension_type_id) REFERENCES extension_type (id) ON DELETE RESTRICT,
    CONSTRAINT fk_extension_value_club_id           FOREIGN KEY (club_id)           REFERENCES club (id)           ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_extension_value_club_key
    ON extension_value (club_id, extension_value_key_name)
    WHERE club_id IS NOT NULL;
CREATE UNIQUE INDEX ux_extension_value_default
    ON extension_value (extension_value_key_name)
    WHERE club_id IS NULL;


-- =============================================================================
-- 4. SQL comments on aggregate-root id columns + the principal-subject
--    user.club_id column. Forensic clarity at boundary review time.
-- =============================================================================

COMMENT ON COLUMN person.id IS
    'UUID v7. Aggregate root (ADR 0018). External form psn_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN club.id IS
    'UUID v7. Aggregate root (ADR 0018). External form clb_<crockford-base32>. See ADR 0019.';
COMMENT ON COLUMN "user".id IS
    'UUID v7. Aggregate root (ADR 0018). External form usr_<crockford-base32>. See ADR 0019.';

COMMENT ON COLUMN "user".club_id IS
    'Principal-subject home club. NOT a @TenantId discriminator — do NOT add @TenantId on the User entity (would chicken-and-egg the user load).';

COMMENT ON COLUMN person.created_by_user_id IS
    'No FK constraint by design (chicken-and-egg at first-user bootstrap). Service layer populates; never bind from request payload.';
COMMENT ON COLUMN club.created_by_user_id IS
    'No FK constraint by design (chicken-and-egg at first-user bootstrap). Service layer populates; never bind from request payload.';
COMMENT ON COLUMN "user".created_by_user_id IS
    'No FK constraint by design (chicken-and-egg at first-user bootstrap). Service layer populates; never bind from request payload.';


-- =============================================================================
-- 5. Reference-data seeds (fixed canonical UUID v7 literals).
--    See next/server/src/test/resources/reference-seeds-canonical-uuids.json
--    for the test-time oracle that pins these UUIDs.
-- =============================================================================

-- start_type (5 canonical launches)
INSERT INTO start_type (id, code, name, applicable_categories) VALUES
    ('019e2e15-2c00-7fa0-8000-000000000fa0', 'WINCH_LAUNCH',   'Winch Launch',   ARRAY['GLIDER']),
    ('019e2e15-2c00-7fa1-8000-000000000fa1', 'AEROTOW',        'Aerotow',        ARRAY['GLIDER','TOW']),
    ('019e2e15-2c00-7fa2-8000-000000000fa2', 'SELF_START',     'Self Start',     ARRAY['GLIDER']),
    ('019e2e15-2c00-7fa3-8000-000000000fa3', 'EXTERNAL_START', 'External Start', ARRAY['GLIDER']),
    ('019e2e15-2c00-7fa4-8000-000000000fa4', 'MOTOR',          'Motor',          ARRAY['MOTOR']);

-- club_state (3 canonical lifecycle states)
INSERT INTO club_state (id, code, name) VALUES
    ('019e2e15-2c00-7bb8-8000-000000000bb8', 'ACTIVE',    'Active'),
    ('019e2e15-2c00-7bb9-8000-000000000bb9', 'SUSPENDED', 'Suspended'),
    ('019e2e15-2c00-7bba-8000-000000000bba', 'CLOSED',    'Closed');

-- language (8 canonical: 4 Swiss national languages + 4 region-tagged variants + English)
INSERT INTO language (id, code, name) VALUES
    ('019e2e15-2c00-77d0-8000-0000000007d0', 'de',    'Deutsch'),
    ('019e2e15-2c00-77d1-8000-0000000007d1', 'fr',    'Français'),
    ('019e2e15-2c00-77d2-8000-0000000007d2', 'it',    'Italiano'),
    ('019e2e15-2c00-77d3-8000-0000000007d3', 'en',    'English'),
    ('019e2e15-2c00-77d4-8000-0000000007d4', 'rm',    'Rumantsch'),
    ('019e2e15-2c00-77d5-8000-0000000007d5', 'de-CH', 'Schweizer Hochdeutsch'),
    ('019e2e15-2c00-77d6-8000-0000000007d6', 'fr-CH', 'Français suisse'),
    ('019e2e15-2c00-77d7-8000-0000000007d7', 'it-CH', 'Italiano svizzero');

-- length_unit_type
INSERT INTO length_unit_type (id, code, name, short_name, comment) VALUES
    ('019e2e15-2c00-7388-8000-000000001388', 'METER', 'Meter', 'm',  'Metric'),
    ('019e2e15-2c00-7389-8000-000000001389', 'FEET',  'Feet',  'ft', 'Imperial');

-- elevation_unit_type
INSERT INTO elevation_unit_type (id, code, name, short_name, comment) VALUES
    ('019e2e15-2c00-7770-8000-000000001770', 'METER', 'Meter', 'm',  'Metric'),
    ('019e2e15-2c00-7771-8000-000000001771', 'FEET',  'Feet',  'ft', 'Imperial');

-- counter_unit_type
INSERT INTO counter_unit_type (id, code, name, short_name, comment) VALUES
    ('019e2e15-2c00-7b58-8000-000000001b58', 'HOURS_DECIMAL', 'Hours (decimal)', 'h',   'Engine / flight time'),
    ('019e2e15-2c00-7b59-8000-000000001b59', 'HOURS_MINUTES', 'Hours (HH:MM)',   'h',   'Engine / flight time'),
    ('019e2e15-2c00-7b5a-8000-000000001b5a', 'LANDINGS',      'Landings',        'ldg', 'Landing counter'),
    ('019e2e15-2c00-7b5b-8000-000000001b5b', 'STARTS',        'Starts',          'st',  'Start counter');

-- extension_type (legacy snapshot)
INSERT INTO extension_type (id, code, name, comment) VALUES
    ('019e2e15-2c00-7f40-8000-000000001f40', 'STRING',  'String value',         NULL),
    ('019e2e15-2c00-7f41-8000-000000001f41', 'INTEGER', 'Integer value',        NULL),
    ('019e2e15-2c00-7f42-8000-000000001f42', 'BOOLEAN', 'Boolean flag',         NULL),
    ('019e2e15-2c00-7f43-8000-000000001f43', 'DATE',    'Date value',           NULL),
    ('019e2e15-2c00-7f44-8000-000000001f44', 'LIST',    'Single-select list',   NULL);

-- role (5 canonical roles — S-026 finalises permission matrix)
INSERT INTO role (id, code, description) VALUES
    ('019e2e15-2c00-7328-8000-000000002328', 'ADMIN',      'Administrator — full club control'),
    ('019e2e15-2c00-7329-8000-000000002329', 'FLIGHT_OPS', 'Flight operations — daily flight + planning ops'),
    ('019e2e15-2c00-732a-8000-00000000232a', 'INSTRUCTOR', 'Flight instructor — training records + sign-offs'),
    ('019e2e15-2c00-732b-8000-00000000232b', 'PILOT',      'Active pilot — read own flights, file own records'),
    ('019e2e15-2c00-732c-8000-00000000232c', 'READER',     'Read-only — admin views, no mutation');

-- country (ISO 3166-1 alpha-2 + alpha-3 + canonical English name; 248 rows.
-- Generated by GenerateCanonicalUuids.java; per-row UUIDs deterministic.)
INSERT INTO country (id, iso2_code, iso3_code, name) VALUES
    ('019e2e15-2c00-73e8-8000-0000000003e8', 'AF', 'AFG', 'Afghanistan'),
    ('019e2e15-2c00-73e9-8000-0000000003e9', 'AL', 'ALB', 'Albania'),
    ('019e2e15-2c00-73ea-8000-0000000003ea', 'DZ', 'DZA', 'Algeria'),
    ('019e2e15-2c00-73eb-8000-0000000003eb', 'AS', 'ASM', 'American Samoa'),
    ('019e2e15-2c00-73ec-8000-0000000003ec', 'AD', 'AND', 'Andorra'),
    ('019e2e15-2c00-73ed-8000-0000000003ed', 'AO', 'AGO', 'Angola'),
    ('019e2e15-2c00-73ee-8000-0000000003ee', 'AI', 'AIA', 'Anguilla'),
    ('019e2e15-2c00-73ef-8000-0000000003ef', 'AQ', 'ATA', 'Antarctica'),
    ('019e2e15-2c00-73f0-8000-0000000003f0', 'AG', 'ATG', 'Antigua and Barbuda'),
    ('019e2e15-2c00-73f1-8000-0000000003f1', 'AR', 'ARG', 'Argentina'),
    ('019e2e15-2c00-73f2-8000-0000000003f2', 'AM', 'ARM', 'Armenia'),
    ('019e2e15-2c00-73f3-8000-0000000003f3', 'AW', 'ABW', 'Aruba'),
    ('019e2e15-2c00-73f4-8000-0000000003f4', 'AU', 'AUS', 'Australia'),
    ('019e2e15-2c00-73f5-8000-0000000003f5', 'AT', 'AUT', 'Austria'),
    ('019e2e15-2c00-73f6-8000-0000000003f6', 'AZ', 'AZE', 'Azerbaijan'),
    ('019e2e15-2c00-73f7-8000-0000000003f7', 'BS', 'BHS', 'Bahamas'),
    ('019e2e15-2c00-73f8-8000-0000000003f8', 'BH', 'BHR', 'Bahrain'),
    ('019e2e15-2c00-73f9-8000-0000000003f9', 'BD', 'BGD', 'Bangladesh'),
    ('019e2e15-2c00-73fa-8000-0000000003fa', 'BB', 'BRB', 'Barbados'),
    ('019e2e15-2c00-73fb-8000-0000000003fb', 'BY', 'BLR', 'Belarus'),
    ('019e2e15-2c00-73fc-8000-0000000003fc', 'BE', 'BEL', 'Belgium'),
    ('019e2e15-2c00-73fd-8000-0000000003fd', 'BZ', 'BLZ', 'Belize'),
    ('019e2e15-2c00-73fe-8000-0000000003fe', 'BJ', 'BEN', 'Benin'),
    ('019e2e15-2c00-73ff-8000-0000000003ff', 'BM', 'BMU', 'Bermuda'),
    ('019e2e15-2c00-7400-8000-000000000400', 'BT', 'BTN', 'Bhutan'),
    ('019e2e15-2c00-7401-8000-000000000401', 'BO', 'BOL', 'Bolivia'),
    ('019e2e15-2c00-7402-8000-000000000402', 'BQ', 'BES', 'Bonaire, Sint Eustatius and Saba'),
    ('019e2e15-2c00-7403-8000-000000000403', 'BA', 'BIH', 'Bosnia and Herzegovina'),
    ('019e2e15-2c00-7404-8000-000000000404', 'BW', 'BWA', 'Botswana'),
    ('019e2e15-2c00-7405-8000-000000000405', 'BV', 'BVT', 'Bouvet Island'),
    ('019e2e15-2c00-7406-8000-000000000406', 'BR', 'BRA', 'Brazil'),
    ('019e2e15-2c00-7407-8000-000000000407', 'IO', 'IOT', 'British Indian Ocean Territory'),
    ('019e2e15-2c00-7408-8000-000000000408', 'BN', 'BRN', 'Brunei Darussalam'),
    ('019e2e15-2c00-7409-8000-000000000409', 'BG', 'BGR', 'Bulgaria'),
    ('019e2e15-2c00-740a-8000-00000000040a', 'BF', 'BFA', 'Burkina Faso'),
    ('019e2e15-2c00-740b-8000-00000000040b', 'BI', 'BDI', 'Burundi'),
    ('019e2e15-2c00-740c-8000-00000000040c', 'CV', 'CPV', 'Cabo Verde'),
    ('019e2e15-2c00-740d-8000-00000000040d', 'KH', 'KHM', 'Cambodia'),
    ('019e2e15-2c00-740e-8000-00000000040e', 'CM', 'CMR', 'Cameroon'),
    ('019e2e15-2c00-740f-8000-00000000040f', 'CA', 'CAN', 'Canada'),
    ('019e2e15-2c00-7410-8000-000000000410', 'KY', 'CYM', 'Cayman Islands'),
    ('019e2e15-2c00-7411-8000-000000000411', 'CF', 'CAF', 'Central African Republic'),
    ('019e2e15-2c00-7412-8000-000000000412', 'TD', 'TCD', 'Chad'),
    ('019e2e15-2c00-7413-8000-000000000413', 'CL', 'CHL', 'Chile'),
    ('019e2e15-2c00-7414-8000-000000000414', 'CN', 'CHN', 'China'),
    ('019e2e15-2c00-7415-8000-000000000415', 'CX', 'CXR', 'Christmas Island'),
    ('019e2e15-2c00-7416-8000-000000000416', 'CC', 'CCK', 'Cocos (Keeling) Islands'),
    ('019e2e15-2c00-7417-8000-000000000417', 'CO', 'COL', 'Colombia'),
    ('019e2e15-2c00-7418-8000-000000000418', 'KM', 'COM', 'Comoros'),
    ('019e2e15-2c00-7419-8000-000000000419', 'CD', 'COD', 'Congo, Democratic Republic of the'),
    ('019e2e15-2c00-741a-8000-00000000041a', 'CG', 'COG', 'Congo'),
    ('019e2e15-2c00-741b-8000-00000000041b', 'CK', 'COK', 'Cook Islands'),
    ('019e2e15-2c00-741c-8000-00000000041c', 'CR', 'CRI', 'Costa Rica'),
    ('019e2e15-2c00-741d-8000-00000000041d', 'CI', 'CIV', 'Côte d''Ivoire'),
    ('019e2e15-2c00-741e-8000-00000000041e', 'HR', 'HRV', 'Croatia'),
    ('019e2e15-2c00-741f-8000-00000000041f', 'CU', 'CUB', 'Cuba'),
    ('019e2e15-2c00-7420-8000-000000000420', 'CW', 'CUW', 'Curaçao'),
    ('019e2e15-2c00-7421-8000-000000000421', 'CY', 'CYP', 'Cyprus'),
    ('019e2e15-2c00-7422-8000-000000000422', 'CZ', 'CZE', 'Czechia'),
    ('019e2e15-2c00-7423-8000-000000000423', 'DK', 'DNK', 'Denmark'),
    ('019e2e15-2c00-7424-8000-000000000424', 'DJ', 'DJI', 'Djibouti'),
    ('019e2e15-2c00-7425-8000-000000000425', 'DM', 'DMA', 'Dominica'),
    ('019e2e15-2c00-7426-8000-000000000426', 'DO', 'DOM', 'Dominican Republic'),
    ('019e2e15-2c00-7427-8000-000000000427', 'EC', 'ECU', 'Ecuador'),
    ('019e2e15-2c00-7428-8000-000000000428', 'EG', 'EGY', 'Egypt'),
    ('019e2e15-2c00-7429-8000-000000000429', 'SV', 'SLV', 'El Salvador'),
    ('019e2e15-2c00-742a-8000-00000000042a', 'GQ', 'GNQ', 'Equatorial Guinea'),
    ('019e2e15-2c00-742b-8000-00000000042b', 'ER', 'ERI', 'Eritrea'),
    ('019e2e15-2c00-742c-8000-00000000042c', 'EE', 'EST', 'Estonia'),
    ('019e2e15-2c00-742d-8000-00000000042d', 'SZ', 'SWZ', 'Eswatini'),
    ('019e2e15-2c00-742e-8000-00000000042e', 'ET', 'ETH', 'Ethiopia'),
    ('019e2e15-2c00-742f-8000-00000000042f', 'FK', 'FLK', 'Falkland Islands'),
    ('019e2e15-2c00-7430-8000-000000000430', 'FO', 'FRO', 'Faroe Islands'),
    ('019e2e15-2c00-7431-8000-000000000431', 'FJ', 'FJI', 'Fiji'),
    ('019e2e15-2c00-7432-8000-000000000432', 'FI', 'FIN', 'Finland'),
    ('019e2e15-2c00-7433-8000-000000000433', 'FR', 'FRA', 'France'),
    ('019e2e15-2c00-7434-8000-000000000434', 'GF', 'GUF', 'French Guiana'),
    ('019e2e15-2c00-7435-8000-000000000435', 'PF', 'PYF', 'French Polynesia'),
    ('019e2e15-2c00-7436-8000-000000000436', 'TF', 'ATF', 'French Southern Territories'),
    ('019e2e15-2c00-7437-8000-000000000437', 'GA', 'GAB', 'Gabon'),
    ('019e2e15-2c00-7438-8000-000000000438', 'GM', 'GMB', 'Gambia'),
    ('019e2e15-2c00-7439-8000-000000000439', 'GE', 'GEO', 'Georgia'),
    ('019e2e15-2c00-743a-8000-00000000043a', 'DE', 'DEU', 'Germany'),
    ('019e2e15-2c00-743b-8000-00000000043b', 'GH', 'GHA', 'Ghana'),
    ('019e2e15-2c00-743c-8000-00000000043c', 'GI', 'GIB', 'Gibraltar'),
    ('019e2e15-2c00-743d-8000-00000000043d', 'GR', 'GRC', 'Greece'),
    ('019e2e15-2c00-743e-8000-00000000043e', 'GL', 'GRL', 'Greenland'),
    ('019e2e15-2c00-743f-8000-00000000043f', 'GD', 'GRD', 'Grenada'),
    ('019e2e15-2c00-7440-8000-000000000440', 'GP', 'GLP', 'Guadeloupe'),
    ('019e2e15-2c00-7441-8000-000000000441', 'GU', 'GUM', 'Guam'),
    ('019e2e15-2c00-7442-8000-000000000442', 'GT', 'GTM', 'Guatemala'),
    ('019e2e15-2c00-7443-8000-000000000443', 'GG', 'GGY', 'Guernsey'),
    ('019e2e15-2c00-7444-8000-000000000444', 'GN', 'GIN', 'Guinea'),
    ('019e2e15-2c00-7445-8000-000000000445', 'GW', 'GNB', 'Guinea-Bissau'),
    ('019e2e15-2c00-7446-8000-000000000446', 'GY', 'GUY', 'Guyana'),
    ('019e2e15-2c00-7447-8000-000000000447', 'HT', 'HTI', 'Haiti'),
    ('019e2e15-2c00-7448-8000-000000000448', 'HM', 'HMD', 'Heard Island and McDonald Islands'),
    ('019e2e15-2c00-7449-8000-000000000449', 'VA', 'VAT', 'Holy See'),
    ('019e2e15-2c00-744a-8000-00000000044a', 'HN', 'HND', 'Honduras'),
    ('019e2e15-2c00-744b-8000-00000000044b', 'HK', 'HKG', 'Hong Kong'),
    ('019e2e15-2c00-744c-8000-00000000044c', 'HU', 'HUN', 'Hungary'),
    ('019e2e15-2c00-744d-8000-00000000044d', 'IS', 'ISL', 'Iceland'),
    ('019e2e15-2c00-744e-8000-00000000044e', 'IN', 'IND', 'India'),
    ('019e2e15-2c00-744f-8000-00000000044f', 'ID', 'IDN', 'Indonesia'),
    ('019e2e15-2c00-7450-8000-000000000450', 'IR', 'IRN', 'Iran'),
    ('019e2e15-2c00-7451-8000-000000000451', 'IQ', 'IRQ', 'Iraq'),
    ('019e2e15-2c00-7452-8000-000000000452', 'IE', 'IRL', 'Ireland'),
    ('019e2e15-2c00-7453-8000-000000000453', 'IM', 'IMN', 'Isle of Man'),
    ('019e2e15-2c00-7454-8000-000000000454', 'IL', 'ISR', 'Israel'),
    ('019e2e15-2c00-7455-8000-000000000455', 'IT', 'ITA', 'Italy'),
    ('019e2e15-2c00-7456-8000-000000000456', 'JM', 'JAM', 'Jamaica'),
    ('019e2e15-2c00-7457-8000-000000000457', 'JP', 'JPN', 'Japan'),
    ('019e2e15-2c00-7458-8000-000000000458', 'JE', 'JEY', 'Jersey'),
    ('019e2e15-2c00-7459-8000-000000000459', 'JO', 'JOR', 'Jordan'),
    ('019e2e15-2c00-745a-8000-00000000045a', 'KZ', 'KAZ', 'Kazakhstan'),
    ('019e2e15-2c00-745b-8000-00000000045b', 'KE', 'KEN', 'Kenya'),
    ('019e2e15-2c00-745c-8000-00000000045c', 'KI', 'KIR', 'Kiribati'),
    ('019e2e15-2c00-745d-8000-00000000045d', 'KP', 'PRK', 'Korea, Democratic People''s Republic of'),
    ('019e2e15-2c00-745e-8000-00000000045e', 'KR', 'KOR', 'Korea, Republic of'),
    ('019e2e15-2c00-745f-8000-00000000045f', 'KW', 'KWT', 'Kuwait'),
    ('019e2e15-2c00-7460-8000-000000000460', 'KG', 'KGZ', 'Kyrgyzstan'),
    ('019e2e15-2c00-7461-8000-000000000461', 'LA', 'LAO', 'Lao People''s Democratic Republic'),
    ('019e2e15-2c00-7462-8000-000000000462', 'LV', 'LVA', 'Latvia'),
    ('019e2e15-2c00-7463-8000-000000000463', 'LB', 'LBN', 'Lebanon'),
    ('019e2e15-2c00-7464-8000-000000000464', 'LS', 'LSO', 'Lesotho'),
    ('019e2e15-2c00-7465-8000-000000000465', 'LR', 'LBR', 'Liberia'),
    ('019e2e15-2c00-7466-8000-000000000466', 'LY', 'LBY', 'Libya'),
    ('019e2e15-2c00-7467-8000-000000000467', 'LI', 'LIE', 'Liechtenstein'),
    ('019e2e15-2c00-7468-8000-000000000468', 'LT', 'LTU', 'Lithuania'),
    ('019e2e15-2c00-7469-8000-000000000469', 'LU', 'LUX', 'Luxembourg'),
    ('019e2e15-2c00-746a-8000-00000000046a', 'MO', 'MAC', 'Macao'),
    ('019e2e15-2c00-746b-8000-00000000046b', 'MG', 'MDG', 'Madagascar'),
    ('019e2e15-2c00-746c-8000-00000000046c', 'MW', 'MWI', 'Malawi'),
    ('019e2e15-2c00-746d-8000-00000000046d', 'MY', 'MYS', 'Malaysia'),
    ('019e2e15-2c00-746e-8000-00000000046e', 'MV', 'MDV', 'Maldives'),
    ('019e2e15-2c00-746f-8000-00000000046f', 'ML', 'MLI', 'Mali'),
    ('019e2e15-2c00-7470-8000-000000000470', 'MT', 'MLT', 'Malta'),
    ('019e2e15-2c00-7471-8000-000000000471', 'MH', 'MHL', 'Marshall Islands'),
    ('019e2e15-2c00-7472-8000-000000000472', 'MQ', 'MTQ', 'Martinique'),
    ('019e2e15-2c00-7473-8000-000000000473', 'MR', 'MRT', 'Mauritania'),
    ('019e2e15-2c00-7474-8000-000000000474', 'MU', 'MUS', 'Mauritius'),
    ('019e2e15-2c00-7475-8000-000000000475', 'YT', 'MYT', 'Mayotte'),
    ('019e2e15-2c00-7476-8000-000000000476', 'MX', 'MEX', 'Mexico'),
    ('019e2e15-2c00-7477-8000-000000000477', 'FM', 'FSM', 'Micronesia'),
    ('019e2e15-2c00-7478-8000-000000000478', 'MD', 'MDA', 'Moldova'),
    ('019e2e15-2c00-7479-8000-000000000479', 'MC', 'MCO', 'Monaco'),
    ('019e2e15-2c00-747a-8000-00000000047a', 'MN', 'MNG', 'Mongolia'),
    ('019e2e15-2c00-747b-8000-00000000047b', 'ME', 'MNE', 'Montenegro'),
    ('019e2e15-2c00-747c-8000-00000000047c', 'MS', 'MSR', 'Montserrat'),
    ('019e2e15-2c00-747d-8000-00000000047d', 'MA', 'MAR', 'Morocco'),
    ('019e2e15-2c00-747e-8000-00000000047e', 'MZ', 'MOZ', 'Mozambique'),
    ('019e2e15-2c00-747f-8000-00000000047f', 'MM', 'MMR', 'Myanmar'),
    ('019e2e15-2c00-7480-8000-000000000480', 'NA', 'NAM', 'Namibia'),
    ('019e2e15-2c00-7481-8000-000000000481', 'NR', 'NRU', 'Nauru'),
    ('019e2e15-2c00-7482-8000-000000000482', 'NP', 'NPL', 'Nepal'),
    ('019e2e15-2c00-7483-8000-000000000483', 'NL', 'NLD', 'Netherlands'),
    ('019e2e15-2c00-7484-8000-000000000484', 'NC', 'NCL', 'New Caledonia'),
    ('019e2e15-2c00-7485-8000-000000000485', 'NZ', 'NZL', 'New Zealand'),
    ('019e2e15-2c00-7486-8000-000000000486', 'NI', 'NIC', 'Nicaragua'),
    ('019e2e15-2c00-7487-8000-000000000487', 'NE', 'NER', 'Niger'),
    ('019e2e15-2c00-7488-8000-000000000488', 'NG', 'NGA', 'Nigeria'),
    ('019e2e15-2c00-7489-8000-000000000489', 'NU', 'NIU', 'Niue'),
    ('019e2e15-2c00-748a-8000-00000000048a', 'NF', 'NFK', 'Norfolk Island'),
    ('019e2e15-2c00-748b-8000-00000000048b', 'MK', 'MKD', 'North Macedonia'),
    ('019e2e15-2c00-748c-8000-00000000048c', 'MP', 'MNP', 'Northern Mariana Islands'),
    ('019e2e15-2c00-748d-8000-00000000048d', 'NO', 'NOR', 'Norway'),
    ('019e2e15-2c00-748e-8000-00000000048e', 'OM', 'OMN', 'Oman'),
    ('019e2e15-2c00-748f-8000-00000000048f', 'PK', 'PAK', 'Pakistan'),
    ('019e2e15-2c00-7490-8000-000000000490', 'PW', 'PLW', 'Palau'),
    ('019e2e15-2c00-7491-8000-000000000491', 'PS', 'PSE', 'Palestine, State of'),
    ('019e2e15-2c00-7492-8000-000000000492', 'PA', 'PAN', 'Panama'),
    ('019e2e15-2c00-7493-8000-000000000493', 'PG', 'PNG', 'Papua New Guinea'),
    ('019e2e15-2c00-7494-8000-000000000494', 'PY', 'PRY', 'Paraguay'),
    ('019e2e15-2c00-7495-8000-000000000495', 'PE', 'PER', 'Peru'),
    ('019e2e15-2c00-7496-8000-000000000496', 'PH', 'PHL', 'Philippines'),
    ('019e2e15-2c00-7497-8000-000000000497', 'PN', 'PCN', 'Pitcairn'),
    ('019e2e15-2c00-7498-8000-000000000498', 'PL', 'POL', 'Poland'),
    ('019e2e15-2c00-7499-8000-000000000499', 'PT', 'PRT', 'Portugal'),
    ('019e2e15-2c00-749a-8000-00000000049a', 'PR', 'PRI', 'Puerto Rico'),
    ('019e2e15-2c00-749b-8000-00000000049b', 'QA', 'QAT', 'Qatar'),
    ('019e2e15-2c00-749c-8000-00000000049c', 'RE', 'REU', 'Réunion'),
    ('019e2e15-2c00-749d-8000-00000000049d', 'RO', 'ROU', 'Romania'),
    ('019e2e15-2c00-749e-8000-00000000049e', 'RU', 'RUS', 'Russian Federation'),
    ('019e2e15-2c00-749f-8000-00000000049f', 'RW', 'RWA', 'Rwanda'),
    ('019e2e15-2c00-74a0-8000-0000000004a0', 'BL', 'BLM', 'Saint Barthélemy'),
    ('019e2e15-2c00-74a1-8000-0000000004a1', 'SH', 'SHN', 'Saint Helena, Ascension and Tristan da Cunha'),
    ('019e2e15-2c00-74a2-8000-0000000004a2', 'KN', 'KNA', 'Saint Kitts and Nevis'),
    ('019e2e15-2c00-74a3-8000-0000000004a3', 'LC', 'LCA', 'Saint Lucia'),
    ('019e2e15-2c00-74a4-8000-0000000004a4', 'MF', 'MAF', 'Saint Martin (French part)'),
    ('019e2e15-2c00-74a5-8000-0000000004a5', 'PM', 'SPM', 'Saint Pierre and Miquelon'),
    ('019e2e15-2c00-74a6-8000-0000000004a6', 'VC', 'VCT', 'Saint Vincent and the Grenadines'),
    ('019e2e15-2c00-74a7-8000-0000000004a7', 'WS', 'WSM', 'Samoa'),
    ('019e2e15-2c00-74a8-8000-0000000004a8', 'SM', 'SMR', 'San Marino'),
    ('019e2e15-2c00-74a9-8000-0000000004a9', 'ST', 'STP', 'Sao Tome and Principe'),
    ('019e2e15-2c00-74aa-8000-0000000004aa', 'SA', 'SAU', 'Saudi Arabia'),
    ('019e2e15-2c00-74ab-8000-0000000004ab', 'SN', 'SEN', 'Senegal'),
    ('019e2e15-2c00-74ac-8000-0000000004ac', 'RS', 'SRB', 'Serbia'),
    ('019e2e15-2c00-74ad-8000-0000000004ad', 'SC', 'SYC', 'Seychelles'),
    ('019e2e15-2c00-74ae-8000-0000000004ae', 'SL', 'SLE', 'Sierra Leone'),
    ('019e2e15-2c00-74af-8000-0000000004af', 'SG', 'SGP', 'Singapore'),
    ('019e2e15-2c00-74b0-8000-0000000004b0', 'SX', 'SXM', 'Sint Maarten (Dutch part)'),
    ('019e2e15-2c00-74b1-8000-0000000004b1', 'SK', 'SVK', 'Slovakia'),
    ('019e2e15-2c00-74b2-8000-0000000004b2', 'SI', 'SVN', 'Slovenia'),
    ('019e2e15-2c00-74b3-8000-0000000004b3', 'SB', 'SLB', 'Solomon Islands'),
    ('019e2e15-2c00-74b4-8000-0000000004b4', 'SO', 'SOM', 'Somalia'),
    ('019e2e15-2c00-74b5-8000-0000000004b5', 'ZA', 'ZAF', 'South Africa'),
    ('019e2e15-2c00-74b6-8000-0000000004b6', 'GS', 'SGS', 'South Georgia and the South Sandwich Islands'),
    ('019e2e15-2c00-74b7-8000-0000000004b7', 'SS', 'SSD', 'South Sudan'),
    ('019e2e15-2c00-74b8-8000-0000000004b8', 'ES', 'ESP', 'Spain'),
    ('019e2e15-2c00-74b9-8000-0000000004b9', 'LK', 'LKA', 'Sri Lanka'),
    ('019e2e15-2c00-74ba-8000-0000000004ba', 'SD', 'SDN', 'Sudan'),
    ('019e2e15-2c00-74bb-8000-0000000004bb', 'SR', 'SUR', 'Suriname'),
    ('019e2e15-2c00-74bc-8000-0000000004bc', 'SJ', 'SJM', 'Svalbard and Jan Mayen'),
    ('019e2e15-2c00-74bd-8000-0000000004bd', 'SE', 'SWE', 'Sweden'),
    ('019e2e15-2c00-74be-8000-0000000004be', 'CH', 'CHE', 'Switzerland'),
    ('019e2e15-2c00-74bf-8000-0000000004bf', 'SY', 'SYR', 'Syrian Arab Republic'),
    ('019e2e15-2c00-74c0-8000-0000000004c0', 'TW', 'TWN', 'Taiwan'),
    ('019e2e15-2c00-74c1-8000-0000000004c1', 'TJ', 'TJK', 'Tajikistan'),
    ('019e2e15-2c00-74c2-8000-0000000004c2', 'TZ', 'TZA', 'Tanzania'),
    ('019e2e15-2c00-74c3-8000-0000000004c3', 'TH', 'THA', 'Thailand'),
    ('019e2e15-2c00-74c4-8000-0000000004c4', 'TL', 'TLS', 'Timor-Leste'),
    ('019e2e15-2c00-74c5-8000-0000000004c5', 'TG', 'TGO', 'Togo'),
    ('019e2e15-2c00-74c6-8000-0000000004c6', 'TK', 'TKL', 'Tokelau'),
    ('019e2e15-2c00-74c7-8000-0000000004c7', 'TO', 'TON', 'Tonga'),
    ('019e2e15-2c00-74c8-8000-0000000004c8', 'TT', 'TTO', 'Trinidad and Tobago'),
    ('019e2e15-2c00-74c9-8000-0000000004c9', 'TN', 'TUN', 'Tunisia'),
    ('019e2e15-2c00-74ca-8000-0000000004ca', 'TR', 'TUR', 'Turkey'),
    ('019e2e15-2c00-74cb-8000-0000000004cb', 'TM', 'TKM', 'Turkmenistan'),
    ('019e2e15-2c00-74cc-8000-0000000004cc', 'TC', 'TCA', 'Turks and Caicos Islands'),
    ('019e2e15-2c00-74cd-8000-0000000004cd', 'TV', 'TUV', 'Tuvalu'),
    ('019e2e15-2c00-74ce-8000-0000000004ce', 'UG', 'UGA', 'Uganda'),
    ('019e2e15-2c00-74cf-8000-0000000004cf', 'UA', 'UKR', 'Ukraine'),
    ('019e2e15-2c00-74d0-8000-0000000004d0', 'AE', 'ARE', 'United Arab Emirates'),
    ('019e2e15-2c00-74d1-8000-0000000004d1', 'GB', 'GBR', 'United Kingdom'),
    ('019e2e15-2c00-74d2-8000-0000000004d2', 'US', 'USA', 'United States'),
    ('019e2e15-2c00-74d3-8000-0000000004d3', 'UM', 'UMI', 'United States Minor Outlying Islands'),
    ('019e2e15-2c00-74d4-8000-0000000004d4', 'UY', 'URY', 'Uruguay'),
    ('019e2e15-2c00-74d5-8000-0000000004d5', 'UZ', 'UZB', 'Uzbekistan'),
    ('019e2e15-2c00-74d6-8000-0000000004d6', 'VU', 'VUT', 'Vanuatu'),
    ('019e2e15-2c00-74d7-8000-0000000004d7', 'VE', 'VEN', 'Venezuela'),
    ('019e2e15-2c00-74d8-8000-0000000004d8', 'VN', 'VNM', 'Viet Nam'),
    ('019e2e15-2c00-74d9-8000-0000000004d9', 'VG', 'VGB', 'Virgin Islands (British)'),
    ('019e2e15-2c00-74da-8000-0000000004da', 'VI', 'VIR', 'Virgin Islands (U.S.)'),
    ('019e2e15-2c00-74db-8000-0000000004db', 'WF', 'WLF', 'Wallis and Futuna'),
    ('019e2e15-2c00-74dc-8000-0000000004dc', 'EH', 'ESH', 'Western Sahara'),
    ('019e2e15-2c00-74dd-8000-0000000004dd', 'YE', 'YEM', 'Yemen'),
    ('019e2e15-2c00-74de-8000-0000000004de', 'ZM', 'ZMB', 'Zambia'),
    ('019e2e15-2c00-74df-8000-0000000004df', 'ZW', 'ZWE', 'Zimbabwe');

-- =============================================================================
-- 6. Update V1 sentinel so app_meta reflects the new generation.
-- =============================================================================

UPDATE app_meta SET meta_value = 'S-012' WHERE meta_key = 'schema_baseline_version';
