package ch.alpenflight.accounting.application;

import ch.alpenflight.platform.id.FlightId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class DeliveryDtos {

    private DeliveryDtos() {}

    @Schema(description = "Delivery list-row projection — tenant-scoped, sorted batch desc / recipient asc.")
    public record DeliveryOverview(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(description = "Booked delivery number (free-text, null until booked).") @Nullable String deliveryNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "Recipient display name (lastname firstname, or the company name).")
                    String recipientName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long batchId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "V4 process-state code: 10 Prepared / 20 Booked / 30 Error / 99 Cancelled.")
                    short processStateId) {}

    @Schema(description = "Delivery detail projection — full read-only invoice draft.")
    public record DeliveryDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(description = "Booked delivery number (free-text, null until booked).") @Nullable String deliveryNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long batchId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "V4 process-state code: 10 Prepared / 20 Booked / 30 Error / 99 Cancelled.")
                    short processStateId,
            @Schema(description = "Free-text delivery information (legacy DeliveryInformation).")
                    @Nullable String deliveryInformation,
            @Schema(description = "Free-text additional information (legacy AdditionalInformation).")
                    @Nullable String additionalInformation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DeliveryRecipientView recipient,
            @Schema(description = "The linked flight (id only; the SPA decorates date/aircraft/pilot). "
                    + "Null on a delivery not linked to a flight.") @Nullable DeliveryFlightSummary flight,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DeliveryItemView> items) {}

    @Schema(description = "Read-only line item (engine output, never hand-editable).")
    public record DeliveryItemView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int position,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String articleNumber,
            @Schema(description = "Frozen line text (legacy ItemText).") @Nullable String itemText,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal quantity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                            description = "Frozen unit-type code (legacy UnitType, e.g. Sec/Pcs/Min).")
                    String unitType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int discountInPercent) {}

    @Schema(description = "The nine frozen recipient-snapshot fields (OR Art. 957a; never re-resolved).")
    public record DeliveryRecipientView(
            @Nullable String name,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable String addressLine1,
            @Nullable String addressLine2,
            @Nullable String zipCode,
            @Nullable String city,
            @Nullable String country,
            @Nullable String clubMemberNumber) {}

    @Schema(description = "The linked flight (id only; the SPA decorates the rest — no cross-module join).")
    public record DeliveryFlightSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightId flightId) {}

    @Schema(description = "Book a Prepared delivery as delivered (external finance confirmation).")
    public record DeliveryBookingRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UUID deliveryId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Instant deliveryDateTime,
            @Schema(description = "Externally-supplied delivery (invoice) number; free-text, no counter.")
                    @Nullable String deliveryNumber) {}

    @Schema(description = "Paged delivery list envelope (SPA-compat page shape).")
    public record DeliveryPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DeliveryOverview> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageSize,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalRows) {}
}
