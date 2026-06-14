package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.FilterConfig.MatchList;
import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LandingTaxStageTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();
    private static final NoLandingTaxStage NO_LANDING_TAX = new NoLandingTaxStage(MATCHER);
    private static final LandingTaxStage LANDING_TAX = new LandingTaxStage(MATCHER);

    private static final List<MatchableCrew> ONE_PILOT =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));

    private static MatchableFlight.Builder glider() {
        return MatchableFlight.builder(FlightAircraftType.GLIDER)
                .immatriculation("HB-1234")
                .flightDurationSeconds(1200)
                .crew(ONE_PILOT);
    }

    private static RuleFilterInput landingTaxFilter(MatchList ldgLocations) {
        FilterConfig base = FilterConfig.empty();
        FilterConfig config = new FilterConfig(
                true, false, false,
                false, false, false, false, false, false,
                null, null, null, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), ldgLocations, base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                "Landetaxe", null);
        return new RuleFilterInput(
                UUID.randomUUID(), null, "LDG", AccountingUnitType.LDGS, config);
    }

    private static RuleFilterInput noLandingTaxForGliderFilter() {
        FilterConfig base = FilterConfig.empty();
        FilterConfig config = new FilterConfig(
                true, false, false,
                true, false, false, false, false, false,
                null, null, null, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                null, null);
        return RuleFilterInput.of(UUID.randomUUID(), null, config);
    }

    // Suppression interplay: a matching NoLandingTax(20) filter with
    // NoLandingTaxForGlider sets the accumulator's glider flag, which then forces
    // the LandingTax(60) line off for a glider flight -> no item emitted.
    @Test
    void noLandingTaxForGliderSuppressesLandingTaxForGliderFlight() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        MatchableFlight flight = glider().build();

        NO_LANDING_TAX.run(acc, flight, List.of(noLandingTaxForGliderFilter()));
        LANDING_TAX.run(acc, flight, List.of(landingTaxFilter(MatchList.empty())));

        assertThat(acc.isNoLandingTaxForGlider()).isTrue();
        assertThat(acc.deliveryItems()).isEmpty();
    }

    // LandingTaxOnStartLocation matches the flight's START location (LSZK) against
    // the filter's ldg-location set even though the flight LANDED elsewhere (LSZF),
    // and bills nrOfLdgsOnStartLocation (3), not nrOfLdgs; and is forced off when
    // there are no landings on the start location.
    @Test
    void onStartLocationBillsStartLocationLandingsAndIsForcedOffWhenNone() {
        // Include-list on the START location: a plain LandingTax would not match
        // (flight landed at LSZF), only the start-substituted pass does.
        RuleFilterInput filter = landingTaxFilter(new MatchList(false, List.of("LSZK")));

        var billed = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        LANDING_TAX.runOnStartLocation(billed, glider()
                .startLocationIcao("LSZK")
                .ldgLocationIcao("LSZF")
                .nrOfLdgs(5)
                .nrOfLdgsOnStartLocation(3)
                .build(), List.of(filter));

        assertThat(billed.deliveryItems()).hasSize(1);
        assertThat(billed.deliveryItems().get(0).quantity())
                .isEqualByComparingTo(BigDecimal.valueOf(3));

        var forcedOff = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        LANDING_TAX.runOnStartLocation(forcedOff, glider()
                .startLocationIcao("LSZK")
                .nrOfLdgsOnStartLocation(0)
                .build(), List.of(filter));

        assertThat(forcedOff.deliveryItems()).isEmpty();
    }
}
