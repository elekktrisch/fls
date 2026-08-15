package ch.alpenflight.migrations.domain;

public enum MigrationRunState {

    DECRYPTING,

    PROVISIONING,

    INGESTING,

    COMPLETING,

    COMPLETED,

    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
