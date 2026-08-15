package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public final class EngineTimeStage {

    private final AccountingRuleMatcher matcher;

    public EngineTimeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    int engineRunningTimeSeconds,
                    List<RuleFilterInput> engineTimeFilters) {
        accumulator.setActiveEngineTimeInSeconds(engineRunningTimeSeconds);

        while (accumulator.getActiveEngineTimeInSeconds() > 0) {
            boolean anyTierCoveredRemainingTime = false;
            for (RuleFilterInput filter : engineTimeFilters) {
                if (applies(accumulator, flight, filter)) {
                    apply(accumulator, flight, filter);
                    anyTierCoveredRemainingTime = true;
                }
            }
            if (!anyTierCoveredRemainingTime) {
                break;
            }
        }
    }

    private boolean applies(RuleBasedDeliveryDetails accumulator,
                            MatchableFlight flight,
                            RuleFilterInput filter) {
        if (!matcher.matches(flight, filter.filterConfig())) {
            return false;
        }
        long active = accumulator.getActiveEngineTimeInSeconds();
        long min = minSeconds(filter.filterConfig());
        long max = maxSeconds(filter.filterConfig());
        return active > min && active <= max;
    }

    private void apply(RuleBasedDeliveryDetails accumulator,
                       MatchableFlight flight,
                       RuleFilterInput filter) {
        String articleNumber = Objects.requireNonNull(filter.articleNumber(),
                "EngineTime filter must carry an articleNumber");
        AccountingUnitType unit = Objects.requireNonNull(filter.accountingUnitType(),
                "EngineTime filter must carry an accountingUnitType");

        int active = accumulator.getActiveEngineTimeInSeconds();
        long min = minSeconds(filter.filterConfig());

        int lineSeconds;
        if (min == 0) {
            lineSeconds = active;
            accumulator.setActiveEngineTimeInSeconds(0);
        } else {
            lineSeconds = (int) (active - min);
            accumulator.setActiveEngineTimeInSeconds((int) min);
        }

        BigDecimal quantity =
                unit.quantityFrom(BigDecimal.valueOf(lineSeconds), AccountingUnitType.SEC);

        accumulator.addItem(new DeliveryItemDetails(
                0,
                articleNumber,
                itemText(flight, filter.filterConfig()),
                null,
                quantity,
                0,
                unit.unitTypeString()));
        accumulator.markFilterMatched(filter.filterId());
    }

    private String itemText(MatchableFlight flight, FilterConfig config) {
        StringBuilder text = new StringBuilder()
                .append(flight.immatriculation())
                .append(' ')
                .append(config.deliveryLineText());
        if (config.includeFlightTypeName()) {
            text.append(' ').append(flight.flightTypeName());
        }
        if (config.includeThresholdText()) {
            text.append(' ').append(config.thresholdText());
        }
        return text.toString();
    }

    private static long minSeconds(FilterConfig config) {
        Integer min = config.minEngineTimeInSecondsMatchingValue();
        return min == null ? 0L : min;
    }

    private static long maxSeconds(FilterConfig config) {
        Integer max = config.maxEngineTimeInSecondsMatchingValue();
        return max == null ? Long.MAX_VALUE : max;
    }
}
