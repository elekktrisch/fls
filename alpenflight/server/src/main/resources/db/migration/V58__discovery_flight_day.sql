
CREATE TABLE t_discovery_flight_day (
    id                  UUID          NOT NULL PRIMARY KEY,
    club_id             UUID          NOT NULL,
    event_date          DATE          NOT NULL,
    created_on          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id UUID,
    deleted_on          TIMESTAMPTZ,
    deleted_by_user_id  UUID,
    CONSTRAINT fk_discovery_flight_day_club_id
        FOREIGN KEY (club_id) REFERENCES t_club (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_discovery_flight_day_club_date
    ON t_discovery_flight_day (club_id, event_date)
    WHERE deleted_on IS NULL;

COMMENT ON COLUMN t_discovery_flight_day.id IS
    'UUID v7. Aggregate root (ADR 0018). See ADR 0019.';
COMMENT ON TABLE t_discovery_flight_day IS
    'Club-scoped discovery-flight event days, read anonymously through a Tenants.runAs window. Deliberately not migrated from legacy TrialFlight.EventDates — forward-dated configuration a club admin re-enters.';
