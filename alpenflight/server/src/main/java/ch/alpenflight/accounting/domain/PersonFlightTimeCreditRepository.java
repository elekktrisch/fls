package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link PersonFlightTimeCredit} reads. Implemented by
 * {@code ch.alpenflight.accounting.infra.JpaPersonFlightTimeCreditRepository}.
 *
 * <p>Tenancy is INDIRECT: the credit carries no {@code @TenantId}. The engine
 * load is scoped exactly as legacy ({@code DeliveryService.cs:142-146}) — a
 * credit is visible to the caller's tenant when its owning {@link
 * ch.alpenflight.persons.domain.Person} has an alive {@link
 * ch.alpenflight.persons.domain.PersonClub} in that tenant. The read JOINs
 * through {@code PersonClub} so Hibernate's {@code @TenantId} predicate fires
 * automatically. JPA-first per ADR 0027 — no native SQL.
 */
public interface PersonFlightTimeCreditRepository {

    /**
     * Active, in-validity credits owned by a Person VISIBLE in the caller's
     * tenant (the owning Person has an alive {@code PersonClub} there). The
     * engine reads each credit's current balance via
     * {@link PersonFlightTimeCredit#currentBalanceInSeconds()}. A credit whose
     * owner is not a member of the caller's tenant is invisible.
     */
    List<PersonFlightTimeCredit> findActiveForPersonInCurrentTenant(UUID personId);

    Optional<PersonFlightTimeCredit> findById(UUID id);

    /**
     * The credit holding the transaction balanced by {@code deliveryId} (its
     * {@code transactions} fetched), or empty when the delivery consumed no credit —
     * the delete-time reversal lookup ({@code DeliveryService.cs:1257}). The credit
     * carries no {@code @TenantId}; the balanced delivery (loaded under the caller's
     * tenant) is the scoping anchor.
     */
    Optional<PersonFlightTimeCredit> findByBalancedDeliveryId(UUID deliveryId);

    PersonFlightTimeCredit save(PersonFlightTimeCredit credit);

    void deleteById(UUID id);

    void flush();
}
