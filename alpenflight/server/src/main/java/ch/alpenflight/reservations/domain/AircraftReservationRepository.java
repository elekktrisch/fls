package ch.alpenflight.reservations.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface AircraftReservationRepository {

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

    record ListItemRow(UUID id,
                       UUID aircraftId,
                       Instant reservationStart,
                       Instant reservationEnd,
                       boolean allDay,
                       UUID pilotPersonId,
                       @Nullable UUID secondCrewPersonId,
                       UUID locationId,
                       @Nullable UUID reservationTypeId,
                       @Nullable String reservationTypeName,
                       @Nullable UUID flightTypeId,
                       @Nullable String info) {}

    record TypeListItem(UUID id, String name, boolean active, boolean instructorRequired) {}

    AircraftReservation save(AircraftReservation reservation);

    Optional<AircraftReservation> findActiveById(UUID id);

    List<ListRow> findAllActiveListRows();

    List<ListRow> findActiveListRowsByAircraftInRange(UUID aircraftId, Range window);

    boolean existsActiveConflict(UUID aircraftId, Range window, @Nullable UUID excludeId);

    List<ListItemRow> findActiveListPage(@Nullable Instant from,
                                         @Nullable Instant to,
                                         boolean ascending,
                                         int pageStart,
                                         int pageSize);

    long countActiveList(@Nullable Instant from, @Nullable Instant to);

    List<ListItemRow> findFutureListRows(Instant asOf);

    List<ListItemRow> findActiveListRowsForDay(Instant dayStart, Instant dayEnd);

    List<TypeListItem> findActiveTypeListItems();

    void flush();
}
