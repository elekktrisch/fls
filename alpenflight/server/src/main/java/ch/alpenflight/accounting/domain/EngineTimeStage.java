package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The EngineTime decrement loop — the engine-counter sibling of
 * {@link FlightTimeStage}, billing the same tiered way against engine running
 * time instead of flight time. Ported line-by-line (NOT rewritten from
 * understanding; customer invoices depend on the current behavior) from:
 *
 * <ul>
 *   <li>the loop in
 *       {@code flsserver/src/FLS.Server.Service/Accounting/RuleEngines/DeliveryItemRulesEngine.cs:83-97}</li>
 *   <li>the per-filter predicate + emit in
 *       {@code flsserver/src/FLS.Server.Service/Accounting/Rules/ItemRules/AircraftEngineTimeRule.cs}
 *       ({@code Initialize}:25-32 + {@code Apply}:34-97)</li>
 *   <li>the per-pass apply machinery in
 *       {@code flsserver/src/FLS.Server.Service/RulesEngine/RulesEngine.cs}
 *       (each pass re-clears + re-initialises every rule, so a filter's match is
 *       re-evaluated against the CURRENT remaining active time)</li>
 * </ul>
 *
 * <p>Unlike {@code AircraftFlightTimeRule}, {@code AircraftEngineTimeRule} has NO
 * {@code PersonFlightTimeCredit} / discount branch at all — the engine-time path
 * is pure decrement upstream too, so there is nothing credit-related to defer
 * here.
 */
public final class EngineTimeStage {

    private final AccountingRuleMatcher matcher;

    public EngineTimeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    /**
     * Runs the decrement loop over the active type-80 (EngineTime) filters, in
     * {@code sort_indicator, id} order, seeding the accumulator's active engine
     * time from {@code engineRunningTimeSeconds}. Legacy seeds it just before the
     * loop from {@code EngineEndOperatingCounterInSeconds.GetValueOrDefault() -
     * EngineStartOperatingCounterInSeconds.GetValueOrDefault()}, so a NULL counter
     * yields 0 and the loop never runs (no item); the orchestrator (T-12) computes
     * that delta and passes it here. Each matching tier emits one
     * {@link DeliveryItemDetails} and decrements the remaining active time; the
     * loop ends when a full pass applies no rule.
     */
    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    int engineRunningTimeSeconds,
                    List<RuleFilterInput> engineTimeFilters) {
        accumulator.setActiveEngineTimeInSeconds(engineRunningTimeSeconds);

        while (accumulator.getActiveEngineTimeInSeconds() > 0) {
            boolean anyApplied = false;
            for (RuleFilterInput filter : engineTimeFilters) {
                if (applies(accumulator, flight, filter)) {
                    apply(accumulator, flight, filter);
                    anyApplied = true;
                }
            }
            // Tier gap: no filter covers the remaining time -> stop. The legacy
            // warns and breaks, leaving the remainder silently unbilled (a known
            // quirk, reproduced bit-exact — never "fixed", customers depend on it).
            if (!anyApplied) {
                break;
            }
        }
    }

    // The per-filter predicate: the base conditions (T-06) AND the tier window.
    // The window is min-EXCLUSIVE / max-INCLUSIVE (legacy Between includeMin:false,
    // includeMax:true) — the asymmetry that lets adjacent tiers (…, m] and (m, …]
    // partition a duration with no double-billing at the boundary.
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
        // Legacy AircraftEngineTimeRule.Initialize asserts ArticleTarget.NotNull;
        // a line-emitting filter must carry both an article and a unit.
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
