package ch.alpenflight.platform.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared volatile-field stripping for the OpenAPI snapshot. Used both by the
 * Gradle-task driver ({@link OpenApiSnapshotMain}) and the drift integration
 * test ({@code OpenApiSnapshotIT}) — the rule "what counts as drift" must be
 * identical in both places.
 *
 * <p>Currently strips {@code $.info.version}, which would otherwise churn on
 * every release-version bump.
 */
final class OpenApiSnapshotNormalize {

    private OpenApiSnapshotNormalize() {}

    static void stripVolatile(JsonNode root) {
        JsonNode info = root.path("info");
        if (info instanceof ObjectNode obj) {
            obj.remove("version");
        }
    }
}
