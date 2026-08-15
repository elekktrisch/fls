package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.domain.DeliveryDetailsSnapshot;
import ch.alpenflight.platform.id.FlightId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class DeliveryCreationTestDtos {

    private DeliveryCreationTestDtos() {}

    @Schema(description = "DeliveryCreationTest list-row projection — tenant-scoped, ordered by name.")
    public record DeliveryCreationTestListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String testName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightId flightId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(description = "Last run's result (null = never run).") @Nullable Boolean lastTestSuccessful,
            @Schema(description = "When the harness was last run (null = never run).") @Nullable Instant lastTestRunOn) {}

    @Schema(description = "DeliveryCreationTest detail projection — full round-trip payload.")
    public record DeliveryCreationTestDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FlightId flightId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String testName,
            @Nullable String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean mustNotCreateDeliveryForFlight,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ignoreRecipientName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ignoreRecipientAddress,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ignoreRecipientPersonId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ignoreRecipientClubMemberNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ignoreDeliveryInformation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ignoreAdditionalInformation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ignoreItemPositioning,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ignoreItemText,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ignoreItemAdditionalInformation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DeliveryDetailsSnapshot expectedDelivery,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> expectedMatchedFilterIds,
            @Schema(description = "Last run's result (null = never run).") @Nullable Boolean lastTestSuccessful,
            @Schema(description = "Last run's diff message (null = never run).") @Nullable String lastTestResultMessage,
            @Schema(description = "When the harness was last run (null = never run).") @Nullable Instant lastTestRunOn,
            @Schema(description = "The engine output captured by the last run (null = never run).")
                    @Nullable DeliveryDetailsSnapshot lastTestCreatedDelivery,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> lastTestMatchedFilterIds) {}

    @Schema(description = "Create/update payload for a DeliveryCreationTest in the caller's tenant.")
    public record DeliveryCreationTestWriteRequest(
            @NotNull FlightId flightId,
            @NotBlank @Size(max = 250) String testName,
            @Nullable String description,
            @Schema(description = "Captured dry-run output to persist as the expected set (absent = leave unchanged).")
                    @Nullable DeliveryDetailsSnapshot expectedDelivery,
            @Schema(description = "Matched AccountingRuleFilter ids from the captured dry-run (absent = empty).")
                    @Nullable List<UUID> expectedMatchedFilterIds,
            @Schema(description = "Harness active (absent = true, the legacy default).") @Nullable Boolean active,
            @Schema(description = "Assert the flight produces NO delivery (absent = false).")
                    @Nullable Boolean mustNotCreateDeliveryForFlight,
            @Schema(description = "Ignore recipient-name mismatch in the diff (absent = false).")
                    @Nullable Boolean ignoreRecipientName,
            @Schema(description = "Ignore recipient-address mismatch in the diff (absent = false).")
                    @Nullable Boolean ignoreRecipientAddress,
            @Schema(description = "Ignore recipient-person-id mismatch in the diff (absent = false).")
                    @Nullable Boolean ignoreRecipientPersonId,
            @Schema(description = "Ignore recipient-club-member-number mismatch in the diff (absent = false).")
                    @Nullable Boolean ignoreRecipientClubMemberNumber,
            @Schema(description = "Ignore delivery-information mismatch in the diff (absent = false).")
                    @Nullable Boolean ignoreDeliveryInformation,
            @Schema(description = "Ignore additional-information mismatch in the diff (absent = false).")
                    @Nullable Boolean ignoreAdditionalInformation,
            @Schema(description = "Ignore item-positioning mismatch in the diff (absent = false).")
                    @Nullable Boolean ignoreItemPositioning,
            @Schema(description = "Ignore item-text mismatch in the diff (absent = false).")
                    @Nullable Boolean ignoreItemText,
            @Schema(description = "Ignore item-additional-information mismatch in the diff (absent = false).")
                    @Nullable Boolean ignoreItemAdditionalInformation) {}

    @Schema(description = "Dry-run engine output for a flight — no persistence (fills the expected set).")
    public record ExampleDeliveryResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DeliveryDetailsSnapshot delivery,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> matchedFilterIds) {}

    @Schema(description = "Run-test result — pass/fail + the field-by-field diff message + the engine output.")
    public record RunTestResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean lastTestSuccessful,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lastTestResultMessage,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DeliveryDetailsSnapshot lastTestCreatedDelivery,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> lastTestMatchedFilterIds) {}
}
