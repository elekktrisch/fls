package ch.alpenflight.flighttypes.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlightCostBalanceTypeRepository {

    List<FlightCostBalanceType> findAllOrderedByLegacyId();

    Optional<FlightCostBalanceType> findById(UUID id);
}
