
CREATE UNIQUE INDEX ux_flight_type_club_name
    ON t_flight_type (operating_club_id, flight_type_name)
    WHERE deleted_on IS NULL;
