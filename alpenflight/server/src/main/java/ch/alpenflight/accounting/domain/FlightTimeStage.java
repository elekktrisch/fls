package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The FlightTime decrement loop — the R3 tiered-billing mechanism, the
 * highest-risk parity in the rewrite. Ported line-by-line (NOT rewritten from
 * understanding; customer invoices depend on the current behavior) from:
 *
 * <ul>
 *   <li>the loop in
 *       {@code flsserver/src/FLS.Server.Service/Accounting/RuleEngines/DeliveryItemRulesEngine.cs:59-71}</li>
 *   <li>the per-filter predicate + emit in
 *       {@code flsserver/src/FLS.Server.Service/Accounting/Rules/ItemRules/AircraftFlightTimeRule.cs}
 *       ({@code Initialize}:30 + the pure-decrement path of {@code Apply}:33-47, 97-138)</li>
 *   <li>the per-pass apply machinery in
 *       {@code flsserver/src/FLS.Server.Service/RulesEngine/RulesEngine.cs}
 *       (each pass re-clears + re-initialises every rule, so a filter's match is
 *       re-evaluated against the CURRENT remaining active time)</li>
 * </ul>
 *
 * <p>The {@code PersonFlightTimeCredit} / discount / over-credit-two-line-split /
 * transaction branch ({@code AircraftFlightTimeRule.Apply}:49-184) is the deferred
 * J-9b credit sub-engine and is deliberately NOT ported — this stage is the pure
 * decrement path (no credits seeded / assumed).
 */
public final class FlightTimeStage {

    private final AccountingRuleMatcher matcher;

    public FlightTimeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    /**
     * Runs the decrement loop over the active type-30 (FlightTime) filters, in
     * {@code sort_indicator, id} order, seeding the accumulator's active flight
     * time from {@code flightDurationZeroBasedSeconds} (legacy seeds it from
     * {@code FlightDurationZeroBased} just before the loop). Each matching tier
     * emits one {@link DeliveryItemDetails} and decrements the remaining active
     * time; the loop ends when a full pass applies no rule.
     */
    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    int flightDurationZeroBasedSeconds,
                    List<RuleFilterInput> flightTimeFilters) {
        accumulator.setActiveFlightTimeInSeconds(flightDurationZeroBasedSeconds);

        while (accumulator.getActiveFlightTimeInSeconds() > 0) {
            boolean anyApplied = false;
            for (RuleFilterInput filter : flightTimeFilters) {
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
        long active = accumulator.getActiveFlightTimeInSeconds();
        long min = filter.filterConfig().minFlightTimeSeconds();
        long max = filter.filterConfig().maxFlightTimeSeconds();
        return active > min && active <= max;
    }

    private void apply(RuleBasedDeliveryDetails accumulator,
                       MatchableFlight flight,
                       RuleFilterInput filter) {
        // Legacy AircraftFlightTimeRule.Initialize asserts ArticleTarget.NotNull;
        // a line-emitting filter must carry both an article and a unit.
        String articleNumber = Objects.requireNonNull(filter.articleNumber(),
                "FlightTime filter must carry an articleNumber");
        AccountingUnitType unit = Objects.requireNonNull(filter.accountingUnitType(),
                "FlightTime filter must carry an accountingUnitType");

        int active = accumulator.getActiveFlightTimeInSeconds();
        long min = filter.filterConfig().minFlightTimeSeconds();

        int lineSeconds;
        if (min == 0) {
            lineSeconds = active;
            accumulator.setActiveFlightTimeInSeconds(0);
        } else {
            lineSeconds = (int) (active - min);
            accumulator.setActiveFlightTimeInSeconds((int) min);
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
}
