package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class LandingTaxStage {

    private final AccountingRuleMatcher matcher;

    public LandingTaxStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> landingTaxFilters) {
        for (RuleFilterInput filter : landingTaxFilters) {
            if (suppressed(accumulator, flight)) {
                continue;
            }
            boolean inWindow = flight.noStartTimeInformation() || flight.noLdgTimeInformation()
                    || durationInWindow(flight.flightDurationSeconds(), filter.filterConfig());
            if (!inWindow || !matcher.matches(flight, filter.filterConfig())) {
                continue;
            }
            emit(accumulator, filter, quantityOrDefault(flight.nrOfLdgs()));
        }
    }

    public void runOnStartLocation(RuleBasedDeliveryDetails accumulator,
                                   MatchableFlight flight,
                                   List<RuleFilterInput> landingTaxFilters) {
        if (defaultZero(flight.nrOfLdgsOnStartLocation()) <= 0) {
            return;
        }
        MatchableFlight onStart = flight.withLdgLocationIcao(flight.startLocationIcao());
        for (RuleFilterInput filter : landingTaxFilters) {
            if (suppressed(accumulator, flight)) {
                continue;
            }
            if (!durationInWindow(flight.flightDurationSeconds(), filter.filterConfig())
                    || !matcher.matches(onStart, filter.filterConfig())) {
                continue;
            }
            emit(accumulator, filter, quantityOrDefault(flight.nrOfLdgsOnStartLocation()));
        }
    }

    private static boolean suppressed(RuleBasedDeliveryDetails accumulator, MatchableFlight flight) {
        return (accumulator.isNoLandingTaxForGlider() && flight.isGlider())
                || (accumulator.isNoLandingTaxForTowFlight() && flight.isTow());
    }

    static void emit(RuleBasedDeliveryDetails accumulator,
                     RuleFilterInput filter,
                     int quantity) {
        String articleNumber = Objects.requireNonNull(filter.articleNumber(),
                "Count-quantity filter must carry an articleNumber");
        AccountingUnitType unit = Objects.requireNonNull(filter.accountingUnitType(),
                "Count-quantity filter must carry an accountingUnitType");
        accumulator.addItem(new DeliveryItemDetails(
                0,
                articleNumber,
                Objects.toString(filter.filterConfig().deliveryLineText(), ""),
                null,
                BigDecimal.valueOf(quantity),
                0,
                unit.unitTypeString()));
        accumulator.markFilterMatched(filter.filterId());
    }

    static int quantityOrDefault(@Nullable Integer count) {
        return count == null ? 1 : count;
    }

    static int defaultZero(@Nullable Integer count) {
        return count == null ? 0 : count;
    }

    static boolean durationInWindow(int durationSeconds, FilterConfig config) {
        return durationSeconds > config.minFlightTimeSeconds()
                && durationSeconds <= config.maxFlightTimeSeconds();
    }
}
