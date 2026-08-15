package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.LocationType;
import ch.alpenflight.referencedata.domain.LocationTypeRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaLocationTypeRepository
        extends JpaRepository<LocationType, UUID>, LocationTypeRepository {
}
