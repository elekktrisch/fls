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
    @SuppressWarnings("UnusedVariable")
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
    @SuppressWarnings("UnusedVariable")
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

    public @Nullable UUID getId() {
        return id;
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
