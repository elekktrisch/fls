package ch.alpenflight.migrations.domain;

public enum MigrationUploadState {

    AWAITING_UPLOAD,

    SUPERSEDED,

    EXPIRED,

    FAILED,

    CONSUMED;

    public boolean isInFlight() {
        return this == AWAITING_UPLOAD;
    }
}
