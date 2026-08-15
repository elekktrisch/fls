package ch.alpenflight.platform.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class OpenApiSnapshotNormalize {

    private OpenApiSnapshotNormalize() {}

    static void stripVolatile(JsonNode root) {
        JsonNode info = root.path("info");
        if (info instanceof ObjectNode obj) {
            obj.remove("version");
        }
    }
}
