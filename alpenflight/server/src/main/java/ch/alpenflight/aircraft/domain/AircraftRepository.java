package ch.alpenflight.aircraft.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for {@link Aircraft} persistence. Implemented by
 * {@code ch.alpenflight.aircraft.infra.JpaAircraftRepository}.
 *
 * <p>Aircraft is tenant-scoped via Hibernate's {@code @TenantId}
 * discriminator on {@code Aircraft.managingClubId} (S-159). The
 * discriminator rides on every read + write query automatically; the
 * service layer trusts it and adds only role-within-tenant checks at the
 * controller.
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

    List<ListRow> findActiveListRowsByTypeCodeIn(java.util.Set<String> typeCodes);

    List<ListRow> findActiveTowingListRows();

    List<PickerRow> findAllActivePickerRows();

    Optional<Aircraft> findActiveById(UUID id);

    Optional<Aircraft> findActiveByImmatriculation(String normalizedImmatriculation);

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
