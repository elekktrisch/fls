package ch.alpenflight.platform.hello;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Smoke-test payload returned by the hello endpoint.")
public record HelloResponse(
        @Schema(description = "Static greeting string.") String message,
        @Schema(description = "Instant the response was constructed (ISO-8601, UTC).") Instant timestamp
) {}
