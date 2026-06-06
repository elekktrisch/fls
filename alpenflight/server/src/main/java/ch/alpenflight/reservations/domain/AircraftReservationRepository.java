package ch.alpenflight.reservations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for {@link AircraftReservation} persistence. Implemented by
 * {@code ch.alpenflight.reservations.infra.JpaAircraftReservationRepository}
 * (T-04).
 *
 * <p>Reservations are tenant-scoped via Hibernate's {@code @TenantId}
 * discriminator on {@code operating_club_id} (ADR 0008); every read + write
 * query carries the tenant predicate automatically, so cross-tenant ids are
 * invisible. The {@code aircraft_id} FK still crosses tenants freely (any
 * operating club may reserve any aircraft — legacy-open parity).
 *
 * <p>Soft-delete ({@code deleted_on}) is filtered at the query layer.
 */
public interface AircraftReservationRepository {

    /**
     * Half-open time window {@code [start, end)} for the conflict probe —
     * mirrors the V4 {@code reservation_range tstzrange(...,'[)')}. T-04
     * binds this to the GiST {@code &&} range-overlap operator. Callers pass
     * the reservation's <em>effective</em> span
     * ({@link AircraftReservation#effectiveStart()} /
     * {@link AircraftReservation#effectiveEnd()}).
     */
    record Range(Instant start, Instant end) {
        public Range {
            if (start == null || end == null) {
                throw new IllegalArgumentException("range bounds must not be null");
            }
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException(
                        "range end (" + end + ") must be strictly after start (" + start + ")");
            }
        }
    }

    /**
     * Projection row for the {@code /api/v1/aircraftreservations} list + paged
     * list + scheduler views. Carries the FK ids the SPA needs to render a row
     * (aircraft lane, pilot, location, type) in a single SQL round-trip; the
     * web layer decorates display labels from the picker payloads (mirrors the
     * Flight {@code ListRow} no-cross-module-join convention, ADR 0023).
     */
    record ListRow(UUID id,
                   UUID aircraftId,
                   Instant reservationStart,
                   Instant reservationEnd,
                   boolean allDay,
                   UUID pilotPersonId,
                   @Nullable UUID secondCrewPersonId,
                   UUID locationId,
                   @Nullable UUID reservationTypeId,
                   @Nullable UUID flightTypeId,
                   @Nullable String info) {}

    /** Slim list-item for the {@code /aircraftreservationtypes/listitems} dropdown. */
    record TypeListItem(UUID id, String name, boolean active) {}

    AircraftReservation save(AircraftReservation reservation);

    Optional<AircraftReservation> findActiveById(UUID id);

    /**
     * Active (non-deleted) reservations within the caller's tenant. The
     * scheduler / list views consume this; ordering + windowing refinements
     * land with the paged-list endpoint (T-06).
     */
    List<ListRow> findAllActiveListRows();

    /**
     * Active reservations overlapping {@code window} on the given aircraft,
     * within the caller's tenant — backs the day/range scheduler view.
     */
    List<ListRow> findActiveListRowsByAircraftInRange(UUID aircraftId, Range window);

    /**
     * Conflict probe: does a non-deleted reservation on {@code aircraftId}
     * within the caller's tenant overlap {@code window} (half-open), excluding
     * {@code excludeId} (the row being edited — self-exclusion)? T-04
     * implements this as the GiST {@code &&} range-overlap query (→
     * {@code NativeSqlRegisterTest} entry, tenant-scoped table). Mirrors the
     * pure {@link AircraftReservation#conflictsWith} predicate.
     *
     * @param excludeId the id to exclude (the reservation being updated), or
     *     {@code null} on create.
     */
    boolean existsActiveConflict(UUID aircraftId, Range window, @Nullable UUID excludeId);

    /** Active reservation types within the caller's tenant, for the dropdown. */
    List<TypeListItem> findActiveTypeListItems();

    /** Flushes the persistence context (mirrors the Aircraft port convention). */
    void flush();
}
