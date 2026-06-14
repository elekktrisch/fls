package ch.alpenflight.accounting.domain;

import java.util.List;

/**
 * The two type-70 VSF-fee passes — ported from
 * {@code flsserver/src/FLS.Server.Service/Accounting/Rules/ItemRules/VsfFeeRule.cs}
 * and {@code .../VsfFeeOnStartLocationRule.cs}. Neither pass carries a duration
 * window (base conditions only); both coalesce by article via
 * {@link RuleBasedDeliveryDetails#addItem}. The line text is the filter's
 * {@code deliveryLineText} alone (no immatriculation suffix). The count-quantity
 * line emission is shared with {@link LandingTaxStage}.
 *
 * <p>Bit-exact port — NOT rewritten from understanding (customer invoices depend
 * on the current behavior, the J-9 contract).
 */
public final class VsfFeeStage {

    private final AccountingRuleMatcher matcher;

    public VsfFeeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    /**
     * VsfFee: quantity {@code = nrOfLdgs} (default 1), one line per matching
     * filter against the flight's landing location.
     */
    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    List<RuleFilterInput> vsfFeeFilters) {
        for (RuleFilterInput filter : vsfFeeFilters) {
            if (!matcher.matches(flight, filter.filterConfig())) {
                continue;
            }
            LandingTaxStage.emit(accumulator, filter,
                    LandingTaxStage.quantityOrDefault(flight.nrOfLdgs()));
        }
    }

    /**
     * VsfFeeOnStartLocation: quantity {@code = nrOfLdgsOnStartLocation} (default
     * 1), forced off when that count is {@code <= 0}. The landing-location
     * condition is overridden to match the flight's START location against the
     * filter's ldg-location set — re-running the existing matcher on a
     * MatchableFlight whose {@code ldgLocationIcao} is the start-location ICAO
     * (the T-10a pattern).
     */
    public void runOnStartLocation(RuleBasedDeliveryDetails accumulator,
                                   MatchableFlight flight,
                                   List<RuleFilterInput> vsfFeeFilters) {
        if (LandingTaxStage.defaultZero(flight.nrOfLdgsOnStartLocation()) <= 0) {
            return;
        }
        MatchableFlight onStart = flight.withLdgLocationIcao(flight.startLocationIcao());
        for (RuleFilterInput filter : vsfFeeFilters) {
            if (!matcher.matches(onStart, filter.filterConfig())) {
                continue;
            }
            LandingTaxStage.emit(accumulator, filter,
                    LandingTaxStage.quantityOrDefault(flight.nrOfLdgsOnStartLocation()));
        }
    }
}
