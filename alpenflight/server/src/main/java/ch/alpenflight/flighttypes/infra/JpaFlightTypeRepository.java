package ch.alpenflight.flighttypes.infra;

import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA implementation of {@link FlightTypeRepository}. Extends
 * both the abstract port and {@code JpaRepository<FlightType, UUID>} so the
 * application layer depends on the port (ADR 0023) while Spring Data
 * generates the runtime bean.
 *
 * <p>FlightType is tenant-scoped via {@code @TenantId} on
 * {@code FlightType.operatingClubId}; Hibernate appends the tenant predicate
 * to every query automatically. Soft-delete is filtered at the query layer.
 */
public interface JpaFlightTypeRepository
        extends JpaRepository<FlightType, UUID>, FlightTypeRepository {

    @Override
    @Query("select ft from FlightType ft where ft.deletedOn is null "
            + "order by ft.flightTypeName asc")
    List<FlightType> findAllActive();

    @Override
    @Query("select ft from FlightType ft where ft.id = :id and ft.deletedOn is null")
    Optional<FlightType> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select ft from FlightType ft where ft.flightTypeName = :name and ft.deletedOn is null")
    Optional<FlightType> findActiveByName(@Param("name") String name);
}
