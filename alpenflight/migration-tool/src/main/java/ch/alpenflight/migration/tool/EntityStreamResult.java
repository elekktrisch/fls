package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.EntityType;
import java.nio.file.Path;

/**
 * Per-entity outcome of the NDJSON streaming pass: where the temp NDJSON
 * landed, how many rows it carries, and the hex sha256 of the stream bytes.
 *
 * <p>The sha256 + row count feed {@code --verbose} / {@code --dry-run}
 * operator stats. They are NOT serialized into {@code manifest.json} — the
 * server's manifest has no checksum field and rejects unknown fields (see
 * {@link ManifestModel}).
 */
public record EntityStreamResult(
        EntityType entityType,
        Path ndjsonTempFile,
        long rowCount,
        String sha256Hex) {

    /** Tar entry name the server's ingest maps back to this {@link EntityType}. */
    public String tarEntryName() {
        return entityType.name() + ".ndjson";
    }
}
