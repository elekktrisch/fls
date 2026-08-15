
CREATE TABLE t_person_category_assignment (
    id                  UUID          NOT NULL PRIMARY KEY,
    person_id           UUID          NOT NULL,
    person_category_id  UUID          NOT NULL,
    club_id             UUID          NOT NULL,
    created_on          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id UUID,
    deleted_on          TIMESTAMPTZ,
    deleted_by_user_id  UUID,
    CONSTRAINT fk_person_category_assignment_person_id
        FOREIGN KEY (person_id)          REFERENCES t_person (id)          ON DELETE CASCADE,
    CONSTRAINT fk_person_category_assignment_person_category_id
        FOREIGN KEY (person_category_id) REFERENCES t_person_category (id) ON DELETE CASCADE,
    CONSTRAINT fk_person_category_assignment_club_id
        FOREIGN KEY (club_id)            REFERENCES t_club (id)            ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_person_category_assignment_alive
    ON t_person_category_assignment (person_id, person_category_id)
    WHERE deleted_on IS NULL;

CREATE INDEX ix_person_category_assignment_club_category
    ON t_person_category_assignment (club_id, person_category_id);

CREATE INDEX ix_person_category_assignment_person
    ON t_person_category_assignment (person_id);
