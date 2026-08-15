package ch.alpenflight.migrations.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record MigrationRunWarning(
        String code,
        String entityType,
        @Nullable UUID clubId,
        @Nullable UUID legacyGuid,
        String detail) {

    public MigrationRunWarning {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType must not be blank");
        }
        if (detail == null) {
            detail = "";
        }
    }
}
