package ch.alpenflight.reservations.infra;

import ch.alpenflight.reservations.domain.AircraftReservationType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the tenant-scoped
 * {@link AircraftReservationType} lookup aggregate (J-5).
 *
 * <p>The reservation type's read surface (the
 * {@code /aircraftreservationtypes/listitems} dropdown) is served by a
 * projection query on {@link JpaAircraftReservationRepository}, and the type is
 * migration-populated (no create API yet — deferred to a future masterdata
 * journey). This plain {@code JpaRepository} exists so the type aggregate has a
 * discoverable Spring Data binding the way every other {@code @TenantId}
 * aggregate does: the S-024 leakage sweep ({@code LeakageSweepIT}) requires one
 * per tenant-scoped entity to drive its create-as-A / invisible-to-B /
 * NO_TENANT-sentinel-fails assertions.
 *
 * <p>Hibernate's {@code @TenantId} on
 * {@link AircraftReservationType#getOperatingClubId()} scopes the inherited
 * {@link JpaRepository#findAll()} / {@link JpaRepository#findById} to the
 * caller's tenant automatically — no explicit predicate needed.
 */
public interface JpaAircraftReservationTypeRepository
        extends JpaRepository<AircraftReservationType, UUID> {
}
