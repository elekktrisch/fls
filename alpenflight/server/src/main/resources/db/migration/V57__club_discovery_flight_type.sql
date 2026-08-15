
ALTER TABLE t_club
    ADD COLUMN discovery_flight_type_id UUID;

ALTER TABLE t_club
    ADD CONSTRAINT fk_club_discovery_flight_type_id
        FOREIGN KEY (discovery_flight_type_id) REFERENCES t_flight_type (id) ON DELETE SET NULL;
