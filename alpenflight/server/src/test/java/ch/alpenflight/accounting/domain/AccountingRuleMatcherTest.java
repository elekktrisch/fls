package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.accounting.domain.FilterConfig.MatchList;
import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AccountingRuleMatcherTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();

    private static final List<MatchableCrew> ONE_PILOT_SO_THE_FLIGHT_IS_NEVER_CREWLESS =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));

    private static MatchableFlight gliderWithImmat(String immat) {
        return MatchableFlight.builder(FlightAircraftType.GLIDER)
                .immatriculation(immat)
                .crew(ONE_PILOT_SO_THE_FLIGHT_IS_NEVER_CREWLESS)
                .build();
    }

    private static FilterConfig gliderRuleWithImmatList(MatchList immats) {
        FilterConfig base = FilterConfig.empty();
        return new FilterConfig(
                true, false, false,
                base.noLandingTaxForGlider(), base.noLandingTaxForTowingAircraft(), base.noLandingTaxForAircraft(),
                base.includeFlightTypeName(), base.extendMatchingFlightTypeCodesToGliderAndTowFlight(),
                base.includeThresholdText(),
                base.thresholdText(),
                base.minFlightTimeInSecondsMatchingValue(), base.maxFlightTimeInSecondsMatchingValue(),
                base.minEngineTimeInSecondsMatchingValue(), base.maxEngineTimeInSecondsMatchingValue(),
                immats,
                base.startTypes(), base.flightTypeCodes(), base.startLocations(), base.ldgLocations(),
                base.clubMemberNumbers(), base.flightCrewTypes(), base.aircraftHomebases(),
                base.memberStates(), base.personCategories(),
                base.deliveryLineText(), base.recipientName());
    }

    @Nested
    class UseAllExceptMatrix {


        @Test
        void includeListNonEmptyMatchesOnlyListedValues() {
            FilterConfig include = gliderRuleWithImmatList(new MatchList(false, List.of("HB-AAA")));

            assertThat(MATCHER.matches(gliderWithImmat("HB-AAA"), include)).isTrue();
            assertThat(MATCHER.matches(gliderWithImmat("HB-BBB"), include)).isFalse();
        }

        @Test
        void excludeListNonEmptyMatchesEverythingButListedValues() {
            FilterConfig exclude = gliderRuleWithImmatList(new MatchList(true, List.of("HB-AAA")));

            assertThat(MATCHER.matches(gliderWithImmat("HB-AAA"), exclude)).isFalse();
            assertThat(MATCHER.matches(gliderWithImmat("HB-BBB"), exclude)).isTrue();
        }

        @Test
        void useAllExceptWithEmptyListImposesNoConditionAndMatchesAll() {
            FilterConfig all = gliderRuleWithImmatList(new MatchList(true, List.of()));

            assertThat(MATCHER.matches(gliderWithImmat("HB-AAA"), all)).isTrue();
            assertThat(MATCHER.matches(gliderWithImmat("HB-ZZZ"), all)).isTrue();
        }

        @Test
        void includeListEmptyNeverMatches() {
            FilterConfig none = gliderRuleWithImmatList(new MatchList(false, List.of()));

            assertThat(MATCHER.matches(gliderWithImmat("HB-AAA"), none)).isFalse();
        }

        @Test
        void immatComparisonIsCaseInsensitive() {
            FilterConfig include = gliderRuleWithImmatList(new MatchList(false, List.of("hb-aaa")));

            assertThat(MATCHER.matches(gliderWithImmat("HB-AAA"), include)).isTrue();
        }
    }

    @Nested
    class AircraftTypeBitmask {

        @Test
        void ruleAllowingTheFlightsTypeBitMatches() {
            FilterConfig towingRule = new FilterConfig(
                    false, true, false,
                    false, false, false, false, false, false,
                    null, null, null, null, null,
                    MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(),
                    MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(),
                    null, null);
            MatchableFlight tow = MatchableFlight.builder(FlightAircraftType.TOW).crew(ONE_PILOT_SO_THE_FLIGHT_IS_NEVER_CREWLESS).build();

            assertThat(MATCHER.matches(tow, towingRule)).isTrue();
        }

        @Test
        void ruleNotAllowingTheFlightsTypeBitNeverMatches() {
            FilterConfig gliderOnly = new FilterConfig(
                    true, false, false,
                    false, false, false, false, false, false,
                    null, null, null, null, null,
                    MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(),
                    MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(),
                    null, null);
            MatchableFlight motor = MatchableFlight.builder(FlightAircraftType.MOTOR).build();

            assertThat(MATCHER.matches(motor, gliderOnly)).isFalse();
        }
    }

    @Nested
    class ExtendFlightTypeCode {

        private FilterConfig flightTypeRule(boolean extend, MatchList flightTypeCodes) {
            return new FilterConfig(
                    true, true, true,
                    false, false, false, false, extend, false,
                    null, null, null, null, null,
                    MatchList.empty(), MatchList.empty(),
                    flightTypeCodes,
                    MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(),
                    MatchList.empty(), MatchList.empty(),
                    null, null);
        }

        @Test
        void withoutExtendOnlyOwnFlightCodeIsConsidered() {
            FilterConfig rule = flightTypeRule(false, new MatchList(false, List.of("TOWCODE")));
            MatchableFlight glider = MatchableFlight.builder(FlightAircraftType.GLIDER)
                    .flightTypeCode("GLIDERCODE")
                    .towFlightTypeCode("TOWCODE")
                    .crew(ONE_PILOT_SO_THE_FLIGHT_IS_NEVER_CREWLESS)
                    .build();

            assertThat(MATCHER.matches(glider, rule)).isFalse();
        }

        @Test
        void withExtendTheTowFlightCodeAlsoSatisfiesTheCondition() {
            FilterConfig rule = flightTypeRule(true, new MatchList(false, List.of("TOWCODE")));
            MatchableFlight glider = MatchableFlight.builder(FlightAircraftType.GLIDER)
                    .flightTypeCode("GLIDERCODE")
                    .towFlightTypeCode("TOWCODE")
                    .crew(ONE_PILOT_SO_THE_FLIGHT_IS_NEVER_CREWLESS)
                    .build();

            assertThat(MATCHER.matches(glider, rule)).isTrue();
        }

        @Test
        void withExtendATowedFlightCodeAlsoSatisfiesTheCondition() {
            FilterConfig rule = flightTypeRule(true, new MatchList(false, List.of("GLIDERCODE")));
            MatchableFlight tow = MatchableFlight.builder(FlightAircraftType.TOW)
                    .flightTypeCode("TOWCODE")
                    .towedFlightTypeCodes(List.of("GLIDERCODE"))
                    .crew(ONE_PILOT_SO_THE_FLIGHT_IS_NEVER_CREWLESS)
                    .build();

            assertThat(MATCHER.matches(tow, rule)).isTrue();
        }
    }

    @Nested
    class CrewConditions {

        private FilterConfig memberNumberRule(MatchList memberNumbers) {
            return new FilterConfig(
                    true, true, true,
                    false, false, false, false, false, false,
                    null, null, null, null, null,
                    MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(),
                    memberNumbers,
                    MatchList.empty(), MatchList.empty(), MatchList.empty(), MatchList.empty(),
                    null, null);
        }

        @Test
        void flightWithoutCrewNeverMatchesACrewScopedRule() {
            FilterConfig rule = memberNumberRule(new MatchList(false, List.of("1234")));
            MatchableFlight noCrew = MatchableFlight.builder(FlightAircraftType.GLIDER).build();

            assertThat(MATCHER.matches(noCrew, rule)).isFalse();
        }

        @Test
        void crewMemberNumberOnTheIncludeListMatches() {
            FilterConfig rule = memberNumberRule(new MatchList(false, List.of("1234")));
            MatchableFlight flight = MatchableFlight.builder(FlightAircraftType.GLIDER)
                    .crew(List.of(MatchableCrew.of("PILOT", "1234", null, List.of())))
                    .build();

            assertThat(MATCHER.matches(flight, rule)).isTrue();
        }

        @Test
        void crewPersonWithoutClubMembershipSurfacesAsException() {
            FilterConfig rule = memberNumberRule(new MatchList(false, List.of("1234")));
            MatchableFlight flight = MatchableFlight.builder(FlightAircraftType.GLIDER)
                    .crew(List.of(MatchableCrew.of("PILOT", null, null, List.of())))
                    .build();

            assertThatThrownBy(() -> MATCHER.matches(flight, rule))
                    .isInstanceOf(MissingPersonClubException.class);
        }
    }
}
