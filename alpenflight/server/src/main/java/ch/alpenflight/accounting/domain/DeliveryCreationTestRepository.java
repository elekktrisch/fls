package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryCreationTestRepository {

    List<DeliveryCreationTest> findAllActiveOrderedByName();

    Optional<DeliveryCreationTest> findActiveById(UUID id);

    DeliveryCreationTest save(DeliveryCreationTest test);

    void flush();
}
