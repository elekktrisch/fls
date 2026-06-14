package ch.alpenflight.accounting.domain;

/**
 * Thrown when an {@link AccountingRuleFilter} create/update would violate a
 * structural business invariant the aggregate owns (ADR 0022 directive 2 —
 * the schema stays structural, the rules live here):
 *
 * <ul>
 *   <li>{@code ruleFilterName} must be non-blank. (Legacy enforced this only
 *       client-side; the V4 column is {@code NOT NULL} but accepts blank — the
 *       aggregate makes "non-blank" the real invariant.)</li>
 *   <li>{@code filterTypeId} must be present. (The discriminator that drives
 *       the whole form / rules engine — meaningless absent.)</li>
 * </ul>
 *
 * <p>Deliberately NOT enforced: per-{@code filter_type} target-field
 * requirements (article / recipient). Legacy permits empty targets (oracle),
 * so the aggregate keeps the minimal invariant set and leaves type-specific
 * field rules to the rules engine (J-9).
 */
public class InvalidAccountingRuleFilterException extends RuntimeException {

    public InvalidAccountingRuleFilterException(String message) {
        super(message);
    }
}
