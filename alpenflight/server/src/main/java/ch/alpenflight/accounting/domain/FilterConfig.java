package ch.alpenflight.accounting.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record FilterConfig(
        boolean isRuleForGliderFlights,
        boolean isRuleForTowingFlights,
        boolean isRuleForMotorFlights,
        boolean noLandingTaxForGlider,
        boolean noLandingTaxForTowingAircraft,
        boolean noLandingTaxForAircraft,
        boolean includeFlightTypeName,
        boolean extendMatchingFlightTypeCodesToGliderAndTowFlight,
        boolean includeThresholdText,
        @Nullable String thresholdText,
        @Nullable Integer minFlightTimeInSecondsMatchingValue,
        @Nullable Integer maxFlightTimeInSecondsMatchingValue,
        @Nullable Integer minEngineTimeInSecondsMatchingValue,
        @Nullable Integer maxEngineTimeInSecondsMatchingValue,
        MatchList aircraftImmatriculations,
        MatchList startTypes,
        MatchList flightTypeCodes,
        MatchList startLocations,
        MatchList ldgLocations,
        MatchList clubMemberNumbers,
        MatchList flightCrewTypes,
        MatchList aircraftHomebases,
        MatchList memberStates,
        MatchList personCategories,
        @Nullable String deliveryLineText,
        @Nullable String recipientName) {

    public FilterConfig {
        aircraftImmatriculations = orEmpty(aircraftImmatriculations);
        startTypes = orEmpty(startTypes);
        flightTypeCodes = orEmpty(flightTypeCodes);
        startLocations = orEmpty(startLocations);
        ldgLocations = orEmpty(ldgLocations);
        clubMemberNumbers = orEmpty(clubMemberNumbers);
        flightCrewTypes = orEmpty(flightCrewTypes);
        aircraftHomebases = orEmpty(aircraftHomebases);
        memberStates = orEmpty(memberStates);
        personCategories = orEmpty(personCategories);
    }

    public static FilterConfig empty() {
        return new FilterConfig(
                false, false, false,
                false, false, false,
                false, false, false,
                null, null, null, null, null,
                MatchList.empty(), MatchList.empty(), MatchList.empty(),
                MatchList.empty(), MatchList.empty(), MatchList.empty(),
                MatchList.empty(), MatchList.empty(), MatchList.empty(),
                MatchList.empty(),
                null, null);
    }

    private static MatchList orEmpty(@Nullable MatchList value) {
        return value == null ? MatchList.empty() : value;
    }

    public long minFlightTimeSeconds() {
        Integer min = minFlightTimeInSecondsMatchingValue;
        return min == null ? 0L : min;
    }

    public long maxFlightTimeSeconds() {
        Integer max = maxFlightTimeInSecondsMatchingValue;
        return max == null ? Long.MAX_VALUE : max;
    }

    public record MatchList(boolean useAllExcept, List<String> matched) {

        public MatchList {
            matched = matched == null ? List.of() : List.copyOf(matched);
        }

        public static MatchList empty() {
            return new MatchList(true, List.of());
        }
    }
}
