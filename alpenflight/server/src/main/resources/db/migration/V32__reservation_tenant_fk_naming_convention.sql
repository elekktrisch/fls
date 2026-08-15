ALTER TABLE t_aircraft_reservation_type
    RENAME CONSTRAINT fk_arvt_operating_club_id
        TO fk_aircraft_reservation_type_operating_club_id;

ALTER TABLE t_aircraft_reservation
    RENAME CONSTRAINT fk_arv_operating_club_id
        TO fk_aircraft_reservation_operating_club_id;
