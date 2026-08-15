package ch.alpenflight.accounting.domain;

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

    public static IgnoreFlags none() {
        return new IgnoreFlags(false, false, false, false, false, false, false, false, false);
    }
}
