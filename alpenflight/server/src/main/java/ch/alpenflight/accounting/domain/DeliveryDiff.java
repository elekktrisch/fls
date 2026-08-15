package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class DeliveryDiff {

    private static final String NL = "\n";

    private DeliveryDiff() {}

    public record Result(boolean successful, String message) {}

    public static Result compare(DeliveryDetailsSnapshot expected,
                                 DeliveryDetailsSnapshot created,
                                 boolean mustNotCreateDeliveryForFlight,
                                 IgnoreFlags flags) {
        if (mustNotCreateDeliveryForFlight) {
            boolean empty = created.items().isEmpty();
            return new Result(empty, "Flug erzeugt kein Lieferschein");
        }

        StringBuilder message = new StringBuilder();
        boolean successful = true;

        if (!flags.deliveryInformation()
                && !Objects.equals(created.deliveryInformation(), expected.deliveryInformation())) {
            successful = false;
            message.setLength(0);
            message.append("DeliveryInformation doesn't match");
        }

        if (!flags.additionalInformation()
                && !Objects.equals(created.additionalInformation(), expected.additionalInformation())) {
            successful = false;
            append(message, "AdditionalInformation doesn't match");
        }

        Recipient createdRecipient = created.recipient();
        Recipient expectedRecipient = expected.recipient();

        if (!flags.recipientPersonId()
                && !Objects.equals(personId(createdRecipient), personId(expectedRecipient))) {
            successful = false;
            append(message, "PersonId doesn't match");
        }

        if (!flags.recipientClubMemberNumber()
                && !Objects.equals(memberNumber(createdRecipient), memberNumber(expectedRecipient))) {
            successful = false;
            append(message, "PersonClubMemberNumber doesn't match");
        }

        if (!flags.recipientName()) {
            if (!Objects.equals(lastname(createdRecipient), lastname(expectedRecipient))) {
                successful = false;
                append(message, "Lastname doesn't match");
            }
            if (!Objects.equals(firstname(createdRecipient), firstname(expectedRecipient))) {
                successful = false;
                append(message, "Firstname doesn't match");
            }
        }


        List<DeliveryItemDetails> createdItems = created.items();
        List<DeliveryItemDetails> expectedItems = expected.items();

        if (createdItems.size() != expectedItems.size()) {
            successful = false;
            append(message, "Numbers of delivery items doesn't match");
        }

        for (DeliveryItemDetails expectedItem : expectedItems) {
            DeliveryItemDetails createdItem = matchCreated(createdItems, expectedItem, flags.itemPositioning());

            if (createdItem == null) {
                successful = false;
                append(message, "Item at position " + expectedItem.position()
                        + " or with ArticleNumber " + expectedItem.articleNumber() + " not found!");
                continue;
            }

            if (!Objects.equals(createdItem.articleNumber(), expectedItem.articleNumber())) {
                successful = false;
                append(message, "Expected ArticleNumber " + expectedItem.articleNumber()
                        + " doesn't match with " + createdItem.articleNumber()
                        + " for expected item at position " + expectedItem.position());
            }

            if (createdItem.quantity().compareTo(expectedItem.quantity()) != 0) {
                successful = false;
                append(message, "Expected Quantity " + expectedItem.quantity()
                        + " doesn't match with " + createdItem.quantity()
                        + " for expected item at position " + expectedItem.position());
            }

            if (createdItem.discountInPercent() != expectedItem.discountInPercent()) {
                successful = false;
                append(message, "Expected DiscountInPercent " + expectedItem.discountInPercent()
                        + " doesn't match with " + createdItem.discountInPercent()
                        + " for expected item at position " + expectedItem.position());
            }

            if (!Objects.equals(createdItem.unitType(), expectedItem.unitType())) {
                successful = false;
                append(message, "Expected UnitType " + expectedItem.unitType()
                        + " doesn't match with " + createdItem.unitType()
                        + " for expected item at position " + expectedItem.position());
            }

            if (!flags.itemText()
                    && !Objects.equals(createdItem.itemText(), expectedItem.itemText())) {
                successful = false;
                append(message, "Expected ItemText " + expectedItem.itemText()
                        + " doesn't match with " + createdItem.itemText()
                        + " for expected item at position " + expectedItem.position());
            }

            if (!flags.itemAdditionalInformation()
                    && !Objects.equals(createdItem.additionalInformation(), expectedItem.additionalInformation())) {
                successful = false;
                append(message, "Expected AdditionalInformation " + expectedItem.additionalInformation()
                        + " doesn't match with " + createdItem.additionalInformation()
                        + " for expected item at position " + expectedItem.position());
            }
        }

        if (createdItems.size() > expectedItems.size()) {
            for (DeliveryItemDetails createdItem : createdItems) {
                boolean inExpected = expectedItems.stream()
                        .anyMatch(x -> Objects.equals(x.articleNumber(), createdItem.articleNumber()));
                if (!inExpected) {
                    successful = false;
                    append(message, "Item with ArticleNumber " + createdItem.articleNumber()
                            + " not found in expected delivery items! Created item to much.");
                }
            }
        }

        return new Result(successful, message.toString());
    }

    private static @Nullable DeliveryItemDetails matchCreated(List<DeliveryItemDetails> createdItems,
                                                              DeliveryItemDetails expectedItem,
                                                              boolean ignorePositioning) {
        for (DeliveryItemDetails created : createdItems) {
            boolean matches = ignorePositioning
                    ? Objects.equals(created.articleNumber(), expectedItem.articleNumber())
                    : created.position() == expectedItem.position();
            if (matches) {
                return created;
            }
        }
        return null;
    }

    private static void append(StringBuilder message, String line) {
        if (message.length() > 0) {
            message.append(NL);
        }
        message.append(line);
    }

    private static @Nullable Object personId(@Nullable Recipient r) {
        return r == null ? null : r.personId();
    }

    private static @Nullable String memberNumber(@Nullable Recipient r) {
        return r == null ? null : r.personClubMemberNumber();
    }

    private static @Nullable String lastname(@Nullable Recipient r) {
        return r == null ? null : r.lastname();
    }

    private static @Nullable String firstname(@Nullable Recipient r) {
        return r == null ? null : r.firstname();
    }
}
