package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstructorFeeStageTest {

    private static final AccountingRuleMatcher MATCHER = new AccountingRuleMatcher();
    private static final InstructorFeeStage INSTRUCTOR_FEE = new InstructorFeeStage(MATCHER);

    private static final List<MatchableCrew> ONE_PILOT =
            List.of(MatchableCrew.of("PILOT", "1234", null, List.of()));

    private static final int NO_INSTRUCTOR_FEE = 4;

    private static MatchableFlight.Builder flight() {
        return MatchableFlight.builder(FlightAircraftType.GLIDER)
                .immatriculation("HB-1234")
                .flightDurationSeconds(3600)
                .instructorDisplayName("Hans Muster")
                .crew(ONE_PILOT);
    }

    private static RuleFilterInput instructorFeeFilter(String article) {
        FilterConfig base = FilterConfig.empty();
        FilterConfig config = new FilterConfig(
                true, false, false,
                false, false, false, false, false, false,
                null, null, null, null, null,
                base.aircraftImmatriculations(), base.startTypes(), base.flightTypeCodes(),
                base.startLocations(), base.ldgLocations(), base.clubMemberNumbers(),
                base.flightCrewTypes(), base.aircraftHomebases(), base.memberStates(),
                base.personCategories(),
                null, null);
        return new RuleFilterInput(
                UUID.randomUUID(), null, article, AccountingUnitType.MIN, config);
    }

    @Test
    void everyMatchingFilterAddsItsOwnLineEvenForTheSameArticle() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        INSTRUCTOR_FEE.run(acc, flight().build(),
                List.of(instructorFeeFilter("INSTR"), instructorFeeFilter("INSTR")));

        assertThat(acc.deliveryItems()).hasSize(2);
        assertThat(acc.deliveryItems())
                .extracting(DeliveryItemDetails::position)
                .containsExactly(1, 2);
        assertThat(acc.deliveryItems())
                .allSatisfy(item -> assertThat(item.quantity())
                        .isEqualByComparingTo(BigDecimal.valueOf(60)));
    }

    @Test
    void noInstructorFeeFlightBillsZeroQuantity() {
        var acc = RuleBasedDeliveryDetails.forClub(UUID.randomUUID());

        INSTRUCTOR_FEE.run(acc, flight().flightCostBalanceTypeId(NO_INSTRUCTOR_FEE).build(),
                List.of(instructorFeeFilter("INSTR")));

        assertThat(acc.deliveryItems()).hasSize(1);
        assertThat(acc.deliveryItems().get(0).quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(acc.deliveryItems().get(0).itemText()).isEqualTo("Fluglehrer-Honorar Hans Muster");
    }
}
