CREATE TABLE t_email_template (
    id                  UUID          NOT NULL PRIMARY KEY,
    club_id             UUID          NOT NULL,
    template_key        VARCHAR(100)  NOT NULL,
    language_locale     VARCHAR(35)   NOT NULL,
    subject             VARCHAR(500)  NOT NULL,
    body                TEXT          NOT NULL,
    created_on          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by_user_id  UUID,
    modified_on         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modified_by_user_id UUID,
    CONSTRAINT fk_email_template_club_id
        FOREIGN KEY (club_id) REFERENCES t_club (id) ON DELETE RESTRICT
);

CREATE        INDEX ix_email_template_club ON t_email_template (club_id);
CREATE UNIQUE INDEX ux_email_template_club_key_locale
    ON t_email_template (club_id, template_key, language_locale);

COMMENT ON COLUMN t_email_template.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: eml-<uuid>. See ADR 0019.';
COMMENT ON TABLE t_email_template IS
    'Per-club transactional-email overrides only; system defaults are S-082 Thymeleaf files, never rows. template_key + language_locale are canonicalized lower-case by the aggregate so the UNIQUE is case-insensitive.';
