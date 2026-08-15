package ch.alpenflight.accounting.infra;

import ch.alpenflight.accounting.domain.DeliveryCreationTest;
import ch.alpenflight.accounting.domain.DeliveryCreationTestRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
