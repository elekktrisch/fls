package ch.alpenflight.accounting.infra;

import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data implementation of {@link PersonFlightTimeCreditRepository}.
 *
 * <p>Tenancy is indirect (the credit has no {@code @TenantId}). The read pivots
 * {@code FROM PersonClub} so Hibernate's {@code @TenantId} discriminator on
 * {@code PersonClub.clubId} appends {@code club_id = :currentTenant}
 * automatically — the same pivot {@code JpaPersonRepository} uses for its
 * cross-tenant Person list. A credit surfaces only when its owning Person has an
 * alive PersonClub in the caller's tenant
 * ({@code DeliveryService.cs:142-146} parity). JPA-first per ADR 0027.
 */
public interface JpaPersonFlightTimeCreditRepository
        extends JpaRepository<PersonFlightTimeCredit, UUID>, PersonFlightTimeCreditRepository {

    @Override
    @Query("select distinct c from PersonClub pc "
            + "join pc.person p "
            + "join PersonFlightTimeCredit c on c.person = p "
            + "left join fetch c.transactions "
            + "where p.id = :personId "
            + "and pc.deletedOn is null "
            + "and p.deletedOn is null "
            + "and c.deletedOn is null")
    List<PersonFlightTimeCredit> findActiveForPersonInCurrentTenant(@Param("personId") UUID personId);
}
