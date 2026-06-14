package ch.alpenflight.accounting.domain;

/**
 * The nine diff-suppression toggles a {@link DeliveryCreationTest} carries
 * (legacy {@code DeliveryCreationTest.IgnoreXxx} flags). When a flag is set, the
 * run-test field-by-field comparison (T-15) treats that field's mismatch as
 * expected, so it does not fail the harness — the operator's way to scope a
 * regression test to the fields the rule under tuning actually drives.
 *
 * <p>A plain immutable domain record (NOT a JPA {@code @Embeddable}) — the
 * aggregate explodes it to nine boolean columns on create/update and reassembles
 * it on read, keeping the schema flat and every field individually visible to the
 * S-027 redaction-coverage scan.
 */
public record IgnoreFlags(
        boolean recipientName,
        boolean recipientAddress,
        boolean recipientPersonId,
        boolean recipientClubMemberNumber,
        boolean deliveryInformation,
        boolean additionalInformation,
        boolean itemPositioning,
        boolean itemText,
        boolean itemAdditionalInformation) {

    /** All flags off — compare every field (the V4 column defaults + a new harness). */
    public static IgnoreFlags none() {
        return new IgnoreFlags(false, false, false, false, false, false, false, false, false);
    }
}
