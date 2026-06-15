package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.accounting.domain.DeliveryProcessState.DeliveryProcessStateConverter;
import ch.alpenflight.server.testsupport.DeliveryTestHydrator;
import org.junit.jupiter.api.Test;

class DeliveryDomainTest {

    private final DeliveryProcessStateConverter converter = new DeliveryProcessStateConverter();

    @Test
    void processState_mapsTheFourSparseV4Codes() {
        assertThat(DeliveryProcessState.PREPARED.code()).isEqualTo((short) 10);
        assertThat(DeliveryProcessState.BOOKED.code()).isEqualTo((short) 20);
        assertThat(DeliveryProcessState.ERROR.code()).isEqualTo((short) 30);
        assertThat(DeliveryProcessState.CANCELLED.code()).isEqualTo((short) 99);

        assertThat(DeliveryProcessState.fromCode((short) 10)).isEqualTo(DeliveryProcessState.PREPARED);
        assertThat(DeliveryProcessState.fromCode((short) 99)).isEqualTo(DeliveryProcessState.CANCELLED);
    }

    @Test
    void processState_unknownCode_rejects() {
        assertThatThrownBy(() -> DeliveryProcessState.fromCode((short) 11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11");
    }

    @Test
    void converter_roundTripsCodeAndNull() {
        assertThat(converter.convertToDatabaseColumn(DeliveryProcessState.BOOKED)).isEqualTo((short) 20);
        assertThat(converter.convertToEntityAttribute((short) 30)).isEqualTo(DeliveryProcessState.ERROR);
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void recipient_carriesAllNineFrozenSnapshotFields() {
        DeliveryRecipient recipient = new DeliveryRecipient(
                "Petra Pilot", "Petra", "Pilot",
                "Flugplatzstrasse 1", "Hangar 3",
                "8000", "Zürich", "Schweiz", "M-4711");

        assertThat(recipient.name()).isEqualTo("Petra Pilot");
        assertThat(recipient.firstname()).isEqualTo("Petra");
        assertThat(recipient.lastname()).isEqualTo("Pilot");
        assertThat(recipient.addressLine1()).isEqualTo("Flugplatzstrasse 1");
        assertThat(recipient.addressLine2()).isEqualTo("Hangar 3");
        assertThat(recipient.zipCode()).isEqualTo("8000");
        assertThat(recipient.city()).isEqualTo("Zürich");
        assertThat(recipient.countryName()).isEqualTo("Schweiz");
        assertThat(recipient.personClubMemberNumber()).isEqualTo("M-4711");

        assertThat(DeliveryRecipient.empty())
                .isEqualTo(new DeliveryRecipient(null, null, null, null, null, null, null, null, null));
    }

    @Test
    void delivery_defaultsToPreparedWithEmptyRecipientAndNoItems() {
        Delivery delivery = DeliveryTestHydrator.bare();

        assertThat(delivery.getProcessState()).isEqualTo(DeliveryProcessState.PREPARED);
        assertThat(delivery.getRecipient()).isEqualTo(DeliveryRecipient.empty());
        assertThat(delivery.getItems()).isEmpty();
        assertThat(delivery.getBatchId()).isZero();
        assertThat(delivery.isDeleted()).isFalse();
    }
}
