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


    Instant MIN_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59Z");

    String LIST_ITEM_SELECT =
            "select new ch.alpenflight.reservations.domain.AircraftReservationRepository$ListItemRow("
                    + "r.id, r.aircraftId, r.reservationStart, r.reservationEnd, r.allDay, "
                    + "r.pilotPersonId, r.secondCrewPersonId, r.locationId, "
                    + "r.reservationTypeId, t.reservationTypeName, r.flightTypeId, r.info) "
                    + "from AircraftReservation r "
                    + "left join AircraftReservationType t on t.id = r.reservationTypeId "
                    + "where r.deletedOn is null ";

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
            + "t.id, t.reservationTypeName, t.active, t.instructorRequired) "
            + "from AircraftReservationType t "
            + "where t.deletedOn is null "
            + "order by t.reservationTypeName asc")
    List<AircraftReservationRepository.TypeListItem> findActiveTypeListItems();

    @Query("select count(r) from AircraftReservation r "
            + "where r.deletedOn is null "
            + "and r.locationId = :locationId "
            + "and r.reservationStart >= :dayStart and r.reservationStart < :dayEnd")
    long countActiveOnDayAtLocation(@Param("dayStart") Instant dayStart,
                                    @Param("dayEnd") Instant dayEnd,
                                    @Param("locationId") UUID locationId);
}
