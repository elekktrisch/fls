package ch.alpenflight.clubs.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiscoveryFlightDayRepository {

    List<DiscoveryFlightDay> findAllActive();

    List<DiscoveryFlightDay> findBookableFrom(LocalDate from);

    Optional<DiscoveryFlightDay> findActiveById(UUID id);

    Optional<DiscoveryFlightDay> findActiveByEventDate(LocalDate eventDate);

    DiscoveryFlightDay save(DiscoveryFlightDay day);
}
