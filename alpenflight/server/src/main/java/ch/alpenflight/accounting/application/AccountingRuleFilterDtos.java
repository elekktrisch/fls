package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.domain.FilterConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the AccountingRuleFilter REST surface. Records (immutable, explicit
 * field set); mass-assignment is structurally impossible because the controller
 * (T-06) binds to the record, not to the
 * {@link ch.alpenflight.accounting.domain.AccountingRuleFilter} aggregate.
 *
 * <p>{@code operatingClubId} (the {@code @TenantId} discriminator) and
 * {@code sortIndicator} (service-owned, T-05) are <strong>absent</strong> from
 * the request shapes — the tenant is set by Hibernate's {@code @TenantId}
 * resolver from the JWT on persist (A04 mass-assignment defence) and the sort
 * position is stamped by the service from {@code nextSortIndicator()}.
 *
 * <h2>The two filter-type fields</h2>
 *
 * The persisted FK is {@code filterTypeId} (UUID → {@code t_accounting_rule_filter_type}).
 * The <em>business discriminator</em> that drives the legacy {@code $scope.save}
 * target-by-type assignment is the legacy int code ({@code filterTypeLegacyId} —
 * 5/10/20/30/…). Both travel on the request: the SPA reads the {id, legacyId}
 * pair from the reference-data endpoint (T-07) and sends both. This mirrors
 * legacy, whose {@code AccountingRuleFilterTypeId} <em>was</em> the int code,
 * and keeps the target-by-type rule self-contained in the application layer
 * without a reference-table lookup.
 *
 * <h2>Target shape (legacy {@code $scope.save}, oracle #15)</h2>
 *
 * <ul>
 *   <li>type == 10 (recipient): {@code recipientMemberNumber} → {@code recipient_target}
 *       + {@code filterConfig.recipientName}; article cleared.</li>
 *   <li>type ∉ {5,10} (article): {@code articleNumber} → {@code article_target}
 *       + {@code filterConfig.deliveryLineText}; recipient cleared.</li>
 *   <li>type == 5 (DoNotInvoice): both cleared.</li>
 * </ul>
 *
 * The {@code filterConfig} carried on the request is the structured predicate
 * bag the SPA edits (the 9 flags, 5 nullable scalars, 10 {@code {useAllExcept,
 * matched[]}} match-lists); the service overrides only its
 * {@code deliveryLineText}/{@code recipientName} per the type, and normalises
 * the threshold/flight-duration fields.
 */
public final class AccountingRuleFilterDtos {

    private AccountingRuleFilterDtos() {}

    @Schema(description = "AccountingRuleFilter list-row projection — sorted by sort indicator.")
    public record AccountingRuleFilterListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ruleFilterName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID filterTypeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sortIndicator,
            // The legacy computed "Target" cell: recipient →
            // "{recipientName} ({memberNumber})", else article →
            // "{articleNumber} ({deliveryLineText})", else empty. Derived at
            // read time; never stored.
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

    /**
     * The create + update payload. Legacy has no create-only / update-only field
     * (the same edit form drives both POST and PUT), so a single write-request
     * shape serves both endpoints (T-06) and the service maps it through one
     * path — no duplicated twin record.
     *
     * <p>{@code operatingClubId} (the {@code @TenantId} discriminator) and
     * {@code sortIndicator} are intentionally absent (A04 mass-assignment
     * defence + service-owned sort).
     */
    /**
     * The three top-level booleans are nullable {@code Boolean} (not primitives)
     * so an <em>omitted</em> flag deserialises to {@code null} rather than
     * tripping Jackson's {@code FAIL_ON_NULL_FOR_PRIMITIVES} → 400 — the same
     * convention {@link ch.alpenflight.me.web.MeNotificationPrefsUpdateRequest}
     * follows. The SPA edit form ({@code accounting-edit.page.ts}) only sends
     * {@code chargedToClubInternal} in the recipient section (filter type == 10),
     * so a non-recipient create body omits it entirely; the service coerces each
     * to its legacy default ({@code active} → true, the other two → false) before
     * handing primitives to the aggregate.
     */
    @Schema(description = "Create/update payload for an AccountingRuleFilter in the caller's tenant.")
    public record AccountingRuleFilterWriteRequest(
            @NotNull UUID filterTypeId,
            // The legacy int discriminator (5/10/20/30/…) driving target-by-type.
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
