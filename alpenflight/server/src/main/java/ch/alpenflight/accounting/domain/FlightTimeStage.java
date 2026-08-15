package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class FlightTimeStage {

    private final AccountingRuleMatcher matcher;

    public FlightTimeStage(AccountingRuleMatcher matcher) {
        this.matcher = matcher;
    }

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    int flightDurationZeroBasedSeconds,
                    List<RuleFilterInput> flightTimeFilters) {
        run(accumulator, flight, flightDurationZeroBasedSeconds, flightTimeFilters, List.of());
    }

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    int flightDurationZeroBasedSeconds,
                    List<RuleFilterInput> flightTimeFilters,
                    List<PersonFlightTimeCredit> credits) {
        accumulator.setActiveFlightTimeInSeconds(flightDurationZeroBasedSeconds);
        OriginalCreditBalances creditBalances = new OriginalCreditBalances(credits);

        while (accumulator.getActiveFlightTimeInSeconds() > 0) {
            boolean anyApplied = false;
            for (RuleFilterInput filter : flightTimeFilters) {
                if (applies(accumulator, flight, filter)) {
                    apply(accumulator, flight, filter, creditBalances);
                    anyApplied = true;
                }
            }
            if (!anyApplied) {
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
        long active = accumulator.getActiveFlightTimeInSeconds();
        long min = filter.filterConfig().minFlightTimeSeconds();
        long max = filter.filterConfig().maxFlightTimeSeconds();
        return active > min && active <= max;
    }

    private void apply(RuleBasedDeliveryDetails accumulator,
                       MatchableFlight flight,
                       RuleFilterInput filter,
                       OriginalCreditBalances creditBalances) {
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

        if (accumulator.hasItemForArticle(articleNumber)) {
            emit(accumulator, articleNumber, itemText, lineSeconds, 0, unit, true);
            accumulator.markFilterMatched(filter.filterId());
            return;
        }

        OriginalCreditBalances.Match credit = creditBalances.match(flight.immatriculation());

        if (credit == null) {
            emit(accumulator, articleNumber, itemText, lineSeconds, 0, unit, false);
        } else if (lineSeconds > credit.balanceSeconds()) {
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

    private static void recordConsumption(RuleBasedDeliveryDetails accumulator,
                                          OriginalCreditBalances.Match credit,
                                          long seconds) {
        UUID creditId = credit.creditId();
        if (creditId != null) {
            accumulator.recordCreditConsumption(creditId, seconds);
        }
    }

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

    private static final class OriginalCreditBalances {

        private final List<PersonFlightTimeCredit> credits;

        OriginalCreditBalances(List<PersonFlightTimeCredit> credits) {
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

        private static boolean matchesImmatriculation(PersonFlightTimeCredit credit,
                                                      @Nullable String immatriculation) {
            String matchedImmatriculationsCsv = credit.getMatchedAircraftImmatriculations();
            String immat = immatriculation == null ? "" : immatriculation;
            if (credit.isUseRuleForAllAircraftsExceptListed()) {
                return matchedImmatriculationsCsv != null
                        && !matchedImmatriculationsCsv.isEmpty()
                        && !matchedImmatriculationsCsv.contains(immat);
            }
            return matchedImmatriculationsCsv != null && matchedImmatriculationsCsv.contains(immat);
        }

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
