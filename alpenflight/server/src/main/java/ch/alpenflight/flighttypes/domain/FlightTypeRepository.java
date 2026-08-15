package ch.alpenflight.flighttypes.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlightTypeRepository {

    List<FlightType> findAllActive();

    Optional<FlightType> findActiveById(UUID id);

    Optional<FlightType> findActiveByName(String name);

    Optional<FlightType> findActiveByCode(String flightCode);

    FlightType save(FlightType flightType);

    void flush();
}
