package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryBookDomainTest {

    private static final UUID CLUB = UUID.randomUUID();
    private static final UUID FLIGHT = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID ARTICLE = UUID.randomUUID();
    private static final Instant DELIVERED_AT = Instant.parse("2026-06-25T09:30:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void book_stampsNumberDeliveredOnAndFlipsToBooked() {
        Delivery delivery = preparedDelivery();
        assertThat(delivery.getProcessState()).isEqualTo(DeliveryProcessState.PREPARED);

        delivery.book(7_042, DELIVERED_AT);

        assertThat(delivery.getDeliveryNumber()).isEqualTo(7_042);
        assertThat(delivery.getDeliveredOn()).isEqualTo(DELIVERED_AT);
        assertThat(delivery.getProcessState()).isEqualTo(DeliveryProcessState.BOOKED);
        assertThat(delivery.isBooked()).isTrue();
    }

    @Test
    void bookedDelivery_rejectsReBook_andDoesNotMutate() {
        Delivery delivery = preparedDelivery();
        delivery.book(7_042, DELIVERED_AT);

        assertThatThrownBy(() -> delivery.book(8_000, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(DeliveryBookedTerminalException.class);

        assertThat(delivery.getDeliveryNumber())
                .as("a rejected re-book leaves the original number untouched")
                .isEqualTo(7_042);
        assertThat(delivery.getDeliveredOn()).isEqualTo(DELIVERED_AT);
    }

    @Test
    void bookedDelivery_rejectsDelete() {
        Delivery delivery = preparedDelivery();
        delivery.book(7_042, DELIVERED_AT);

        assertThatThrownBy(() -> delivery.delete(null, CLOCK))
                .isInstanceOf(DeliveryBookedTerminalException.class);

        assertThat(delivery.isDeleted())
                .as("a rejected delete-of-booked leaves the row un-deleted")
                .isFalse();
    }

    private static Delivery preparedDelivery() {
        RuleBasedDeliveryDetails computed = RuleBasedDeliveryDetails.forClub(CLUB);
        computed.setRecipient(new Recipient(PERSON, "M-77", null, "Petra", "Pilot"));
        computed.addItem(new DeliveryItemDetails(
                0, "ART-FT", "HB-X Flugzeit", null, new BigDecimal("90"), 0, "Min"));
        return Delivery.createFromEligibleFlight(CLUB, FLIGHT, computed, 1L, number -> ARTICLE);
    }
}
