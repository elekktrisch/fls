
CREATE TABLE t_migration_upload (
    id                       UUID         NOT NULL PRIMARY KEY,
    user_id                  UUID         NOT NULL REFERENCES t_user(id),
    state                    VARCHAR(32)  NOT NULL,
    public_key_pem           TEXT         NOT NULL,
    private_key_ciphertext   BYTEA            NULL,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    expires_at               TIMESTAMPTZ  NOT NULL,
    consumed_at              TIMESTAMPTZ      NULL
);

CREATE UNIQUE INDEX ux_migration_upload_user_awaiting
    ON t_migration_upload (user_id)
    WHERE state = 'awaiting_upload';

CREATE INDEX ix_migration_upload_expiry
    ON t_migration_upload (state, expires_at);

COMMENT ON TABLE t_migration_upload IS
    'Per-upload RSA-4096 handshake row (S-140). Pre-tenant: no club_id / @TenantId. private_key_ciphertext is Tink-AEAD-wrapped PKCS#8 DER; uploadId bound as associatedData.';

COMMENT ON COLUMN t_migration_upload.state IS
    'State machine enforced in Java (MigrationUploadState enum) — no CHECK per ADR 0022 D2.';

COMMENT ON COLUMN t_migration_upload.private_key_ciphertext IS
    'NULL once the row leaves awaiting_upload — wiped on supersede / expire / consume / fail.';
