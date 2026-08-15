ALTER TABLE t_planning_day_assignment_type
    RENAME CONSTRAINT fk_pdat_operating_club_id
        TO fk_planning_day_assignment_type_operating_club_id;

ALTER TABLE t_planning_day
    RENAME CONSTRAINT fk_pln_operating_club_id
        TO fk_planning_day_operating_club_id;
