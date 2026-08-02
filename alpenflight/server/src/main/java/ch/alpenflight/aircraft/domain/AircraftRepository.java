package ch.alpenflight.aircraft.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for {@link Aircraft} persistence. Implemented by
 * {@code ch.alpenflight.aircraft.infra.JpaAircraftRepository}.
 *
 * <p>Aircraft is cross-tenant (S-058 reversion of S-159) — queries do NOT
 * carry a {@code @TenantId} discriminator. Reads return rows from any
 * club; write authorization is enforced at the controller via the
 * {@code AircraftAccess} SpEL bean (manager-club gate on
 * {@code managing_club_id}).
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
                   @Nullable Boolean currentStateFlyable,
                   @Nullable String manufacturerName,
                   @Nullable String aircraftModel,
                   @Nullable Integer nrOfSeats) {}

    /**
     * Projection row for the slim {@code GET /api/v1/aircraft/picker}
     * endpoint hit by every Flight / Reservation create form.
     */
    record PickerRow(UUID id,
                     String immatriculation,
                     UUID aircraftTypeId,
                     boolean towingAircraft,
                     @Nullable Integer nrOfSeats) {}

    List<ListRow> findAllActiveListRows();

    List<ListRow> findActiveListRowsByTypeCodeIn(java.util.Set<String> typeCodes);

    List<ListRow> findActiveTowingListRows();

    List<PickerRow> findAllActivePickerRows();

    Optional<Aircraft> findActiveById(UUID id);

    Optional<Aircraft> findActiveByImmatriculation(String normalizedImmatriculation);

    /**
     * Every live aircraft, immatriculation-ordered — the scan side of the OGN
     * device-database sync, which matches our fleet against the downloaded
     * registry rather than the other way round.
     */
    List<Aircraft> findAllActive();

    Aircraft save(Aircraft aircraft);

    /**
     * Flushes the persistence context. Used after {@code changeState} /
     * {@code recordCounter} so cascade-PERSIST runs against the still-managed
     * parent and the new child entity's generated UUID is populated in place
     * (the service holds the original transient reference). Calling
     * {@code save} on a managed parent would route through {@code em.merge},
     * which cascades by copying transient children — leaving the original
     * reference unpopulated.
     */
    void flush();
}
