package ch.alpenflight.accounting.domain;

import ch.alpenflight.persons.domain.Person;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Pre-paid flight-time credit held by a {@link Person}. <strong>Cross-tenant by
 * design</strong> (no {@code @TenantId}) — a credit is per-person and applies in
 * every club the person flies for; legacy scopes the load via
 * {@code Person -> PersonClubs.ClubId = currentTenant}
 * ({@code DeliveryService.cs:142-146}), so the row carries no {@code club_id}.
 * The repo's current-balance read JOINs the owning Person's {@code PersonClub}
 * rows filtered to the caller's tenant (the cross-tenant {@link Person} pattern).
 *
 * <p>Per ADR 0022 directive 2 the credit-branch math (immatriculation activation,
 * over-credit split, discount stamping) lives on the engine as Java, not in the
 * schema. The aggregate holds the current balance as the single
 * {@code IsCurrent} {@link PersonFlightTimeCreditTransaction}
 * ({@code AircraftFlightTimeRule.cs:61}); {@link #currentBalanceInSeconds()}
 * returns it ({@code null} ⇒ unlimited).
 */
@Entity
@Table(name = "t_person_flight_time_credit")
public class PersonFlightTimeCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    private @Nullable Person person;

    @Column(name = "no_flight_time_limit", nullable = false)
    private boolean noFlightTimeLimit;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil = Instant.EPOCH;

    @Column(name = "use_rule_for_all_aircrafts_except_listed", nullable = false)
    private boolean useRuleForAllAircraftsExceptListed;

    @Column(name = "matched_aircraft_immatriculations")
    private @Nullable String matchedAircraftImmatriculations;

    @Column(name = "discount_in_percent", nullable = false)
    private int discountInPercent;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    @OneToMany(mappedBy = "credit",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<PersonFlightTimeCreditTransaction> transactions = new ArrayList<>();

    protected PersonFlightTimeCredit() {
        // JPA.
    }

    /**
     * Grants a credit to {@code person}. The current balance is recorded as a
     * single {@code IsCurrent} transaction — {@code unlimited == true} mirrors
     * {@code NoFlightTimeLimit} (a {@code null} balance), otherwise
     * {@code balanceInSeconds} is the prepaid balance.
     */
    public static PersonFlightTimeCredit grant(Person person,
                                               Instant validUntil,
                                               boolean useRuleForAllAircraftsExceptListed,
                                               @Nullable String matchedAircraftImmatriculations,
                                               int discountInPercent,
                                               boolean unlimited,
                                               @Nullable Long balanceInSeconds) {
        if (person == null) {
            throw new IllegalArgumentException("person must not be null");
        }
        PersonFlightTimeCredit credit = new PersonFlightTimeCredit();
        credit.person = person;
        credit.validUntil = validUntil;
        credit.noFlightTimeLimit = unlimited;
        credit.useRuleForAllAircraftsExceptListed = useRuleForAllAircraftsExceptListed;
        credit.matchedAircraftImmatriculations = matchedAircraftImmatriculations;
        credit.discountInPercent = discountInPercent;
        credit.transactions.add(PersonFlightTimeCreditTransaction.current(
                credit, unlimited, unlimited ? null : balanceInSeconds));
        return credit;
    }

    /**
     * The current balance in seconds — the single {@code IsCurrent} transaction's
     * {@code CurrentFlightTimeBalanceInSeconds} ({@code AircraftFlightTimeRule.cs:61}).
     * {@code null} ⇒ unlimited ({@code NoFlightTimeLimit}). Distinguish "unlimited"
     * (this returns {@code null} AND {@link #hasCurrentBalance()} is {@code true})
     * from "no current transaction" via {@link #hasCurrentBalance()} — a raw nullable
     * is returned rather than an {@code Optional} so a null balance isn't collapsed to
     * empty.
     */
    public @Nullable Long currentBalanceInSeconds() {
        return currentTransaction()
                .map(PersonFlightTimeCreditTransaction::currentFlightTimeBalanceInSeconds)
                .orElse(null);
    }

    /** Whether a single {@code IsCurrent} transaction exists (the partial UNIQUE guarantees at most one). */
    public boolean hasCurrentBalance() {
        return currentTransaction().isPresent();
    }

    public Optional<PersonFlightTimeCreditTransaction> currentTransaction() {
        return transactions.stream()
                .filter(PersonFlightTimeCreditTransaction::isCurrent)
                .findFirst();
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable Person getPerson() {
        return person;
    }

    public boolean isNoFlightTimeLimit() {
        return noFlightTimeLimit;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public boolean isUseRuleForAllAircraftsExceptListed() {
        return useRuleForAllAircraftsExceptListed;
    }

    public @Nullable String getMatchedAircraftImmatriculations() {
        return matchedAircraftImmatriculations;
    }

    public int getDiscountInPercent() {
        return discountInPercent;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    public List<PersonFlightTimeCreditTransaction> getTransactions() {
        return List.copyOf(transactions);
    }
}
