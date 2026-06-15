package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.DeliveryItemPipeline.RuleFilters;
import ch.alpenflight.accounting.domain.DeliveryItemPipeline.TowInput;
import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
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

    // Each emitting filter is scoped to glider flights (the aircraft-kind facet is
    // the one condition an "otherwise matches everything" filter must still set —
    // an all-false-kind config matches no flight) and carries its own article so
    // the lines stay distinct + their order is visible.
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

        // One matching filter of every emitting type (min=0 windows bill the whole
        // remainder in one pass so each loop emits exactly one line).
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

        // The tow shares the SAME filter buckets — its TOW-kind filters carry their
        // own articles so they don't coalesce with the glider's lines; only the
        // glider matches the GLIDER-kind FlightTime/Instructor filters and only the
        // tow matches the TOW-kind ones.
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

        // glider FlightTime + glider Instructor, THEN the entire tow pipeline
        // (tow FlightTime + tow Instructor), THEN the glider's Fuel/StartTax/Landing
        // — the tow lines slot in BEFORE the glider's remaining lines, continuing the
        // shared accumulator's position numbering.
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

        // The recursive call passes tow=null, so a TOW-kind filter that matches the
        // tow flight emits its line exactly ONCE — the tow is never re-recursed into
        // (the legacy "the tow has no tow" guarantee, here structural not data-driven).
        RuleFilters filters = buckets(
                List.of(towKind("FT_T")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        PIPELINE.run(acc, glider().build(), filters, 0, 0,
                new TowInput(tow().build(), 600, 0));

        assertThat(acc.deliveryItems())
                .extracting(DeliveryItemDetails::articleNumber)
                .containsExactly("FT_T");
    }

    // A FlightTime/Instructor filter scoped to GLIDER flights only.
    private static RuleFilterInput gliderKind(String article) {
        return new RuleFilterInput(
                UUID.randomUUID(), null, article, AccountingUnitType.MIN,
                kindScoped(true, false));
    }

    // A FlightTime/Instructor filter scoped to TOWING flights only.
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
