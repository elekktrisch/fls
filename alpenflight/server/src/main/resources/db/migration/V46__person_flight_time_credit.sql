
CREATE TABLE t_person_flight_time_credit (
    id                                          UUID          NOT NULL PRIMARY KEY,
    person_id                                   UUID          NOT NULL,
    no_flight_time_limit                        BOOLEAN       NOT NULL DEFAULT false,
    valid_until                                 TIMESTAMPTZ   NOT NULL,
    use_rule_for_all_aircrafts_except_listed    BOOLEAN       NOT NULL DEFAULT false,
    matched_aircraft_immatriculations           TEXT,
    discount_in_percent                         INTEGER       NOT NULL DEFAULT 0,
    created_on                                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                          UUID,
    modified_on                                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                         UUID,
    deleted_on                                  TIMESTAMPTZ,
    deleted_by_user_id                          UUID,
    CONSTRAINT fk_person_flight_time_credit_person_id
        FOREIGN KEY (person_id) REFERENCES t_person (id) ON DELETE CASCADE
);
CREATE INDEX ix_pftc_person
    ON t_person_flight_time_credit (person_id)
    WHERE deleted_on IS NULL;

CREATE TABLE t_person_flight_time_credit_transaction (
    id                                          UUID          NOT NULL PRIMARY KEY,
    credit_id                                   UUID          NOT NULL,
    balanced_delivery_id                        UUID,
    balance_date_time                           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    no_flight_time_limit                        BOOLEAN       NOT NULL DEFAULT false,
    current_flight_time_balance_in_seconds      BIGINT,
    flight_time_balance_in_seconds              BIGINT        NOT NULL DEFAULT 0,
    old_flight_time_balance_in_seconds          BIGINT,
    is_current                                  BOOLEAN       NOT NULL DEFAULT false,
    created_on                                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id                          UUID,
    modified_on                                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id                         UUID,
    deleted_on                                  TIMESTAMPTZ,
    deleted_by_user_id                          UUID,
    CONSTRAINT fk_pftc_transaction_credit_id
        FOREIGN KEY (credit_id) REFERENCES t_person_flight_time_credit (id) ON DELETE CASCADE,
    CONSTRAINT fk_pftc_transaction_balanced_delivery_id
        FOREIGN KEY (balanced_delivery_id) REFERENCES t_delivery (id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX ux_pftc_transaction_current
    ON t_person_flight_time_credit_transaction (credit_id)
    WHERE is_current;
CREATE INDEX ix_pftc_transaction_credit
    ON t_person_flight_time_credit_transaction (credit_id)
    WHERE deleted_on IS NULL;

COMMENT ON COLUMN t_person_flight_time_credit.id IS
    'UUID v7. Aggregate root (ADR 0018). Cross-tenant — visible to every club the owning Person belongs to (no club_id).';
COMMENT ON COLUMN t_person_flight_time_credit.matched_aircraft_immatriculations IS
    'Denormalized CSV, substring-matched downstream (legacy .Contains). No FK rewrite — parity exclusion.';
COMMENT ON COLUMN t_person_flight_time_credit_transaction.balanced_delivery_id IS
    'Nullable FK -> Delivery; null when the linked Delivery is not migrated (no hard FK dependency on J-10b).';
