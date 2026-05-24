package ch.alpenflight.flighttypes.infra;

import ch.alpenflight.flighttypes.domain.FlightCostBalanceType;
import ch.alpenflight.flighttypes.domain.FlightCostBalanceTypeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA implementation of {@link FlightCostBalanceTypeRepository}.
 * FCBT is system-global reference data — no tenant filter; rows are managed
 * by Flyway (V3 seed) plus a future sysadmin admin route (deferred per
 * S-053 design notes).
 */
public interface JpaFlightCostBalanceTypeRepository
        extends JpaRepository<FlightCostBalanceType, UUID>, FlightCostBalanceTypeRepository {

    @Override
    @Query("select fcb from FlightCostBalanceType fcb order by fcb.legacyIntId asc")
    List<FlightCostBalanceType> findAllOrderedByLegacyId();

    @Override
    Optional<FlightCostBalanceType> findById(UUID id);
}
