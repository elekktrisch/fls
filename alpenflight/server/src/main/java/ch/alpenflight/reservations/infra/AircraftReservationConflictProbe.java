package ch.alpenflight.reservations.infra;

import ch.alpenflight.reservations.domain.AircraftReservationRepository.Range;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Spring Data custom-fragment seam for the one query that can't be a derived /
 * {@code @Query} method: the GiST range-overlap conflict probe. Implemented by
 * {@link AircraftReservationConflictProbeImpl}, which injects the tenant
 * resolver + {@code EntityManager} to bind the explicit
 * {@code operating_club_id} predicate the native query needs (Hibernate's
 * {@code @TenantId} discriminator does not apply to native SQL).
 *
 * <p>Re-declares {@code existsActiveConflict} (also on the domain port
 * {@link ch.alpenflight.reservations.domain.AircraftReservationRepository}) so
 * the Spring Data composition routes that method to the {@code Impl} fragment
 * rather than treating it as a derived query.
 */
interface AircraftReservationConflictProbe {

    boolean existsActiveConflict(UUID aircraftId, Range window, @Nullable UUID excludeId);
}
