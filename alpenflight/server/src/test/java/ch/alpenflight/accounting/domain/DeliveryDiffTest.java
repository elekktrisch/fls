package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Bit-exact unit tests for {@link DeliveryDiff} — the port of the legacy
 * {@code DeliveryService.RunTestDeliveryCreation} diff
 * ({@code flsserver/.../Accounting/DeliveryService.cs:549-766}). Each case pins a
 * message string + the success = AND-of-checks contract.
 */
class DeliveryDiffTest {

    private static final IgnoreFlags ALL_OFF = IgnoreFlags.none();
    private static final IgnoreFlags IGNORE_DELIVERY_LEVEL = new IgnoreFlags(
            true, true, true, true, true, true, false, false, false);

    private static DeliveryItemDetails item(int position, String article, String qty) {
        return new DeliveryItemDetails(position, article, "text", null, new BigDecimal(qty), 0, "Min");
    }

    private static DeliveryDetailsSnapshot snapshot(DeliveryItemDetails... items) {
        return new DeliveryDetailsSnapshot(List.of(items), null, null, null);
    }

    @Test
    void identicalDeliveries_succeed_withEmptyMessage() {
        DeliveryDetailsSnapshot expected = snapshot(item(1, "ART-FT", "90"), item(2, "ART-LT", "2"));
        DeliveryDetailsSnapshot created = snapshot(item(1, "ART-FT", "90"), item(2, "ART-LT", "2"));

        DeliveryDiff.Result result =
                DeliveryDiff.compare(expected, created, false, IGNORE_DELIVERY_LEVEL);

        assertThat(result.successful()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void quantityScaleDifference_isExactMatch_byCompareTo() {
        DeliveryDetailsSnapshot expected = snapshot(item(1, "ART-FT", "90"));
        DeliveryDetailsSnapshot created = snapshot(item(1, "ART-FT", "90.00"));

        DeliveryDiff.Result result =
                DeliveryDiff.compare(expected, created, false, IGNORE_DELIVERY_LEVEL);

        assertThat(result.successful()).isTrue();
    }

    @Test
    void quantityMismatch_fails_withDiffMessage_andKeepsComparingOtherFields() {
        DeliveryDetailsSnapshot expected = snapshot(item(1, "ART-FT", "90"));
        DeliveryDetailsSnapshot created = snapshot(item(1, "ART-FT", "91"));

        DeliveryDiff.Result result =
                DeliveryDiff.compare(expected, created, false, IGNORE_DELIVERY_LEVEL);

        assertThat(result.successful()).isFalse();
        assertThat(result.message())
                .isEqualTo("Expected Quantity 90 doesn't match with 91 for expected item at position 1");
    }

    @Test
    void mustNotCreate_succeedsWhenEmpty_failsWhenItemsPresent() {
        DeliveryDetailsSnapshot empty = snapshot();
        DeliveryDetailsSnapshot withItem = snapshot(item(1, "ART-FT", "90"));

        DeliveryDiff.Result ok = DeliveryDiff.compare(empty, empty, true, ALL_OFF);
        assertThat(ok.successful()).isTrue();
        assertThat(ok.message()).isEqualTo("Flug erzeugt kein Lieferschein");

        DeliveryDiff.Result bad = DeliveryDiff.compare(empty, withItem, true, ALL_OFF);
        assertThat(bad.successful()).isFalse();
        assertThat(bad.message()).isEqualTo("Flug erzeugt kein Lieferschein");
    }

    @Test
    void itemCountMismatch_andExtraCreatedItem_bothAccumulate() {
        DeliveryDetailsSnapshot expected = snapshot(item(1, "ART-FT", "90"));
        DeliveryDetailsSnapshot created = snapshot(item(1, "ART-FT", "90"), item(2, "ART-LT", "2"));

        DeliveryDiff.Result result =
                DeliveryDiff.compare(expected, created, false, IGNORE_DELIVERY_LEVEL);

        assertThat(result.successful()).isFalse();
        assertThat(result.message())
                .contains("Numbers of delivery items doesn't match")
                .contains("Item with ArticleNumber ART-LT not found in expected delivery items! "
                        + "Created item to much.");
    }

    @Test
    void recipientMismatch_failsWhenNotIgnored_passesWhenIgnored() {
        Recipient expectedRcpt = new Recipient(UUID.randomUUID(), "REC-1", "Petra Pilot", "Petra", "Pilot");
        Recipient createdRcpt = new Recipient(UUID.randomUUID(), "REC-2", "Hans Tow", "Hans", "Tow");
        DeliveryDetailsSnapshot expected =
                new DeliveryDetailsSnapshot(List.of(), expectedRcpt, null, null);
        DeliveryDetailsSnapshot created =
                new DeliveryDetailsSnapshot(List.of(), createdRcpt, null, null);
        IgnoreFlags ignoreInfoOnly = new IgnoreFlags(
                false, true, false, false, true, true, false, false, false);

        DeliveryDiff.Result fails = DeliveryDiff.compare(expected, created, false, ignoreInfoOnly);
        assertThat(fails.successful()).isFalse();
        assertThat(fails.message())
                .contains("PersonId doesn't match")
                .contains("PersonClubMemberNumber doesn't match")
                .contains("Lastname doesn't match")
                .contains("Firstname doesn't match");

        IgnoreFlags ignoreAllRecipient = new IgnoreFlags(
                true, true, true, true, true, true, false, false, false);
        DeliveryDiff.Result passes = DeliveryDiff.compare(expected, created, false, ignoreAllRecipient);
        assertThat(passes.successful()).isTrue();
        assertThat(passes.message()).isEmpty();
    }
}
