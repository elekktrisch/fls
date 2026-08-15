package ch.alpenflight.accounting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_person_flight_time_credit_transaction")
public class PersonFlightTimeCreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "credit_id", nullable = false)
    @SuppressWarnings("UnusedVariable")
    private @Nullable PersonFlightTimeCredit credit;

    @Column(name = "balanced_delivery_id")
    private @Nullable UUID balancedDeliveryId;

    @Column(name = "balance_date_time", nullable = false)
    private Instant balanceDateTime = Instant.EPOCH;

    @Column(name = "no_flight_time_limit", nullable = false)
    private boolean noFlightTimeLimit;

    @Column(name = "current_flight_time_balance_in_seconds")
    private @Nullable Long currentFlightTimeBalanceInSeconds;

    @Column(name = "flight_time_balance_in_seconds", nullable = false)
    private long flightTimeBalanceInSeconds;

    @Column(name = "old_flight_time_balance_in_seconds")
    private @Nullable Long oldFlightTimeBalanceInSeconds;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(name = "deleted_on")
    @SuppressWarnings("UnusedVariable")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    protected PersonFlightTimeCreditTransaction() {
    }

    static PersonFlightTimeCreditTransaction current(PersonFlightTimeCredit credit,
                                                     boolean unlimited,
                                                     @Nullable Long balanceInSeconds) {
        PersonFlightTimeCreditTransaction tx = new PersonFlightTimeCreditTransaction();
        tx.credit = credit;
        tx.noFlightTimeLimit = unlimited;
        tx.currentFlightTimeBalanceInSeconds = unlimited ? null : balanceInSeconds;
        tx.current = true;
        return tx;
    }

    static PersonFlightTimeCreditTransaction consumption(PersonFlightTimeCredit credit,
                                                         boolean unlimited,
                                                         @Nullable Long oldBalanceSeconds,
                                                         @Nullable Long newBalanceSeconds,
                                                         long consumedSeconds,
                                                         UUID balancedDeliveryId,
                                                         Instant balanceDateTime) {
        PersonFlightTimeCreditTransaction tx = new PersonFlightTimeCreditTransaction();
        tx.credit = credit;
        tx.noFlightTimeLimit = unlimited;
        tx.oldFlightTimeBalanceInSeconds = unlimited ? null : oldBalanceSeconds;
        tx.currentFlightTimeBalanceInSeconds = unlimited ? null : newBalanceSeconds;
        tx.flightTimeBalanceInSeconds = -consumedSeconds;
        tx.balancedDeliveryId = balancedDeliveryId;
        tx.balanceDateTime = balanceDateTime;
        tx.current = true;
        return tx;
    }

    static PersonFlightTimeCreditTransaction reversal(PersonFlightTimeCredit credit,
                                                      boolean unlimited,
                                                      @Nullable Long oldBalanceSeconds,
                                                      @Nullable Long newBalanceSeconds,
                                                      long restoredSeconds,
                                                      @Nullable UUID balancedDeliveryId,
                                                      Instant balanceDateTime) {
        PersonFlightTimeCreditTransaction tx = new PersonFlightTimeCreditTransaction();
        tx.credit = credit;
        tx.noFlightTimeLimit = unlimited;
        tx.oldFlightTimeBalanceInSeconds = unlimited ? null : oldBalanceSeconds;
        tx.currentFlightTimeBalanceInSeconds = unlimited ? null : newBalanceSeconds;
        tx.flightTimeBalanceInSeconds = restoredSeconds;
        tx.balancedDeliveryId = balancedDeliveryId;
        tx.balanceDateTime = balanceDateTime;
        tx.current = true;
        return tx;
    }

    void clearCurrent() {
        this.current = false;
    }

    boolean balances(UUID deliveryId) {
        return deliveryId.equals(balancedDeliveryId);
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getBalancedDeliveryId() {
        return balancedDeliveryId;
    }

    public long getFlightTimeBalanceInSeconds() {
        return flightTimeBalanceInSeconds;
    }

    public @Nullable Long getOldFlightTimeBalanceInSeconds() {
        return oldFlightTimeBalanceInSeconds;
    }

    public Instant getBalanceDateTime() {
        return balanceDateTime;
    }

    public @Nullable Long currentFlightTimeBalanceInSeconds() {
        return currentFlightTimeBalanceInSeconds;
    }

    public boolean isNoFlightTimeLimit() {
        return noFlightTimeLimit;
    }

    public boolean isCurrent() {
        return current;
    }
}
