package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryDetail;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryFlightSummary;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryItemView;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryOverview;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryRecipientView;
import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryItem;
import ch.alpenflight.accounting.domain.DeliveryRecipient;
import ch.alpenflight.platform.id.FlightId;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class DeliveryDetailMapper {

    private DeliveryDetailMapper() {}

    static DeliveryOverview toOverview(Delivery delivery) {
        return new DeliveryOverview(
                requireId(delivery),
                delivery.getDeliveryNumber(),
                recipientDisplayName(delivery.getRecipient()),
                delivery.getBatchId(),
                delivery.getProcessState().code());
    }

    static DeliveryDetail toDetail(Delivery delivery) {
        return new DeliveryDetail(
                requireId(delivery),
                delivery.getDeliveryNumber(),
                delivery.getBatchId(),
                delivery.getProcessState().code(),
                delivery.getDeliveryInformation(),
                delivery.getAdditionalInformation(),
                toRecipientView(delivery.getRecipient()),
                toFlightSummary(delivery.getFlightId()),
                delivery.getItems().stream().map(DeliveryDetailMapper::toItemView).toList());
    }

    private static DeliveryItemView toItemView(DeliveryItem item) {
        return new DeliveryItemView(
                item.getPosition(),
                item.getArticleNumber(),
                item.getItemText(),
                item.getQuantity(),
                item.getUnitTypeCode(),
                item.getDiscountInPercent());
    }

    private static DeliveryRecipientView toRecipientView(DeliveryRecipient r) {
        return new DeliveryRecipientView(
                r.name(), r.firstname(), r.lastname(),
                r.addressLine1(), r.addressLine2(),
                r.zipCode(), r.city(), r.countryName(),
                r.personClubMemberNumber());
    }

    private static @Nullable DeliveryFlightSummary toFlightSummary(@Nullable UUID flightId) {
        return flightId == null ? null : new DeliveryFlightSummary(FlightId.of(flightId));
    }

    private static String recipientDisplayName(DeliveryRecipient r) {
        String name = r.name();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String last = orEmpty(r.lastname());
        String first = orEmpty(r.firstname());
        return (last + " " + first).trim();
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static UUID requireId(Delivery delivery) {
        return Objects.requireNonNull(delivery.getId(), "Cannot map an unpersisted Delivery");
    }
}
