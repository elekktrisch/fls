package ch.alpenflight.flighttypes.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link FlightType} persistence. Implemented by
 * {@code ch.alpenflight.flighttypes.infra.JpaFlightTypeRepository}.
 *
 * <p>FlightType is tenant-scoped via Hibernate's {@code @TenantId}
 * discriminator on {@code FlightType.operatingClubId} (S-159 / S-013). The
 * discriminator rides on every read + write query automatically; the service
 * layer trusts it and adds only role-within-tenant checks at the controller.
 *
 * <p>Soft-delete (V3 {@code deleted_on}) is filtered at the query layer.
 */
public interface FlightTypeRepository {

    List<FlightType> findAllActive();

    Optional<FlightType> findActiveById(UUID id);

    Optional<FlightType> findActiveByName(String name);

    FlightType save(FlightType flightType);

    /** Flushes the persistence context — used to surface DB-side UNIQUE races synchronously. */
    void flush();
}
