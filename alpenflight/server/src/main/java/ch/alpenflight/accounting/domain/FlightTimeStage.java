package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
 * <p>The {@code PersonFlightTimeCredit} / discount / over-credit-two-line-split
 * branch ({@code AircraftFlightTimeRule.Apply}:49-184) applies a person's pre-paid
 * credits to the emitted line — credited line + billed remainder when the balance
 * only partly covers the tier. The credits are a provided domain input; an empty
 * list leaves the pure decrement path untouched. The persisted transaction
 * side-effects (the new {@code PersonFlightTimeCreditTransaction}) are NOT modeled
 * here — the dry-run path that consumes this stage mutates nothing.
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
        run(accumulator, flight, flightDurationZeroBasedSeconds, flightTimeFilters, List.of());
    }

    /**
     * The credit-aware loop: {@code credits} are the flight's person's pre-paid
     * {@link PersonFlightTimeCredit}s. The first matched credit with a usable
     * balance discounts the emitted line (and splits it on over-credit). The
     * balance is read from the credit's current ({@code IsCurrent}) transaction and
     * is NOT mutated here — legacy records the resulting balance only on a new
     * transaction persisted once on a real run, so each emitted line sees the
     * original balance (see {@link CreditLedger}). An empty list is the pure
     * decrement path.
     */
    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    int flightDurationZeroBasedSeconds,
                    List<RuleFilterInput> flightTimeFilters,
                    List<PersonFlightTimeCredit> credits) {
        accumulator.setActiveFlightTimeInSeconds(flightDurationZeroBasedSeconds);
        CreditLedger ledger = new CreditLedger(credits);

        while (accumulator.getActiveFlightTimeInSeconds() > 0) {
            boolean anyApplied = false;
            for (RuleFilterInput filter : flightTimeFilters) {
                if (applies(accumulator, flight, filter)) {
                    apply(accumulator, flight, filter, ledger);
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
                       RuleFilterInput filter,
                       CreditLedger ledger) {
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

        String itemText = itemText(flight, filter.filterConfig());

        // Legacy runs the credit branch only on the fresh-add path: when a line for
        // this article already exists it merges the quantity into it and skips the
        // credit handling entirely (cs:97-104 vs the else at :105-184). Reproduce
        // that merge-into-existing case with the coalescing addItem.
        if (accumulator.hasItemForArticle(articleNumber)) {
            emit(accumulator, articleNumber, itemText, lineSeconds, 0, unit, true);
            accumulator.markFilterMatched(filter.filterId());
            return;
        }

        CreditLedger.Match credit = ledger.match(flight.immatriculation());

        if (credit == null) {
            emit(accumulator, articleNumber, itemText, lineSeconds, 0, unit, false);
        } else if (lineSeconds > credit.balanceSeconds()) {
            // Split the BILLED slice (lineSeconds = active - min), not the full
            // active: diverges from the legacy over-credit on min != 0 tiers,
            // which compares/credits the full active and can emit a credited line
            // exceeding the slice plus a negative remainder (ADR 0026).
            long creditedSeconds = Math.min(credit.balanceSeconds(), lineSeconds);
            long remainderSeconds = lineSeconds - creditedSeconds;
            emit(accumulator, articleNumber, itemText, creditedSeconds, credit.discountInPercent(), unit, false);
            emit(accumulator, articleNumber, itemText, remainderSeconds, 0, unit, false);
            recordConsumption(accumulator, credit, creditedSeconds);
        } else {
            emit(accumulator, articleNumber, itemText, lineSeconds, credit.discountInPercent(), unit, false);
            recordConsumption(accumulator, credit, lineSeconds);
        }
        accumulator.markFilterMatched(filter.filterId());
    }

    // The credit-ledger entry on RuleBasedDeliveryDetails the real persist path
    // turns into a PersonFlightTimeCreditTransaction; the dry-run ignores it. An
    // unlimited credit (no id-bearing balance) still records its consumed delta so
    // the create-time ledger writes the audit row legacy writes for it.
    private static void recordConsumption(RuleBasedDeliveryDetails accumulator,
                                          CreditLedger.Match credit,
                                          long seconds) {
        UUID creditId = credit.creditId();
        if (creditId != null) {
            accumulator.recordCreditConsumption(creditId, seconds);
        }
    }

    // coalesce mirrors legacy's two add paths: the fresh-add path (cs:138/:170 direct
    // .Add, so the over-credit split yields two distinct same-article lines) vs the
    // pre-existing-line quantity merge (cs:100-101).
    private void emit(RuleBasedDeliveryDetails accumulator,
                      String articleNumber,
                      String itemText,
                      long seconds,
                      int discountInPercent,
                      AccountingUnitType unit,
                      boolean coalesce) {
        BigDecimal quantity =
                unit.quantityFrom(BigDecimal.valueOf(seconds), AccountingUnitType.SEC);
        DeliveryItemDetails item = new DeliveryItemDetails(
                0, articleNumber, itemText, null, quantity, discountInPercent, unit.unitTypeString());
        if (coalesce) {
            accumulator.addItem(item);
        } else {
            accumulator.addLineWithoutCoalesce(item);
        }
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

    /**
     * The per-run credit state: holds the flight's person's credits. Each emitted
     * line re-evaluates the first matching credit with a usable balance from the
     * ORIGINAL current balance — legacy reads {@code credit.…First(IsCurrent)}
     * fresh on every pass and never mutates that source between a delivery's lines
     * ({@code AircraftFlightTimeRule.cs:61}; the resulting balance is recorded only
     * on a NEW transaction persisted once on a real run, {@code DeliveryService.cs:201-214}),
     * so a credit hit by two tiers of one flight sees its full balance each time.
     * First-match-wins re-resolves per line (legacy re-runs the credit loop every
     * pass and {@code break}s on the first match, {@code cs:92}).
     */
    private static final class CreditLedger {

        private final List<PersonFlightTimeCredit> credits;

        CreditLedger(List<PersonFlightTimeCredit> credits) {
            this.credits = new ArrayList<>(credits);
        }

        @Nullable Match match(@Nullable String immatriculation) {
            for (PersonFlightTimeCredit credit : credits) {
                if (!matchesImmatriculation(credit, immatriculation)) {
                    continue;
                }
                if (credit.isNoFlightTimeLimit()) {
                    return new Match(credit, Long.MAX_VALUE);
                }
                Long balance = credit.currentBalanceInSeconds();
                long usable = balance == null ? 0 : balance;
                if (usable <= 0) {
                    continue;
                }
                return new Match(credit, usable);
            }
            return null;
        }

        // Reproduce legacy MatchesPersonFlightTimeCredit (cs:191-214) — C# String.Contains
        // is a SUBSTRING test on the raw CSV (parity exclusion: NOT split/normalized).
        // UseRuleForAllAircraftsExceptListed inverts: true ⇒ applies when the non-empty
        // list does NOT contain the immat; false ⇒ applies when it does (null list skips,
        // not the legacy NPE).
        private static boolean matchesImmatriculation(PersonFlightTimeCredit credit,
                                                      @Nullable String immatriculation) {
            String list = credit.getMatchedAircraftImmatriculations();
            String immat = immatriculation == null ? "" : immatriculation;
            if (credit.isUseRuleForAllAircraftsExceptListed()) {
                return list != null && !list.isEmpty() && !list.contains(immat);
            }
            return list != null && list.contains(immat);
        }

        /** One matched credit and its usable balance for the line being emitted. */
        static final class Match {

            private final PersonFlightTimeCredit credit;
            private final long balanceSeconds;

            Match(PersonFlightTimeCredit credit, long balanceSeconds) {
                this.credit = credit;
                this.balanceSeconds = balanceSeconds;
            }

            long balanceSeconds() {
                return balanceSeconds;
            }

            @Nullable UUID creditId() {
                return credit.getId();
            }

            int discountInPercent() {
                return credit.getDiscountInPercent();
            }
        }
    }
}
