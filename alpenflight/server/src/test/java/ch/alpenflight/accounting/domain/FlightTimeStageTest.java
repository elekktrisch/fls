package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.persons.domain.Person;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FlightTimeStageTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();
    private static final FlightTimeStage STAGE = new FlightTimeStage(MATCHER);

    private static final List<MatchableCrew> ONE_PILOT =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));
    private static final String IMMAT = "HB-1234";

    private static MatchableFlight glider() {
        return MatchableFlight.builder(FlightAircraftType.GLIDER)
                .immatriculation(IMMAT)
                .flightTypeName("Schulung")
                .crew(ONE_PILOT)
                .build();
    }

    private static RuleFilterInput tier(
            String article, @Nullable Integer min, @Nullable Integer max) {
        return tier(article, min, max, AccountingUnitType.MIN);
    }

    private static RuleFilterInput tier(
            String article, @Nullable Integer min, @Nullable Integer max, AccountingUnitType unit) {
        FilterConfig base = FilterConfig.empty();
        FilterConfig config = new FilterConfig(
                true, false, false,
                false, false, false, false, false, false,
                null, min, max, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                "Flugzeit", null);
        return new RuleFilterInput(UUID.randomUUID(), null, article, unit, config);
    }

    private static PersonFlightTimeCredit credit(boolean useRuleForAllExceptListed,
                                                 @Nullable String matchedImmats,
                                                 int discountInPercent,
                                                 boolean unlimited,
                                                 @Nullable Long balanceInSeconds) {
        return PersonFlightTimeCredit.grant(
                Person.register("Test", "Pilot", null),
                Instant.EPOCH,
                useRuleForAllExceptListed,
                matchedImmats,
                discountInPercent,
                unlimited,
                balanceInSeconds);
    }

    @Test
    void tieredBillingEmitsOneItemPerTierInApplyOrder() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), 1500, List.of(
                tier("UPPER", 600, null),
                tier("LOWER", 0, 600)));

        List<DeliveryItemDetails> items = acc.deliveryItems();
        assertThat(items).hasSize(2);

        assertThat(items.get(0).articleNumber()).isEqualTo("UPPER");
        assertThat(items.get(0).quantity()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(items.get(0).unitType()).isEqualTo("Minuten");

        assertThat(items.get(1).articleNumber()).isEqualTo("LOWER");
        assertThat(items.get(1).quantity()).isEqualByComparingTo(new BigDecimal("10"));

        assertThat(acc.getActiveFlightTimeInSeconds()).isZero();
    }

    @Test
    void zeroFlightTimeEmitsNoItems() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), 0, List.of(tier("ANY", 0, null)));

        assertThat(acc.deliveryItems()).isEmpty();
    }

    @Test
    void tierGapLeavesRemainderSilentlyUnbilled() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), 1500, List.of(tier("MID", 600, 1200)));

        assertThat(acc.deliveryItems()).isEmpty();
        assertThat(acc.getActiveFlightTimeInSeconds()).isEqualTo(1500);
    }

    private static List<RuleFilterInput> oneSecTier() {
        return List.of(tier("FT", 0, null, AccountingUnitType.SEC));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", value = {
            "fullyCovered,  false, 25, false, 5000, 1000, 25, 0,    0",
            "overCredit,    false, 30, false,  600,  600, 30, 400,  0",
            "unlimited,     false, 50, true,   null, 1000, 50, 0,   0",
            "zeroBalance,   false, 40, false,    0, 1000,  0, 0,    0",
            "noDiscountLeg, false,  0, false, 5000, 1000,  0, 0,    0",
    })
    void creditBranchSplitsAndStampsTheDiscount(
            String name,
            boolean exceptListed, int discount, boolean unlimited, @Nullable Long balance,
            int firstQty, int firstDiscount, int remainderQty, int remainderDiscount) {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), 1000, oneSecTier(),
                List.of(credit(exceptListed, IMMAT, discount, unlimited, balance)));

        var items = acc.deliveryItems();
        assertThat(items.getFirst().quantity()).isEqualByComparingTo(BigDecimal.valueOf(firstQty));
        assertThat(items.getFirst().discountInPercent()).isEqualTo(firstDiscount);
        if (remainderQty > 0) {
            assertThat(items).hasSize(2);
            assertThat(items.get(1).quantity()).isEqualByComparingTo(BigDecimal.valueOf(remainderQty));
            assertThat(items.get(1).discountInPercent()).isEqualTo(remainderDiscount);
            assertThat(items.get(1).articleNumber()).isEqualTo(items.getFirst().articleNumber());
            assertThat(items.get(1).itemText()).isEqualTo(items.getFirst().itemText());
        } else {
            assertThat(items).hasSize(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "minTierGenuineOverCredit, 200, 200, 25, 100, 0",
            "minTierBoundaryNoSplit,   400, 300, 25,   0, 0",
    })
    void minTierSplitsOnBilledSliceNotFullActive(
            String name,
            long balance, int firstQty, int firstDiscount, int remainderQty, int remainderDiscount) {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), 900, List.of(tier("FT", 600, null, AccountingUnitType.SEC)),
                List.of(credit(false, IMMAT, firstDiscount, false, balance)));

        var items = acc.deliveryItems();
        assertThat(items.getFirst().quantity()).isEqualByComparingTo(BigDecimal.valueOf(firstQty));
        assertThat(items.getFirst().discountInPercent()).isEqualTo(firstDiscount);
        if (remainderQty > 0) {
            assertThat(items).hasSize(2);
            assertThat(items.get(1).quantity()).isEqualByComparingTo(BigDecimal.valueOf(remainderQty));
            assertThat(items.get(1).discountInPercent()).isEqualTo(remainderDiscount);
        } else {
            assertThat(items).hasSize(1);
        }
    }

    @ParameterizedTest(name = "exceptListed={0} matchedImmats={1} -> applies={2}")
    @CsvSource(nullValues = "null", value = {
            "false, 'HB-1234,HB-9', true",
            "false, 'HB-9,HB-8',    false",
            "false, null,           false",
            "true,  'HB-9,HB-8',    true",
            "true,  'HB-1234,HB-9', false",
            "true,  null,           false",
    })
    void immatriculationActivationHonoursSubstringMatchAndInversion(
            boolean exceptListed, @Nullable String matchedImmats, boolean applies) {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(acc, glider(), 1000, oneSecTier(),
                List.of(credit(exceptListed, matchedImmats, 20, false, 5_000L)));

        assertThat(acc.deliveryItems().getFirst().discountInPercent())
                .isEqualTo(applies ? 20 : 0);
    }

    @ParameterizedTest(name = "{0} -> discount={1}")
    @CsvSource({
            "firstUsableWins,    10",
            "zeroBalanceSkipped, 35",
            "noCredits,           0",
    })
    void firstUsableCreditWins(String scenario, int expectedDiscount) {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        List<PersonFlightTimeCredit> credits = switch (scenario) {
            case "firstUsableWins" -> List.of(
                    credit(false, IMMAT, 10, false, 5_000L),
                    credit(false, IMMAT, 99, false, 5_000L));
            case "zeroBalanceSkipped" -> List.of(
                    credit(false, IMMAT, 10, false, 0L),
                    credit(false, IMMAT, 35, false, 5_000L));
            default -> List.of();
        };

        STAGE.run(acc, glider(), 1000, oneSecTier(), credits);

        assertThat(acc.deliveryItems()).hasSize(1);
        assertThat(acc.deliveryItems().getFirst().discountInPercent()).isEqualTo(expectedDiscount);
    }
}
