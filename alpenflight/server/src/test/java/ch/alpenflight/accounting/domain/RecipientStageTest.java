package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecipientStageTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();
    private static final RecipientStage STAGE = new RecipientStage(MATCHER);

    private static final List<MatchableCrew> ONE_PILOT =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));

    /** A glider-scoped recipient filter that matches any glider flight with crew. */
    private static FilterConfig gliderRule() {
        FilterConfig base = FilterConfig.empty();
        return new FilterConfig(
                true, false, false,
                false, false, false, false, false, false,
                null, null, null, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                null, null);
    }

    private static MatchableFlight glider() {
        return MatchableFlight.builder(FlightAircraftType.GLIDER).crew(ONE_PILOT).build();
    }

    // Recipient filters all carry stopRuleEngineWhenApplied=true, so the FIRST
    // matching filter in (sort_indicator, id) order wins — the lower-sort filter
    // stops the engine before the higher-sort one is even evaluated.
    @Test
    void firstMatchingRecipientFilterWinsAndStops() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        var winner = new Recipient(null, "111", "First Winner", null, null);
        var loser = new Recipient(null, "222", "Second Loser", null, null);

        STAGE.run(acc, glider(), List.of(
                new RuleFilterInput(firstId, winner, gliderRule()),
                new RuleFilterInput(secondId, loser, gliderRule())));

        assertThat(acc.recipient()).isEqualTo(winner);
        assertThat(acc.getMatchedFilterIds()).containsExactly(firstId);
    }

    // DeliveryRecipientRule throws when the matched recipient filter has a null
    // RecipientTarget — legacy cannot create a delivery without a recipient.
    @Test
    void matchedRecipientFilterWithNullTargetThrows() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        assertThatThrownBy(() -> STAGE.run(acc, glider(),
                List.of(new RuleFilterInput(UUID.randomUUID(), null, gliderRule()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // With NO recipient filter matching, the two appended fallbacks run; they do
    // NOT stop, so among themselves the LAST applying wins.
    @Test
    void fallbackRulesResolveRecipientWhenNoRecipientFilterMatches() {
        var costsPaidByPerson = MatchableFlight.builder(FlightAircraftType.GLIDER)
                .crew(ONE_PILOT)
                .flightCostBalanceTypeId(5)
                .flightCostInvoiceRecipient(new Recipient(null, "INV", "Invoice Recipient", null, null))
                .pilot(new Recipient(null, "PIL", "The Pilot", null, null))
                .build();
        var personAcc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(personAcc, costsPaidByPerson, List.of());

        assertThat(personAcc.recipient().personClubMemberNumber()).isEqualTo("INV");
        assertThat(personAcc.getMatchedFilterIds()).isEmpty();

        var pilotPaysAll = MatchableFlight.builder(FlightAircraftType.GLIDER)
                .crew(ONE_PILOT)
                .flightCostBalanceTypeId(1)
                .flightCostInvoiceRecipient(new Recipient(null, "INV", "Invoice Recipient", null, null))
                .pilot(new Recipient(null, "PIL", "The Pilot", null, null))
                .build();
        var pilotAcc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        STAGE.run(pilotAcc, pilotPaysAll, List.of());

        assertThat(pilotAcc.recipient().personClubMemberNumber()).isEqualTo("PIL");
    }
}
