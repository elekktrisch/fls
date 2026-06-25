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

/**
 * Aggregate-internal child of {@link PersonFlightTimeCredit}: one balance
 * snapshot. The engine reads only the single {@code IsCurrent} row
 * ({@code AircraftFlightTimeRule.cs:61}); the partial UNIQUE
 * {@code ux_pftc_transaction_current} on {@code (credit_id) WHERE is_current}
 * is the structural backstop for the "exactly one current" invariant.
 *
 * <p>{@code currentFlightTimeBalanceInSeconds} is nullable — {@code null} means
 * unlimited ({@code NoFlightTimeLimit}). {@code balancedDelivery} is a nullable
 * back-reference left null when the linked Delivery is not migrated.
 */
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
        // JPA.
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

    /**
     * The new {@code IsCurrent} balance row a delivery create writes when it
     * consumes {@code consumedSeconds} from this credit
     * ({@code AircraftFlightTimeRule.cs:73-184}, {@code DeliveryService.cs:201-216}).
     * {@code flightTimeBalanceInSeconds} is the negated consumed delta;
     * {@code currentFlightTimeBalanceInSeconds} the new remaining balance
     * (null for an unlimited credit, {@code old - consumed} floored at 0
     * otherwise); {@code balancedDeliveryId} links it to the delivery so a later
     * delete can reverse it.
     */
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

    void clearCurrent() {
        this.current = false;
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
