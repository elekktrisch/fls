package ch.alpenflight.reservations.infra;

import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
