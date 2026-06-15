package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleBasedDeliveryDetailsTest {

    private static DeliveryItemDetails item(String articleNumber, String quantity) {
        return new DeliveryItemDetails(
                0, articleNumber, "text", null, new BigDecimal(quantity), 0, "Sekunden");
    }

    @Test
    void addItemAppendsAndAssignsSequentialPositions() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        var first = acc.addItem(item("A100", "60"));
        var second = acc.addItem(item("A200", "30"));

        assertThat(first.position()).isEqualTo(1);
        assertThat(second.position()).isEqualTo(2);
        assertThat(acc.deliveryItems()).extracting(DeliveryItemDetails::articleNumber).containsExactly("A100", "A200");
    }

    @Test
    void addItemWithDuplicateArticleAddsQuantityToExistingLine() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        acc.addItem(item("A100", "60"));

        var result = acc.addItem(item("A100", "30"));

        assertThat(acc.deliveryItems()).hasSize(1);
        assertThat(result.quantity()).isEqualByComparingTo("90");
        assertThat(result.position()).isEqualTo(1);
    }

    @Test
    void decrementActiveFlightTimeAndFlagsAreMutable() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        acc.setActiveFlightTimeInSeconds(3600);

        acc.setActiveFlightTimeInSeconds(1800);
        acc.setNoLandingTaxForFlight(true);
        var filterId = UUID.randomUUID();
        acc.markFilterMatched(filterId);

        assertThat(acc.getActiveFlightTimeInSeconds()).isEqualTo(1800);
        assertThat(acc.isNoLandingTaxForFlight()).isTrue();
        assertThat(acc.getMatchedFilterIds()).containsExactly(filterId);
    }
}
