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
    }

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

    public boolean releaseCurrent() {
        Optional<PersonFlightTimeCreditTransaction> current = currentTransaction();
        current.ifPresent(PersonFlightTimeCreditTransaction::clearCurrent);
        return current.isPresent();
    }

    public void appendConsumption(long consumedSeconds,
                                  @Nullable Long oldBalanceSeconds,
                                  boolean wasUnlimited,
                                  UUID deliveryId,
                                  Instant balanceDateTime) {
        if (consumedSeconds <= 0) {
            return;
        }
        boolean unlimited = wasUnlimited || noFlightTimeLimit;
        Long newBalance = unlimited || oldBalanceSeconds == null
                ? null
                : Math.max(0L, oldBalanceSeconds - consumedSeconds);
        transactions.add(PersonFlightTimeCreditTransaction.consumption(
                this, unlimited, oldBalanceSeconds, newBalance, consumedSeconds, deliveryId, balanceDateTime));
    }

    public boolean appendReversal(UUID deliveryId, @Nullable Long currentBalanceSeconds, Instant balanceDateTime) {
        Optional<PersonFlightTimeCreditTransaction> balanced = transactions.stream()
                .filter(t -> t.balances(deliveryId))
                .findFirst();
        if (balanced.isEmpty()) {
            return false;
        }
        long restoredSeconds = -balanced.get().getFlightTimeBalanceInSeconds();
        boolean unlimited = noFlightTimeLimit;
        Long newBalance = unlimited || currentBalanceSeconds == null
                ? null
                : currentBalanceSeconds + restoredSeconds;
        transactions.add(PersonFlightTimeCreditTransaction.reversal(
                this, unlimited, currentBalanceSeconds, newBalance, restoredSeconds, deliveryId, balanceDateTime));
        return true;
    }

    public @Nullable Long currentBalanceInSeconds() {
        return currentTransaction()
                .map(PersonFlightTimeCreditTransaction::currentFlightTimeBalanceInSeconds)
                .orElse(null);
    }

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
