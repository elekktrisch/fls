package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One active {@link AccountingRuleFilter}, resolved into the pure-data shape the
 * engine stages consume — the per-filter equivalent of the legacy
 * {@code RuleBasedAccountingRuleFilterDetails}. The engine orchestrator (T-12)
 * builds these from the aggregate rows it loads {@code ORDER BY sort_indicator,
 * id}, already narrowed to the active filters of one legacy filter-type, so the
 * stages operate on pure data with no JPA / reference-data dependency.
 *
 * <p>{@code recipientTarget} is the pre-resolved {@link Recipient} a
 * {@link RecipientStage} sets when this filter matches; {@code null} reproduces
 * the legacy null-{@code RecipientTarget} case the recipient rule throws on. It
 * is unused by {@link IgnoreFlightStage} (a DoNotInvoice filter only flips a
 * flag) — pass {@code null} there.
 *
 * <p>{@code articleNumber} + {@code accountingUnitType} are the item-emitting
 * facets the line-billing stages ({@link FlightTimeStage}, and EngineTime /
 * single-pass stages to come) need but which the aggregate cannot hand the
 * domain directly: {@code articleNumber} lives on the filter's {@code article_target}
 * column and {@code accountingUnitType} is resolved from the filter's
 * {@code accounting_unit_type_id} UUID. The orchestrator (T-12) resolves both and
 * supplies them; they are {@code null} for filters that emit no line (recipient /
 * ignore), and pass {@link #of} there.
 */
public record RuleFilterInput(
        UUID filterId,
        @Nullable Recipient recipientTarget,
        @Nullable String articleNumber,
        @Nullable AccountingUnitType accountingUnitType,
        FilterConfig filterConfig) {

    /** A non-line-emitting filter (recipient / ignore): no article / unit. */
    public static RuleFilterInput of(
            UUID filterId, @Nullable Recipient recipientTarget, FilterConfig filterConfig) {
        return new RuleFilterInput(filterId, recipientTarget, null, null, filterConfig);
    }
}
