package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface DeliveryRepository {

    List<Delivery> findActivePage(Pageable pageable);

    long countActive();

    Optional<Delivery> findActiveById(UUID id);

    Delivery save(Delivery delivery);

    long countActiveByFlightId(UUID flightId);

    long findMaxBatchId();
}
