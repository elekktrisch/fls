package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.domain.FilterConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class AccountingRuleFilterDtos {

    private AccountingRuleFilterDtos() {}

    @Schema(description = "AccountingRuleFilter list-row projection — sorted by sort indicator.")
    public record AccountingRuleFilterListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ruleFilterName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID filterTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sortIndicator,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String target) {}

    @Schema(description = "AccountingRuleFilter detail projection — full round-trip payload.")
    public record AccountingRuleFilterDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID filterTypeId,
            @Nullable UUID accountingUnitTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ruleFilterName,
            @Nullable String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sortIndicator,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean stopRuleEngineWhenApplied,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean chargedToClubInternal,
            @Nullable String articleTarget,
            @Nullable String recipientTarget,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FilterConfig filterConfig) {}

    @Schema(description = "Create/update payload for an AccountingRuleFilter in the caller's tenant.")
    public record AccountingRuleFilterWriteRequest(
            @NotNull UUID filterTypeId,
            int filterTypeLegacyId,
            @Nullable UUID accountingUnitTypeId,
            @NotBlank @Size(max = 250) String ruleFilterName,
            @Nullable String description,
            @Schema(description = "Filter active (absent = true, the legacy default).") @Nullable Boolean active,
            @Schema(description = "Stop the rule engine when this rule applies (absent = false).") @Nullable Boolean stopRuleEngineWhenApplied,
            @Schema(description = "Charge the recipient to the club internally (absent = false; sent only for recipient filters).") @Nullable Boolean chargedToClubInternal,
            @Nullable @Size(max = 50) String articleNumber,
            @Nullable String deliveryLineText,
            @Nullable @Size(max = 50) String recipientMemberNumber,
            @Nullable String recipientName,
            @NotNull FilterConfig filterConfig) {}
}
