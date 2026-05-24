package ch.alpenflight.flighttypes.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link FlightCostBalanceType} persistence. Implemented by
 * {@code ch.alpenflight.flighttypes.infra.JpaFlightCostBalanceTypeRepository}.
 *
 * <p>FCBT is system-global reference data — no tenant filter. Ordered by
 * {@code legacyIntId} ascending for stable listing across releases (matches
 * the V3 seed order).
 */
public interface FlightCostBalanceTypeRepository {

    List<FlightCostBalanceType> findAllOrderedByLegacyId();

    Optional<FlightCostBalanceType> findById(UUID id);
}
