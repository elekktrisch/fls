package ch.alpenflight.aircraft.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for {@link Aircraft} persistence. Implemented by
 * {@code ch.alpenflight.aircraft.infra.JpaAircraftRepository}.
 *
 * <p>Aircraft is cross-tenant — no {@code @TenantId} discriminator filter
 * rides on these queries. Tenancy / authz is enforced at the application
 * service layer via {@code AircraftAccess}, not Hibernate.
 *
 * <p>Soft-delete (V3 {@code deleted_on}) is filtered at the query layer.
 */
public interface AircraftRepository {

    /**
     * Projection row for the {@code GET /api/v1/aircraft} list view. Carries
     * the AircraftType code + has_engine boolean + (optional) current
     * AircraftState code/flyability so the list serves in a single SQL
     * round-trip.
     */
    record ListRow(UUID id,
                   @Nullable UUID ownerClubId,
                   String immatriculation,
                   @Nullable String competitionSign,
                   UUID aircraftTypeId,
                   String aircraftTypeCode,
                   @Nullable Boolean aircraftTypeHasEngine,
                   boolean towingAircraft,
                   @Nullable String currentStateCode,
                   @Nullable Boolean currentStateFlyable) {}

    /**
     * Projection row for the slim {@code GET /api/v1/aircraft/picker}
     * endpoint hit by every Flight / Reservation create form.
     */
    record PickerRow(UUID id,
                     String immatriculation,
                     UUID aircraftTypeId,
                     boolean towingAircraft) {}

    List<ListRow> findAllActiveListRows();

    List<PickerRow> findAllActivePickerRows();

    Optional<Aircraft> findActiveById(UUID id);

    Optional<Aircraft> findActiveByImmatriculation(String normalizedImmatriculation);

    Aircraft save(Aircraft aircraft);

    /**
     * Save + flush. Used after state-change / counter-record so the
     * cascaded child entity's generated UUID is populated before the
     * controller maps the response.
     */
    Aircraft saveAndFlush(Aircraft aircraft);
}
