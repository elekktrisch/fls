package ch.alpenflight.flighttypes.infra;

import ch.alpenflight.flighttypes.domain.FlightCostBalanceType;
import ch.alpenflight.flighttypes.domain.FlightCostBalanceTypeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JpaFlightCostBalanceTypeRepository
        extends JpaRepository<FlightCostBalanceType, UUID>, FlightCostBalanceTypeRepository {

    @Override
    @Query("select fcb from FlightCostBalanceType fcb order by fcb.legacyIntId asc")
    List<FlightCostBalanceType> findAllOrderedByLegacyId();

    @Override
    Optional<FlightCostBalanceType> findById(UUID id);
}
