
CREATE TABLE t_demo_seat (
    id                UUID         NOT NULL PRIMARY KEY,
    seat_number       INTEGER      NOT NULL,
    club_id           UUID         NOT NULL,
    keycloak_username VARCHAR(64)  NOT NULL,
    lease_state       VARCHAR(16)  NOT NULL,
    lease_holder_key  TEXT,
    lease_expires_at  TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_on        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modified_on       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_demo_seat_club_id
        FOREIGN KEY (club_id) REFERENCES t_club (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_demo_seat_seat_number       ON t_demo_seat (seat_number);
CREATE UNIQUE INDEX ux_demo_seat_club_id           ON t_demo_seat (club_id);
CREATE UNIQUE INDEX ux_demo_seat_keycloak_username ON t_demo_seat (keycloak_username);

COMMENT ON TABLE t_demo_seat IS
    'The demo seat pool (J-20). Platform / cross-tenant — no @TenantId, because'
    ' this row maps a seat to its club and a caller reads it before a tenant'
    ' exists. The lease state machine, the expiry rule and the per-address cap'
    ' are Java per ADR 0022 D2 — no CHECK here.';

COMMENT ON COLUMN t_demo_seat.lease_state IS
    'Java enum DemoSeat.LeaseState: FREE / LEASED. A LEASED row that passes'
    ' lease_expires_at stays LEASED. SandboxResetJob deletes and re-seeds the'
    ' club, and only then returns the seat to the pool.';

COMMENT ON COLUMN t_demo_seat.lease_holder_key IS
    'The visitor address that holds the lease. There is deliberately no UNIQUE'
    ' index on this column: the cap is the property'
    ' demo.max-live-seats-per-address, and the end-to-end profile raises it'
    ' above 1.';

COMMENT ON COLUMN t_demo_seat.version IS
    'Optimistic lock. A claim on a free seat is one conditional UPDATE on this'
    ' cell, so two concurrent visitors cannot take the same seat.';


INSERT INTO t_club (
    id,
    clubname,
    club_key,
    country_id,
    club_state_id,
    deployment_id,
    city,
    public_registration_enabled
)
SELECT
    ('019e30c3-2c00-7001-8000-0000000de' || lpad(seat.number::text, 3, '0'))::uuid,
    'Segelfluggruppe Demo',
    'DEMO' || lpad(seat.number::text, 2, '0'),
    '019e2e15-2c00-74be-8000-0000000004be',
    '019e2e15-2c00-7bb8-8000-000000000bb8',
    '00000000-0000-0000-0000-000000000001',
    'Zürich',
    false
FROM generate_series(1, 10) AS seat(number)
ON CONFLICT (id) DO NOTHING;


INSERT INTO t_demo_seat (
    id,
    seat_number,
    club_id,
    keycloak_username,
    lease_state
)
SELECT
    ('019e30c3-2c00-7d00-8000-0000000de' || lpad(seat.number::text, 3, '0'))::uuid,
    seat.number,
    ('019e30c3-2c00-7001-8000-0000000de' || lpad(seat.number::text, 3, '0'))::uuid,
    'demo' || seat.number,
    'FREE'
FROM generate_series(1, 10) AS seat(number)
ON CONFLICT (id) DO NOTHING;
