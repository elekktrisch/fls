package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seed orchestration for the flight-time-credit e2e — the only write path for
 * {@link PersonFlightTimeCredit} (no production credit-CRUD surface exists). The
 * clean-seed parity spec grants a credit matched to a freshly-minted flight
 * immatriculation that no static SQL seed could reference.
 * {@code @Profile({"dev","test"})} keeps it out of production (the real-idp e2e
 * backend boots {@code dev}). Audited (financial credit) like every accounting
 * mutation; the owning Person scopes the credit to the caller's tenant.
 */
@Service
@Profile({"dev", "test"})
public class PersonFlightTimeCreditSeedService {

    private static final String AUDIT_ENTITY_TYPE = "PersonFlightTimeCredit";

    private final PersonFlightTimeCreditRepository credits;
    private final PersonRepository persons;
    private final AuditTrail auditTrail;

    PersonFlightTimeCreditSeedService(PersonFlightTimeCreditRepository credits,
                                      PersonRepository persons,
                                      AuditTrail auditTrail) {
        this.credits = credits;
        this.persons = persons;
        this.auditTrail = auditTrail;
    }

    @Transactional
    public CreditView grant(GrantCommand cmd) {
        Person person = persons.findActiveById(cmd.personId())
                .orElseThrow(() -> new PersonNotVisibleException(cmd.personId()));

        PersonFlightTimeCredit credit = PersonFlightTimeCredit.grant(
                person,
                cmd.validUntil() == null ? Instant.EPOCH : cmd.validUntil(),
                cmd.useRuleForAllAircraftsExceptListed(),
                cmd.matchedAircraftImmatriculations(),
                cmd.discountInPercent(),
                cmd.noFlightTimeLimit(),
                cmd.currentFlightTimeBalanceInSeconds());

        CreditView view = CreditView.of(credits.save(credit));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, view.id(), view));
        return view;
    }

    public Optional<CreditView> find(UUID id) {
        return credits.findById(id).map(CreditView::of);
    }

    @Transactional
    public void remove(UUID id) {
        credits.findById(id).ifPresent(credit -> {
            CreditView before = CreditView.of(credit);
            credits.deleteById(id);
            auditTrail.record(AuditAction.DELETE,
                    AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id, before));
        });
    }

    public record GrantCommand(
            UUID personId,
            boolean noFlightTimeLimit,
            boolean useRuleForAllAircraftsExceptListed,
            @Nullable String matchedAircraftImmatriculations,
            int discountInPercent,
            @Nullable Instant validUntil,
            @Nullable Long currentFlightTimeBalanceInSeconds) {}

    public record CreditView(
            UUID id,
            boolean noFlightTimeLimit,
            int discountInPercent,
            @Nullable Long currentFlightTimeBalanceInSeconds) {

        static CreditView of(PersonFlightTimeCredit credit) {
            return new CreditView(
                    Objects.requireNonNull(credit.getId(), "a persisted credit has an id"),
                    credit.isNoFlightTimeLimit(),
                    credit.getDiscountInPercent(),
                    credit.currentBalanceInSeconds());
        }
    }

    public static final class PersonNotVisibleException extends RuntimeException {
        PersonNotVisibleException(UUID personId) {
            super("no Person visible in the caller's tenant: " + personId);
        }
    }
}
