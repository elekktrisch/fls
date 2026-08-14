package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.DeliveryItemPipeline.RuleFilters;
import ch.alpenflight.accounting.domain.DeliveryItemPipeline.TowInput;
import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.persons.domain.Person;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryItemPipelineTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();
    private static final DeliveryItemPipeline PIPELINE = new DeliveryItemPipeline(MATCHER);

    private static final List<MatchableCrew> ONE_PILOT =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));

    private static MatchableFlight.Builder glider() {
        return MatchableFlight.builder(FlightAircraftType.GLIDER)
                .immatriculation("HB-GLDR")
                .flightDurationSeconds(1800)
                .nrOfLdgs(1)
                .crew(ONE_PILOT);
    }

    private static MatchableFlight.Builder tow() {
        return MatchableFlight.builder(FlightAircraftType.TOW)
                .immatriculation("HB-TOWA")
                .flightDurationSeconds(600)
                .nrOfLdgs(1)
                .crew(ONE_PILOT);
    }

    private static RuleFilterInput lineFilter(String article, AccountingUnitType unit) {
        return new RuleFilterInput(UUID.randomUUID(), null, article, unit, kindScoped(true, false));
    }

    private static RuleFilters buckets(
            List<RuleFilterInput> flightTime,
            List<RuleFilterInput> engineTime,
            List<RuleFilterInput> instructorFee,
            List<RuleFilterInput> additionalFuelFee,
            List<RuleFilterInput> startTax,
            List<RuleFilterInput> landingTax,
            List<RuleFilterInput> vsfFee) {
        return new RuleFilters(
                List.of(), flightTime, engineTime, instructorFee,
                additionalFuelFee, startTax, landingTax, vsfFee);
    }

    private static RuleFilters allOneEach() {
        return buckets(
                List.of(lineFilter("FT", AccountingUnitType.MIN)),
                List.of(lineFilter("ET", AccountingUnitType.MIN)),
                List.of(lineFilter("INSTR", AccountingUnitType.MIN)),
                List.of(lineFilter("FUEL", AccountingUnitType.MIN)),
                List.of(lineFilter("START", AccountingUnitType.START_OR_FLIGHT)),
                List.of(lineFilter("LDG", AccountingUnitType.LDGS)),
                List.of(lineFilter("VSF", AccountingUnitType.LDGS)));
    }

    @Test
    void emitsLinesInLegacyStageOrder() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        PIPELINE.run(acc, glider().build(), allOneEach(), 1800, 1200, null);

        assertThat(acc.deliveryItems())
                .extracting(DeliveryItemDetails::articleNumber)
                .containsExactly("FT", "ET", "INSTR", "FUEL", "START", "LDG", "VSF");
        assertThat(acc.deliveryItems())
                .extracting(DeliveryItemDetails::position)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void towLinesAppendBeforeGliderFuelStartLandingVsfSharingTheAccumulator() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        RuleFilters filters = new RuleFilters(
                List.of(),
                List.of(gliderKind("FT_G"), towKind("FT_T")),
                List.of(),
                List.of(gliderKind("INSTR_G"), towKind("INSTR_T")),
                List.of(gliderKind("FUEL_G")),
                List.of(gliderKind("START_G")),
                List.of(gliderKind("LDG_G")),
                List.of());

        PIPELINE.run(acc, glider().instructorDisplayName("Hans").build(), filters, 1800, 0,
                new TowInput(tow().instructorDisplayName("Fritz").build(), 600, 0));

        assertThat(acc.deliveryItems())
                .extracting(DeliveryItemDetails::articleNumber)
                .containsExactly("FT_G", "INSTR_G", "FT_T", "INSTR_T", "FUEL_G", "START_G", "LDG_G");
        assertThat(acc.deliveryItems())
                .extracting(DeliveryItemDetails::position)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void towRecursionIsOneLevelOnly() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        RuleFilters filters = buckets(
                List.of(towKind("FT_T")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        PIPELINE.run(acc, glider().build(), filters, 0, 0,
                new TowInput(tow().build(), 600, 0));

        assertThat(acc.deliveryItems())
                .extracting(DeliveryItemDetails::articleNumber)
                .containsExactly("FT_T");
    }

    @Test
    void gliderAndTowSharingOneCreditSumBothPassesOntoIt() throws Exception {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        UUID creditId = UUID.randomUUID();
        PersonFlightTimeCredit shared = creditMatching("HB-GLDR,HB-TOWA", 10_000L, creditId);

        RuleFilters filters = buckets(
                List.of(creditTier("FT_G", true, false), creditTier("FT_T", false, true)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        PIPELINE.run(acc, glider().build(), filters, 1800, 0,
                new TowInput(tow().build(), 600, 0), List.of(shared));

        assertThat(acc.creditConsumptions())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.creditId()).isEqualTo(creditId);
                    assertThat(c.consumedSeconds()).isEqualTo(2400L);
                });
    }

    private static RuleFilterInput creditTier(String article, boolean glider, boolean towing) {
        FilterConfig base = FilterConfig.empty();
        FilterConfig config = new FilterConfig(
                glider, towing, false,
                false, false, false, false, false, false,
                null, 0, null, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                null, null);
        return new RuleFilterInput(UUID.randomUUID(), null, article, AccountingUnitType.SEC, config);
    }

    private static PersonFlightTimeCredit creditMatching(String matchedImmats, long balanceSeconds, UUID id)
            throws Exception {
        PersonFlightTimeCredit credit = PersonFlightTimeCredit.grant(
                Person.register("Test", "Pilot", null),
                Instant.EPOCH, false, matchedImmats, 0, false, balanceSeconds);
        Field idField = PersonFlightTimeCredit.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(credit, id);
        return credit;
    }

    private static RuleFilterInput gliderKind(String article) {
        return new RuleFilterInput(
                UUID.randomUUID(), null, article, AccountingUnitType.MIN,
                kindScoped(true, false));
    }

    private static RuleFilterInput towKind(String article) {
        return new RuleFilterInput(
                UUID.randomUUID(), null, article, AccountingUnitType.MIN,
                kindScoped(false, true));
    }

    private static FilterConfig kindScoped(boolean glider, boolean towing) {
        FilterConfig base = FilterConfig.empty();
        return new FilterConfig(
                glider, towing, false,
                false, false, false, false, false, false,
                null, null, null, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                null, null);
    }
}
