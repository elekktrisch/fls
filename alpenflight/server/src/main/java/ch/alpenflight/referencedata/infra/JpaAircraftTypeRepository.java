package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.AircraftType;
import ch.alpenflight.referencedata.domain.AircraftTypeRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAircraftTypeRepository
        extends JpaRepository<AircraftType, UUID>, AircraftTypeRepository {
}
