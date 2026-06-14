package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable typed value object for the harness jsonb columns
 * {@code t_delivery_creation_test.expected_delivery} and
 * {@code .last_test_created_delivery} — the comparable subset of a
 * {@link RuleBasedDeliveryDetails} the test diffs (the engine's <em>emitted</em>
 * output, never its internal working state). Persisted on
 * {@link DeliveryCreationTest} via {@code @JdbcTypeCode(SqlTypes.JSON)} (the J-8
 * {@code FilterConfig} jsonb precedent: a typed record, not a raw String, so the
 * harness round-trips every field and T-15's field-by-field diff reads typed
 * values rather than re-parsing JSON).
 *
 * <p>Carries NO Jackson annotations — Hibernate's auto-registered Jackson
 * {@code FormatMapper} serialises records reflectively by component name, and
 * {@code accounting.domain} stays Jackson-free (ADR 0023).
 *
 * <p>Mirrors {@link RuleBasedDeliveryDetails}'s emitted fields: the line items,
 * the resolved recipient, and the two free-text information fields. The engine's
 * working state (active times, suppression flags, do-not-invoice short-circuit,
 * matched-filter ids) is deliberately absent — matched ids live in the dedicated
 * {@code expected_matched_filter_ids} / {@code last_test_matched_filter_ids}
 * UUID[] columns, not in this payload.
 */
public record DeliveryDetailsSnapshot(
        List<DeliveryItemDetails> items,
        @Nullable Recipient recipient,
        @Nullable String deliveryInformation,
        @Nullable String additionalInformation) {

    public DeliveryDetailsSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** The expected payload of a brand-new harness before its first dry-run captures one. */
    public static DeliveryDetailsSnapshot empty() {
        return new DeliveryDetailsSnapshot(List.of(), null, null, null);
    }

    /** Projects the comparable subset out of an engine run's accumulator. */
    public static DeliveryDetailsSnapshot of(RuleBasedDeliveryDetails details) {
        return new DeliveryDetailsSnapshot(
                details.deliveryItems(),
                details.recipient(),
                details.getDeliveryInformation(),
                details.getAdditionalInformation());
    }
}
