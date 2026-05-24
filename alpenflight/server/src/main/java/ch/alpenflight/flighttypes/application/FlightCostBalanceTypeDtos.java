package ch.alpenflight.flighttypes.application;

import ch.alpenflight.platform.id.FlightCostBalanceTypeId;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTOs for the FlightCostBalanceType REST surface. Read-only at S-053 — no
 * admin CRUD until S-058 / S-072 demand it. {@code legacyIntId} is
 * intentionally omitted from the wire shape (cutover-importer artifact, not
 * caller-relevant).
 */
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
