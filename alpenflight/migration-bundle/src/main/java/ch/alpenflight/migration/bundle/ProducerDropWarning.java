package ch.alpenflight.migration.bundle;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ProducerDropWarning(
        String code,
        EntityType entityType,
        @Nullable UUID clubId,
        @Nullable UUID legacyGuid,
        // RENAME: detail -> nonPiiDetail
        String detail) {

    public ProducerDropWarning {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (entityType == null) {
            throw new IllegalArgumentException("entityType must not be null");
        }
        if (detail == null) {
            detail = "";
        }
    }
}
