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

    @Override
    @Query("select coalesce(max(d.batchId), 0) from Delivery d")
    long findMaxBatchId();

    @Override
    @Query("select count(d) from Delivery d where d.flightId = :flightId and d.deletedOn is null")
    long countActiveByFlightId(@Param("flightId") UUID flightId);

    @Override
    Delivery save(Delivery delivery);
}
