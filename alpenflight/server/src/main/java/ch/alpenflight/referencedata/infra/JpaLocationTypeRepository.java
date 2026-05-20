package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.LocationType;
import ch.alpenflight.referencedata.domain.LocationTypeRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data adapter for {@link LocationTypeRepository}. The
 * {@code findAllByOrderByDescriptionAsc} derived-query method name signals
 * the canonical sort to dropdown consumers; the underlying set is the seeded
 * V3 row catalog (6 rows today).
 */
public interface JpaLocationTypeRepository
        extends JpaRepository<LocationType, UUID>, LocationTypeRepository {
}
