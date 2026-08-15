package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.EntityType;
import java.nio.file.Path;

public record EntityStreamResult(
        EntityType entityType,
        Path ndjsonTempFile,
        long rowCount,
        String sha256Hex) {

    public String tarEntryName() {
        return entityType.name() + ".ndjson";
    }
}
