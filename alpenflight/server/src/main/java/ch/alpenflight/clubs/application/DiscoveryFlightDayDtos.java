package ch.alpenflight.clubs.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public final class DiscoveryFlightDayDtos {

    private DiscoveryFlightDayDtos() {}

    @Schema(description = "A discovery-flight day the club currently offers.")
    public record DiscoveryFlightDayResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate eventDate) {}

    @Schema(description = "Payload to publish a new discovery-flight day for the caller's club.")
    public record DiscoveryFlightDayCreateRequest(
            @Schema(description = "Calendar day to offer discovery flights on. "
                    + "Must not be in the past, and must not already be offered.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull LocalDate eventDate) {}
}
