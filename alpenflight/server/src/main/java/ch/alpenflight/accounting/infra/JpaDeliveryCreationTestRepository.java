package ch.alpenflight.accounting.infra;

import ch.alpenflight.accounting.domain.DeliveryCreationTest;
import ch.alpenflight.accounting.domain.DeliveryCreationTestRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA implementation of {@link DeliveryCreationTestRepository}.
 * Extends both the abstract port and {@code JpaRepository<DeliveryCreationTest,
 * UUID>} so the application layer depends on the port (ADR 0023) while Spring
 * Data generates the runtime bean.
 *
 * <p>DeliveryCreationTest is tenant-scoped via {@code @TenantId} on
 * {@code DeliveryCreationTest.operatingClubId}; Hibernate appends the tenant
 * predicate to every JPQL query automatically, so none of these queries name
 * {@code operatingClubId} — it is the discriminator, not a parameter. Soft-delete
 * is filtered at the query layer ({@code deletedOn is null}). JPA-first per
 * ADR 0027 — no native SQL.
 */
public interface JpaDeliveryCreationTestRepository
        extends JpaRepository<DeliveryCreationTest, UUID>, DeliveryCreationTestRepository {

    @Override
    @Query("select dct from DeliveryCreationTest dct where dct.deletedOn is null "
            + "order by dct.testName asc, dct.id asc")
    List<DeliveryCreationTest> findAllActiveOrderedByName();

    @Override
    @Query("select dct from DeliveryCreationTest dct left join fetch dct.items "
            + "where dct.id = :id and dct.deletedOn is null")
    Optional<DeliveryCreationTest> findActiveById(@Param("id") UUID id);
}
