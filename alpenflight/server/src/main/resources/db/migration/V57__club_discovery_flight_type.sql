-- The flight type a discovery-flight registration stamps on the reservation it
-- books. Nullable + ON DELETE SET NULL because an unset or since-removed type
-- must not block a registration; that fallback rule lives on the Club aggregate
-- per ADR 0022 directive 2, so the schema carries only the column and its FK.
--
-- The journey's two operator-email columns need no DDL — they already exist,
-- unmapped, from the V2 baseline.

ALTER TABLE t_club
    ADD COLUMN discovery_flight_type_id UUID;

ALTER TABLE t_club
    ADD CONSTRAINT fk_club_discovery_flight_type_id
        FOREIGN KEY (discovery_flight_type_id) REFERENCES t_flight_type (id) ON DELETE SET NULL;
