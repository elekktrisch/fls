package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import ch.alpenflight.persons.domain.Person;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryCreateDomainTest {

    private static final UUID CLUB = UUID.randomUUID();
    private static final UUID FLIGHT = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID ARTICLE = UUID.randomUUID();

    @Test
    void createFromEligibleFlight_mapsRecipientSnapshotItemsAndBatch() {
        RuleBasedDeliveryDetails computed = RuleBasedDeliveryDetails.forClub(CLUB);
        computed.setRecipient(new Recipient(PERSON, "M-77", null, "Petra", "Pilot"));
        computed.setDeliveryInformation("flight info");
        computed.addItem(new DeliveryItemDetails(
                0, "ART-FT", "HB-X Flugzeit", null, new BigDecimal("90"), 25, "Min"));

        Delivery delivery = Delivery.createFromEligibleFlight(
                CLUB, FLIGHT, computed, 42L, number -> ARTICLE);

        assertThat(delivery.getProcessState()).isEqualTo(DeliveryProcessState.PREPARED);
        assertThat(delivery.getOperatingClubId()).isEqualTo(CLUB);
        assertThat(delivery.getFlightId()).isEqualTo(FLIGHT);
        assertThat(delivery.getBatchId()).isEqualTo(42L);
        assertThat(delivery.getRecipientPersonId()).isEqualTo(PERSON);
        assertThat(delivery.getRecipient().lastname()).isEqualTo("Pilot");
        assertThat(delivery.getRecipient().personClubMemberNumber()).isEqualTo("M-77");
        assertThat(delivery.getDeliveryInformation()).isEqualTo("flight info");
        assertThat(delivery.getItems()).hasSize(1);
        DeliveryItem item = delivery.getItems().getFirst();
        assertThat(item.getArticleNumber()).isEqualTo("ART-FT");
        assertThat(item.getArticleId()).isEqualTo(ARTICLE);
        assertThat(item.getQuantity()).isEqualByComparingTo("90");
        assertThat(item.getDiscountInPercent()).isEqualTo(25);
    }

    @Test
    void createFromEligibleFlight_noRecipientOrNoItems_isPreparationError() {
        RuleBasedDeliveryDetails noRecipient = RuleBasedDeliveryDetails.forClub(CLUB);
        noRecipient.addItem(new DeliveryItemDetails(
                0, "ART-FT", "txt", null, BigDecimal.ONE, 0, "Min"));
        assertThatThrownBy(() -> Delivery.createFromEligibleFlight(
                CLUB, FLIGHT, noRecipient, 1L, number -> ARTICLE))
                .isInstanceOf(DeliveryPreparationException.class);

        RuleBasedDeliveryDetails noItems = RuleBasedDeliveryDetails.forClub(CLUB);
        noItems.setRecipient(new Recipient(PERSON, "M-77", null, "Petra", "Pilot"));
        assertThatThrownBy(() -> Delivery.createFromEligibleFlight(
                CLUB, FLIGHT, noItems, 1L, number -> ARTICLE))
                .isInstanceOf(DeliveryPreparationException.class);
    }

    @Test
    void createFromEligibleFlight_unresolvableArticle_isPreparationError() {
        RuleBasedDeliveryDetails computed = RuleBasedDeliveryDetails.forClub(CLUB);
        computed.setRecipient(new Recipient(PERSON, "M-77", null, "Petra", "Pilot"));
        computed.addItem(new DeliveryItemDetails(
                0, "ART-MISSING", "txt", null, BigDecimal.ONE, 0, "Min"));

        assertThatThrownBy(() -> Delivery.createFromEligibleFlight(
                CLUB, FLIGHT, computed, 1L, number -> null))
                .isInstanceOf(DeliveryPreparationException.class)
                .hasMessageContaining("ART-MISSING");
    }

    @Test
    void consumption_appendsCurrentRowAndFlipsPriorCurrent() {
        PersonFlightTimeCredit credit = grantedCredit(5_400L);
        UUID deliveryId = UUID.randomUUID();
        Instant at = Instant.parse("2026-06-25T10:00:00Z");

        recordConsumption(credit, 3_600L, deliveryId, at);

        assertThat(credit.getTransactions()).hasSize(2);
        assertThat(credit.getTransactions().stream().filter(PersonFlightTimeCreditTransaction::isCurrent))
                .as("exactly one current row after a consumption")
                .hasSize(1);
        PersonFlightTimeCreditTransaction current = credit.currentTransaction().orElseThrow();
        assertThat(current.currentFlightTimeBalanceInSeconds())
                .as("new balance = old 5400 - consumed 3600")
                .isEqualTo(1_800L);
        assertThat(current.getOldFlightTimeBalanceInSeconds()).isEqualTo(5_400L);
        assertThat(current.getFlightTimeBalanceInSeconds())
                .as("negated consumed delta")
                .isEqualTo(-3_600L);
        assertThat(current.getBalancedDeliveryId()).isEqualTo(deliveryId);
        assertThat(current.getBalanceDateTime()).isEqualTo(at);
    }

    @Test
    void consumption_overdraw_floorsBalanceAtZero() {
        PersonFlightTimeCredit credit = grantedCredit(600L);

        recordConsumption(credit, 5_400L, UUID.randomUUID(), Instant.parse("2026-06-25T10:00:00Z"));

        assertThat(credit.currentBalanceInSeconds()).isZero();
    }

    @Test
    void appendConsumption_zeroSeconds_isNoOp() {
        PersonFlightTimeCredit credit = grantedCredit(600L);

        credit.appendConsumption(0L, 600L, false, UUID.randomUUID(), Instant.EPOCH);

        assertThat(credit.getTransactions()).hasSize(1);
        assertThat(credit.currentBalanceInSeconds()).isEqualTo(600L);
    }

    private static PersonFlightTimeCredit grantedCredit(long balanceSeconds) {
        Person person = Person.register("Petra", "Pilot", null);
        return PersonFlightTimeCredit.grant(
                person, Instant.parse("2026-12-31T00:00:00Z"),
                false, "HB-X", 0, false, balanceSeconds);
    }

    private static void recordConsumption(PersonFlightTimeCredit credit, long seconds, UUID deliveryId, Instant at) {
        Long oldBalance = credit.currentBalanceInSeconds();
        boolean wasUnlimited = credit.currentTransaction().orElseThrow().isNoFlightTimeLimit();
        credit.releaseCurrent();
        credit.appendConsumption(seconds, oldBalance, wasUnlimited, deliveryId, at);
    }
}
