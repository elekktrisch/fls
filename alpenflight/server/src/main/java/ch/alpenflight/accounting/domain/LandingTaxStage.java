package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The two type-60 landing-tax passes, ported from
 * {@code flsserver/src/FLS.Server.Service/Accounting/Rules/ItemRules/LandingTaxRule.cs}
 * and {@code .../LandingTaxOnStartLocationRule.cs}. Both run AFTER
 * {@link NoLandingTaxStage} (which sets the suppression flags they read) and
 * both coalesce by article via {@link RuleBasedDeliveryDetails#addItem}.
 *
 * <p>Bit-exact port — NOT rewritten from understanding (customer invoices depend
 * on the current behavior, the J-9 contract).
 */
public final class LandingTaxStage {

    private final AccountingRuleMatcher matcher;

    public LandingTaxStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    /**
     * LandingTax: quantity {@code = nrOfLdgs} (default 1), one line per matching
     * filter. The duration window is SKIPPED when the flight has no start- or
     * landing-time information ("assume the aircraft was in the air"); the
     * suppression flags can still force the line off for a glider/tow flight.
     */
    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> landingTaxFilters) {
        for (RuleFilterInput filter : landingTaxFilters) {
            if (suppressed(accumulator, flight)) {
                continue;
            }
            // No start/ldg time -> assume in the air, skip the duration window.
            boolean inWindow = flight.noStartTimeInformation() || flight.noLdgTimeInformation()
                    || durationInWindow(flight.flightDurationSeconds(), filter.filterConfig());
            if (!inWindow || !matcher.matches(flight, filter.filterConfig())) {
                continue;
            }
            emit(accumulator, filter, quantityOrDefault(flight.nrOfLdgs()));
        }
    }

    /**
     * LandingTaxOnStartLocation: quantity {@code = nrOfLdgsOnStartLocation}
     * (default 1), forced off when that count is {@code <= 0}. The landing-location
     * condition is overridden to match the flight's START location against the
     * filter's ldg-location set — done by re-running the existing matcher on a
     * MatchableFlight whose {@code ldgLocationIcao} is the start-location ICAO.
     * Unlike {@link #run}, this pass KEEPS the duration window (no no-time-info
     * skip).
     */
    public void runOnStartLocation(RuleBasedDeliveryDetails accumulator,
                                   MatchableFlight flight,
                                   List<RuleFilterInput> landingTaxFilters) {
        // Forced off when there are no landings on the start location.
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

    // Forced off when a no-landing-tax flag set by NoLandingTaxStage covers this
    // flight's aircraft kind (glider/tow).
    private static boolean suppressed(RuleBasedDeliveryDetails accumulator, MatchableFlight flight) {
        return (accumulator.isNoLandingTaxForGlider() && flight.isGlider())
                || (accumulator.isNoLandingTaxForTowFlight() && flight.isTow());
    }

    private void emit(RuleBasedDeliveryDetails accumulator,
                      RuleFilterInput filter,
                      int quantity) {
        String articleNumber = Objects.requireNonNull(filter.articleNumber(),
                "LandingTax filter must carry an articleNumber");
        AccountingUnitType unit = Objects.requireNonNull(filter.accountingUnitType(),
                "LandingTax filter must carry an accountingUnitType");
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

    private static int quantityOrDefault(@Nullable Integer count) {
        return count == null ? 1 : count;
    }

    private static int defaultZero(@Nullable Integer count) {
        return count == null ? 0 : count;
    }

    static boolean durationInWindow(int durationSeconds, FilterConfig config) {
        return durationSeconds > config.minFlightTimeSeconds()
                && durationSeconds <= config.maxFlightTimeSeconds();
    }
}
