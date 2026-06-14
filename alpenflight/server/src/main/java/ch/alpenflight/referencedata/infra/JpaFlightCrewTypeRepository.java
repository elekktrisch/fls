package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.FlightCrewType;
import ch.alpenflight.referencedata.domain.FlightCrewTypeRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data adapter for {@link FlightCrewTypeRepository}. V3-seeded; 7 rows. */
public interface JpaFlightCrewTypeRepository
        extends JpaRepository<FlightCrewType, UUID>, FlightCrewTypeRepository {
}
