-- S-184 Ports legacy `PersonPersonCategories` M:N junction into a tenant-
-- scoped direct association. Legacy carried only (PersonId, PersonCategoryId);
-- new schema denormalises club_id from the linked category for @TenantId
-- routing per ADR 0008 — the legacy producer joins PersonCategories to fill
-- it on the wire.
--
-- S-141 ingest applies this junction at the PERSON_CATEGORY_ASSIGNMENT
-- pass after the upstream PERSON / PERSON_CATEGORY / CLUB passes
-- complete. Tenant-scoped (`club_id` is the @TenantId discriminator). FKs:
--   * person_id           → t_person          cross-tenant (sacred-cow split)
--   * person_category_id  → t_person_category tenant-scoped
--   * club_id             → t_club            tenant-scoped
-- Tombstone semantics align with t_person_club: a soft-deleted assignment
-- still occupies the natural-key pair, so the alive UNIQUE is partial on
-- deleted_on IS NULL.

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
