package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryCreationTestDomainTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-06-14T10:00:00Z"), ZoneOffset.UTC);

    private static final UUID CLUB = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID FLIGHT = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Test
    void create_blankName_rejects() {
        assertThatThrownBy(() -> DeliveryCreationTest.create(
                CLUB, FLIGHT, "  ", null, true, false, IgnoreFlags.none()))
                .isInstanceOf(InvalidDeliveryCreationTestException.class)
                .hasMessageContaining("testName");
    }

    @Test
    void create_nullFlight_rejects() {
        assertThatThrownBy(() -> DeliveryCreationTest.create(
                CLUB, null, "Tow harness", null, true, false, IgnoreFlags.none()))
                .isInstanceOf(InvalidDeliveryCreationTestException.class)
                .hasMessageContaining("flightId");
    }

    @Test
    void captureExpected_thenRecordRun_areDomainOwnedTransitions() {
        DeliveryCreationTest test = DeliveryCreationTest.create(
                CLUB, FLIGHT, "  Tiered FlightTime  ", "  desc  ", true, false,
                new IgnoreFlags(true, false, false, false, false, false, false, false, true));

        assertThat(test.getTestName()).isEqualTo("Tiered FlightTime");
        assertThat(test.getDescription()).isEqualTo("desc");
        assertThat(test.getIgnoreFlags().recipientName()).isTrue();
        assertThat(test.getIgnoreFlags().itemAdditionalInformation()).isTrue();
        assertThat(test.getIgnoreFlags().recipientAddress()).isFalse();
        assertThat(test.getExpectedDelivery()).isEqualTo(DeliveryDetailsSnapshot.empty());
        assertThat(test.getItems()).isEmpty();

        DeliveryItemDetails line = new DeliveryItemDetails(
                1, "ART-30", "first 30min", null, new BigDecimal("0.5000"), 0, "Min");
        DeliveryDetailsSnapshot expected = new DeliveryDetailsSnapshot(
                List.of(line), null, "delivery info", null);
        UUID matched = UUID.fromString("00000000-0000-0000-0000-00000000a001");
        test.captureExpected(expected, List.of(matched));

        assertThat(test.getExpectedDelivery()).isEqualTo(expected);
        assertThat(test.getExpectedMatchedFilterIds()).containsExactly(matched);
        assertThat(test.getItems())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getArticleNumber()).isEqualTo("ART-30");
                    assertThat(item.getQuantity()).isEqualByComparingTo("0.5000");
                    assertThat(item.getUnitTypeCode()).isEqualTo("Min");
                    assertThat(item.getOperatingClubId()).isEqualTo(CLUB);
                });

        Instant runOn = Instant.now(FIXED_CLOCK);
        DeliveryDetailsSnapshot created = new DeliveryDetailsSnapshot(List.of(), null, null, null);
        test.recordRun(false, "items differ at position 1", created, List.of(matched), runOn);

        assertThat(test.getLastTestSuccessful()).isFalse();
        assertThat(test.getLastTestResultMessage()).isEqualTo("items differ at position 1");
        assertThat(test.getLastTestCreatedDelivery()).isEqualTo(created);
        assertThat(test.getLastTestMatchedFilterIds()).containsExactly(matched);
        assertThat(test.getLastTestRunOn()).isEqualTo(runOn);
    }

    @Test
    void update_blankName_rejectsWithoutMutating() {
        DeliveryCreationTest test = DeliveryCreationTest.create(
                CLUB, FLIGHT, "Keep me", null, true, false, IgnoreFlags.none());

        assertThatThrownBy(() -> test.update(
                FLIGHT, "   ", null, false, true, IgnoreFlags.none()))
                .isInstanceOf(InvalidDeliveryCreationTestException.class);

        assertThat(test.getTestName()).isEqualTo("Keep me");
        assertThat(test.isActive()).isTrue();
        assertThat(test.isMustNotCreateDeliveryForFlight()).isFalse();
    }

    @Test
    void softDelete_idempotent() {
        DeliveryCreationTest test = DeliveryCreationTest.create(
                CLUB, FLIGHT, "Doomed", null, true, false, IgnoreFlags.none());
        UUID actor = UUID.randomUUID();
        test.softDelete(actor, FIXED_CLOCK);
        assertThat(test.isDeleted()).isTrue();
        test.softDelete(actor, FIXED_CLOCK);
        assertThat(test.isDeleted()).isTrue();
    }
}
