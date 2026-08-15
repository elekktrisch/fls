package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonFlightTimeCreditRepository {

    List<PersonFlightTimeCredit> findActiveForPersonInCurrentTenant(UUID personId);

    Optional<PersonFlightTimeCredit> findById(UUID id);

    Optional<PersonFlightTimeCredit> findByBalancedDeliveryId(UUID deliveryId);

    PersonFlightTimeCredit save(PersonFlightTimeCredit credit);

    void deleteById(UUID id);

    void flush();
}
