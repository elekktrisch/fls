package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AccountingUnitTypeTest {

    @Test
    void legacyIntCodesAreStable() {
        assertThat(AccountingUnitType.fromCode(10)).isEqualTo(AccountingUnitType.MIN);
        assertThat(AccountingUnitType.fromCode(20)).isEqualTo(AccountingUnitType.SEC);
        assertThat(AccountingUnitType.fromCode(30)).isEqualTo(AccountingUnitType.LDGS);
        assertThat(AccountingUnitType.fromCode(40)).isEqualTo(AccountingUnitType.START_OR_FLIGHT);
        assertThat(AccountingUnitType.fromCode(0)).isEqualTo(AccountingUnitType.UNDEFINED);
    }

    @Test
    void secondsToMinutesDividesBySixty() {
        assertThat(AccountingUnitType.MIN.quantityFrom(new BigDecimal("90"), AccountingUnitType.SEC))
                .isEqualByComparingTo("1.5");
    }

    @Test
    void minutesToSecondsMultipliesBySixty() {
        assertThat(AccountingUnitType.SEC.quantityFrom(new BigDecimal("1.5"), AccountingUnitType.MIN))
                .isEqualByComparingTo("90");
    }

    @Test
    void sameUnitIsIdentity() {
        BigDecimal q = new BigDecimal("123.456");
        assertThat(AccountingUnitType.SEC.quantityFrom(q, AccountingUnitType.SEC)).isEqualByComparingTo(q);
        assertThat(AccountingUnitType.LDGS.quantityFrom(q, AccountingUnitType.LDGS)).isEqualByComparingTo(q);
    }

    @Test
    void landingsAndStartsAreInterchangeable() {
        BigDecimal q = new BigDecimal("2");
        assertThat(AccountingUnitType.LDGS.quantityFrom(q, AccountingUnitType.START_OR_FLIGHT))
                .isEqualByComparingTo("2");
        assertThat(AccountingUnitType.START_OR_FLIGHT.quantityFrom(q, AccountingUnitType.LDGS))
                .isEqualByComparingTo("2");
    }

    @Test
    void timeToCountUnitsThrows() {
        assertThatThrownBy(
                        () -> AccountingUnitType.MIN.quantityFrom(BigDecimal.ONE, AccountingUnitType.LDGS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> AccountingUnitType.LDGS.quantityFrom(BigDecimal.ONE, AccountingUnitType.SEC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unitTypeStringMatchesLegacyGermanLabels() {
        assertThat(AccountingUnitType.SEC.unitTypeString()).isEqualTo("Sekunden");
        assertThat(AccountingUnitType.MIN.unitTypeString()).isEqualTo("Minuten");
        assertThat(AccountingUnitType.START_OR_FLIGHT.unitTypeString()).isEqualTo("Start");
        assertThat(AccountingUnitType.LDGS.unitTypeString()).isEqualTo("Landung");
        assertThat(AccountingUnitType.UNDEFINED.unitTypeString()).isEmpty();
    }
}
