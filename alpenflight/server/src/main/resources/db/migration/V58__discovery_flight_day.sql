-- Bookable days a club offers discovery flights on. Replaces the legacy
-- per-club settings row that held a JSON array of ISO datetimes; AlpenFlight
-- has no settings table by design, so the days become club-scoped rows.
--
-- Structural only (ADR 0022 directive 2): PK, the club FK, the tenant
-- discriminator, the identity-bearing partial UNIQUE. "A past day is not
-- bookable" and "a day may not be scheduled into the past" are aggregate
-- methods, not CHECKs.
--
-- DATE, not TIMESTAMPTZ: the legacy value carried a time-of-day that nothing
-- ever read (the booked reservation is all-day), so storing an instant would
-- invent a timezone question the domain does not have.

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

-- Identity-bearing: one live entry per (club, date) — a second row for the same
-- day would render as a duplicate option in the public day picker. Partial, so a
-- club can withdraw a day and re-publish it. Also serves the public read's
-- (club_id, event_date >= today) scan.
CREATE UNIQUE INDEX ux_discovery_flight_day_club_date
    ON t_discovery_flight_day (club_id, event_date)
    WHERE deleted_on IS NULL;

COMMENT ON COLUMN t_discovery_flight_day.id IS
    'UUID v7. Aggregate root (ADR 0018). See ADR 0019.';
COMMENT ON TABLE t_discovery_flight_day IS
    'Club-scoped discovery-flight event days, read anonymously through a Tenants.runAs window. Deliberately not migrated from legacy TrialFlight.EventDates — forward-dated configuration a club admin re-enters.';
