package ch.alpenflight.accounting.infra;

import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA implementation of {@link DeliveryRepository}. Extends both the
 * abstract port and {@code JpaRepository<Delivery, UUID>} so the application layer
 * depends on the port (ADR 0023) while Spring Data generates the runtime bean.
 *
 * <p>Delivery is tenant-scoped via {@code @TenantId} on {@code Delivery.operatingClubId};
 * Hibernate appends the tenant predicate to every JPQL query automatically, so none
 * of these queries name {@code operatingClubId} — it is the discriminator, not a
 * parameter. Soft-delete is filtered at the query layer ({@code deletedOn is null}).
 * JPA-first per ADR 0027 — no native SQL.
 */
public interface JpaDeliveryRepository
        extends JpaRepository<Delivery, UUID>, DeliveryRepository {

    @Override
    @Query("select d from Delivery d where d.deletedOn is null "
            + "order by d.batchId desc, d.recipient.lastname asc, "
            + "d.recipient.firstname asc, d.id asc")
    List<Delivery> findActivePage(Pageable pageable);

    @Override
    @Query("select count(d) from Delivery d where d.deletedOn is null")
    long countActive();

    @Override
    @Query("select d from Delivery d left join fetch d.items "
            + "where d.id = :id and d.deletedOn is null")
    Optional<Delivery> findActiveById(@Param("id") UUID id);
}
