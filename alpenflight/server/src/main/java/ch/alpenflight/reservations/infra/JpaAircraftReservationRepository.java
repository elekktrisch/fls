package ch.alpenflight.reservations.infra;

import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA implementation of the {@link AircraftReservationRepository}
 * domain port (J-5 T-04). Extends the port, {@code JpaRepository
 * <AircraftReservation, UUID>}, and the {@link AircraftReservationConflictProbe}
 * custom fragment (so Spring Data composes the GiST native query, which needs an
 * injected tenant resolver a default method can't reach) — the application layer
 * depends only on the port (ADR 0023).
 *
 * <p><strong>Tenancy.</strong> {@code t_aircraft_reservation} is tenant-scoped
 * via Hibernate's {@code @TenantId} discriminator on {@code operating_club_id}
 * (ADR 0008). The JPQL list/find queries inherit the tenant predicate
 * automatically. {@link #existsActiveConflict} is the exception: it is native
 * SQL (the Postgres {@code &&} range-overlap operator against the generated
 * {@code reservation_range tstzrange} column has no JPQL form), so the tenant
 * discriminator does NOT apply — the fragment carries an explicit
 * {@code operating_club_id = ?} predicate and is registered in
 * {@code native-sql-register.md} ({@code NativeSqlRegisterTest} gate).
 *
 * <p><strong>Soft-delete.</strong> Every query filters {@code deleted_on IS
 * NULL}; the conflict probe additionally rides the partial GiST index
 * {@code ix_arv_aircraft_range_gist} on {@code (aircraft_id, reservation_range)
 * WHERE deleted_on IS NULL}.
 *
 * <p>List rows are flat projection DTOs (the SPA decorates display labels from
 * picker payloads) — no cross-module joins, mirroring the Flight/Aircraft
 * {@code ListRow} convention.
 */
public interface JpaAircraftReservationRepository
        extends JpaRepository<AircraftReservation, UUID>,
                AircraftReservationRepository,
                AircraftReservationConflictProbe {

    @Override
    @Query("select new ch.alpenflight.reservations.domain.AircraftReservationRepository$ListRow("
            + "r.id, r.aircraftId, r.reservationStart, r.reservationEnd, r.allDay, "
            + "r.pilotPersonId, r.secondCrewPersonId, r.locationId, "
            + "r.reservationTypeId, r.flightTypeId, r.info) "
            + "from AircraftReservation r "
            + "where r.deletedOn is null "
            + "order by r.reservationStart asc")
    List<AircraftReservationRepository.ListRow> findAllActiveListRows();

    @Override
    default List<AircraftReservationRepository.ListRow> findActiveListRowsByAircraftInRange(
            UUID aircraftId, Range window) {
        return findActiveListRowsByAircraftInRange(aircraftId, window.start(), window.end());
    }

    /**
     * Half-open overlap on a single aircraft: a row overlaps {@code [start,end)}
     * iff {@code reservationStart < end && start < reservationEnd}. JPQL
     * (tenant-filtered) — no native range type needed for the list view; the
     * GiST native probe is reserved for the hot conflict check.
     */
    @Query("select new ch.alpenflight.reservations.domain.AircraftReservationRepository$ListRow("
            + "r.id, r.aircraftId, r.reservationStart, r.reservationEnd, r.allDay, "
            + "r.pilotPersonId, r.secondCrewPersonId, r.locationId, "
            + "r.reservationTypeId, r.flightTypeId, r.info) "
            + "from AircraftReservation r "
            + "where r.deletedOn is null and r.aircraftId = :aircraftId "
            + "and r.reservationStart < :end and :start < r.reservationEnd "
            + "order by r.reservationStart asc")
    List<AircraftReservationRepository.ListRow> findActiveListRowsByAircraftInRange(
            @Param("aircraftId") UUID aircraftId,
            @Param("start") Instant windowStart,
            @Param("end") Instant windowEnd);

    // ----- T-06: paged-list + future/day overview projections -----
    //
    // The enriched ListItemRow left-joins AircraftReservationType (same module)
    // for the type name; cross-module labels stay client-decorated (ADR 0023).
    // Sort direction can't be parameterised in JPQL, so asc/desc are two methods
    // selected by a default-method dispatcher. The optional [from,to) window is
    // applied with sentinel bounds (Instant.MIN/MAX) when a bound is absent —
    // a single typed query, avoiding the Hibernate-6 `:param is null` type-
    // inference pitfall on an Instant parameter.

    Instant MIN_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59Z");

    /** Base projection + tenant-implicit (@TenantId) + soft-delete filter; callers append their window. */
    String LIST_ITEM_SELECT =
            "select new ch.alpenflight.reservations.domain.AircraftReservationRepository$ListItemRow("
                    + "r.id, r.aircraftId, r.reservationStart, r.reservationEnd, r.allDay, "
                    + "r.pilotPersonId, r.secondCrewPersonId, r.locationId, "
                    + "r.reservationTypeId, t.reservationTypeName, r.flightTypeId, r.info) "
                    + "from AircraftReservation r "
                    + "left join AircraftReservationType t on t.id = r.reservationTypeId "
                    + "where r.deletedOn is null ";

    /** Half-open [from,to) window on the reservation start, appended by the paged-list queries. */
    String LIST_ITEM_WINDOW = "and r.reservationStart >= :from and r.reservationStart < :to ";

    @Override
    default List<AircraftReservationRepository.ListItemRow> findActiveListPage(
            @Nullable Instant from, @Nullable Instant to,
            boolean ascending, int pageStart, int pageSize) {
        Instant lo = from == null ? MIN_INSTANT : from;
        Instant hi = to == null ? MAX_INSTANT : to;
        var page = org.springframework.data.domain.PageRequest.of(
                pageSize <= 0 ? 0 : pageStart / pageSize, Math.max(pageSize, 1));
        return ascending
                ? findActiveListPageAsc(lo, hi, page)
                : findActiveListPageDesc(lo, hi, page);
    }

    @Query(LIST_ITEM_SELECT + LIST_ITEM_WINDOW + "order by r.reservationStart asc, r.id asc")
    List<AircraftReservationRepository.ListItemRow> findActiveListPageAsc(
            @Param("from") Instant from,
            @Param("to") Instant to,
            org.springframework.data.domain.Pageable pageable);

    @Query(LIST_ITEM_SELECT + LIST_ITEM_WINDOW + "order by r.reservationStart desc, r.id desc")
    List<AircraftReservationRepository.ListItemRow> findActiveListPageDesc(
            @Param("from") Instant from,
            @Param("to") Instant to,
            org.springframework.data.domain.Pageable pageable);

    @Override
    default long countActiveList(@Nullable Instant from, @Nullable Instant to) {
        return countActiveListBetween(from == null ? MIN_INSTANT : from,
                to == null ? MAX_INSTANT : to);
    }

    @Query("select count(r) from AircraftReservation r where r.deletedOn is null "
            + "and r.reservationStart >= :from and r.reservationStart < :to")
    long countActiveListBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Override
    @Query(LIST_ITEM_SELECT + "and r.reservationStart >= :asOf "
            + "order by r.reservationStart asc, r.id asc")
    List<AircraftReservationRepository.ListItemRow> findFutureListRows(@Param("asOf") Instant asOf);

    @Override
    @Query("select new ch.alpenflight.reservations.domain.AircraftReservationRepository$ListItemRow("
            + "r.id, r.aircraftId, r.reservationStart, r.reservationEnd, r.allDay, "
            + "r.pilotPersonId, r.secondCrewPersonId, r.locationId, "
            + "r.reservationTypeId, t.reservationTypeName, r.flightTypeId, r.info) "
            + "from AircraftReservation r "
            + "left join AircraftReservationType t on t.id = r.reservationTypeId "
            + "where r.deletedOn is null "
            + "and r.reservationStart < :dayEnd and :dayStart < r.reservationEnd "
            + "order by r.reservationStart asc, r.id asc")
    List<AircraftReservationRepository.ListItemRow> findActiveListRowsForDay(
            @Param("dayStart") Instant dayStart, @Param("dayEnd") Instant dayEnd);

    @Override
    @Query("select r from AircraftReservation r where r.id = :id and r.deletedOn is null")
    Optional<AircraftReservation> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select new ch.alpenflight.reservations.domain.AircraftReservationRepository$TypeListItem("
            + "t.id, t.reservationTypeName, t.active) "
            + "from AircraftReservationType t "
            + "where t.deletedOn is null "
            + "order by t.reservationTypeName asc")
    List<AircraftReservationRepository.TypeListItem> findActiveTypeListItems();
}
