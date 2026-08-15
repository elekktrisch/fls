package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.AircraftState;
import ch.alpenflight.referencedata.domain.AircraftStateRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAircraftStateRepository
        extends JpaRepository<AircraftState, UUID>, AircraftStateRepository {
}
