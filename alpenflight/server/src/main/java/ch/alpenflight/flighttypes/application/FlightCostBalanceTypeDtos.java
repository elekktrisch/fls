package ch.alpenflight.flighttypes.application;

import ch.alpenflight.platform.id.FlightCostBalanceTypeId;
import io.swagger.v3.oas.annotations.media.Schema;

public final class FlightCostBalanceTypeDtos {

    private FlightCostBalanceTypeDtos() {}

    @Schema(description = "FlightCostBalanceType reference-list projection.")
    public record FlightCostBalanceTypeResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightCostBalanceTypeId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isPersonForInvoiceRequired,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForGlider,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForTow,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isForMotor) {}
}
