package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.domain.DeliveryDetailsSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the DeliveryCreationTest REST surface (the rules-engine regression
 * harness, J-9). Records (immutable, explicit field set); mass-assignment is
 * structurally impossible because the controller binds to the record, not to the
 * {@link ch.alpenflight.accounting.domain.DeliveryCreationTest} aggregate.
 *
 * <p>{@code operatingClubId} (the {@code @TenantId} discriminator) is
 * <strong>absent</strong> from the request shape — the tenant is set by
 * Hibernate's {@code @TenantId} resolver from the JWT on persist (A04
 * mass-assignment defence). The captured expected payload + the {@code lastTest*}
 * run-state are owned by the dry-run / run-test endpoints (T-15), not editable
 * through this CRUD write request.
 *
 * <h2>Optional booleans</h2>
 *
 * {@code active}, {@code mustNotCreateDeliveryForFlight} and the nine
 * {@code ignore*} flags are nullable {@code Boolean} (not primitives) so an
 * <em>omitted</em> flag deserialises to {@code null} rather than tripping
 * Jackson's {@code FAIL_ON_NULL_FOR_PRIMITIVES} → 400 — the SPA edit form only
 * sends the flags the operator toggles. The service coerces each to its legacy
 * default ({@code active} → true, the rest → false) before handing the
 * {@link ch.alpenflight.accounting.domain.IgnoreFlags} VO to the aggregate.
 */
public final class DeliveryCreationTestDtos {

    private DeliveryCreationTestDtos() {}

    @Schema(description = "DeliveryCreationTest list-row projection — tenant-scoped, ordered by name.")
    public record DeliveryCreationTestListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String testName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID flightId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(description = "Last run's result (null = never run).") @Nullable Boolean lastTestSuccessful,
            @Schema(description = "When the harness was last run (null = never run).") @Nullable Instant lastTestRunOn) {}

    @Schema(description = "DeliveryCreationTest detail projection — full round-trip payload.")
    public record DeliveryCreationTestDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID flightId,
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
            // The lastTest* run-state is read-only here — owned by the run-test
            // endpoint (T-15). A never-run harness leaves all four null.
            @Schema(description = "Last run's result (null = never run).") @Nullable Boolean lastTestSuccessful,
            @Schema(description = "Last run's diff message (null = never run).") @Nullable String lastTestResultMessage,
            @Schema(description = "When the harness was last run (null = never run).") @Nullable Instant lastTestRunOn,
            @Schema(description = "The engine output captured by the last run (null = never run).")
                    @Nullable DeliveryDetailsSnapshot lastTestCreatedDelivery,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> lastTestMatchedFilterIds) {}

    /**
     * The create + update payload. Legacy drives both POST and PUT from the same
     * edit form, so one write-request shape serves both endpoints and the service
     * maps it through one path.
     *
     * <p>{@code expectedDelivery} + {@code expectedMatchedFilterIds} carry the
     * dry-run output the SPA captured ({@code exampleResult.delivery} /
     * {@code .matchedFilterIds}) — both nullable, because a harness can be saved
     * before its first dry-run (no expectation yet, legal). When present they
     * become the harness's persisted expected set via the aggregate's
     * {@code captureExpected}; without them the run-state is untouched. The
     * {@code lastTest*} run-state stays read-only here — owned by the run-test
     * endpoint (T-15).
     */
    @Schema(description = "Create/update payload for a DeliveryCreationTest in the caller's tenant.")
    public record DeliveryCreationTestWriteRequest(
            @NotNull UUID flightId,
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

    /**
     * The dry-run result — the engine output for a flight WITHOUT persistence
     * (legacy {@code generateExampleDelivery}). The SPA edit form uses it to FILL
     * the expected set when authoring a harness.
     */
    @Schema(description = "Dry-run engine output for a flight — no persistence (fills the expected set).")
    public record ExampleDeliveryResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DeliveryDetailsSnapshot delivery,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> matchedFilterIds) {}

    /**
     * The run-test result — the engine ran against the harness's stored flight and
     * was diffed against the expected set (legacy {@code LastTestSuccessful} /
     * {@code LastTestResultMessage} / {@code LastTestCreatedDeliveryDetails} /
     * {@code LastTestMatchedAccountingRuleFilterIds}). Persisted as the harness's
     * run-state before being returned.
     */
    @Schema(description = "Run-test result — pass/fail + the field-by-field diff message + the engine output.")
    public record RunTestResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean lastTestSuccessful,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lastTestResultMessage,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DeliveryDetailsSnapshot lastTestCreatedDelivery,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> lastTestMatchedFilterIds) {}
}
