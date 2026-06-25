package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.persons.domain.Person;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryDeleteDomainTest {

    private static final Instant CONSUMED_AT = Instant.parse("2026-06-25T10:00:00Z");
    private static final Instant REVERSED_AT = Instant.parse("2026-06-25T11:00:00Z");

    @Test
    void appendReversal_appendsCurrentRow_flipsPrior_andKeepsConsumptionRow() {
        UUID deliveryId = UUID.randomUUID();
        PersonFlightTimeCredit credit = grantedCredit(5_400L);
        recordConsumption(credit, 3_600L, deliveryId, CONSUMED_AT);
        assertThat(credit.currentBalanceInSeconds()).isEqualTo(1_800L);

        Long balanceBeforeReversal = credit.currentBalanceInSeconds();
        credit.releaseCurrent();
        boolean reversed = credit.appendReversal(deliveryId, balanceBeforeReversal, REVERSED_AT);

        assertThat(reversed).isTrue();
        assertThat(credit.getTransactions())
                .as("grant + consumption + reversal — the original consumption row is kept")
                .hasSize(3);
        assertThat(credit.getTransactions().stream().filter(PersonFlightTimeCreditTransaction::isCurrent))
                .as("exactly one current row after the reversal")
                .hasSize(1);
        assertThat(credit.getTransactions())
                .as("the original consumption row (balanced by the delivery, negated delta) survives un-currented")
                .anyMatch(t -> deliveryId.equals(t.getBalancedDeliveryId())
                        && !t.isCurrent()
                        && t.getFlightTimeBalanceInSeconds() == -3_600L);

        PersonFlightTimeCreditTransaction reversal = credit.currentTransaction().orElseThrow();
        assertThat(reversal.getFlightTimeBalanceInSeconds())
                .as("the reversal adds the consumed seconds back (positive delta)")
                .isEqualTo(3_600L);
        assertThat(reversal.currentFlightTimeBalanceInSeconds())
                .as("balance restored to the pre-consumption value")
                .isEqualTo(5_400L);
        assertThat(reversal.getOldFlightTimeBalanceInSeconds()).isEqualTo(1_800L);
        assertThat(reversal.getBalancedDeliveryId()).isEqualTo(deliveryId);
        assertThat(credit.currentBalanceInSeconds())
                .as("net of consumption + reversal returns the original balance")
                .isEqualTo(5_400L);
    }

    @Test
    void appendReversal_noTransactionBalancedByDelivery_isNoOp() {
        PersonFlightTimeCredit credit = grantedCredit(600L);

        boolean reversed = credit.appendReversal(UUID.randomUUID(), 600L, REVERSED_AT);

        assertThat(reversed).isFalse();
        assertThat(credit.getTransactions()).hasSize(1);
        assertThat(credit.currentBalanceInSeconds()).isEqualTo(600L);
    }

    private static PersonFlightTimeCredit grantedCredit(long balanceSeconds) {
        Person person = Person.register("Petra", "Pilot", null);
        return PersonFlightTimeCredit.grant(
                person, Instant.parse("2026-12-31T00:00:00Z"),
                false, "HB-X", 0, false, balanceSeconds);
    }

    private static void recordConsumption(PersonFlightTimeCredit credit, long seconds, UUID deliveryId, Instant at) {
        Long oldBalance = credit.currentBalanceInSeconds();
        boolean wasUnlimited = credit.currentTransaction().orElseThrow().isNoFlightTimeLimit();
        credit.releaseCurrent();
        credit.appendConsumption(seconds, oldBalance, wasUnlimited, deliveryId, at);
    }
}
