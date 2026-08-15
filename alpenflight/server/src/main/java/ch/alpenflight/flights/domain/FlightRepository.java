package ch.alpenflight.flights.domain;

import ch.alpenflight.platform.id.FlightId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface FlightRepository {

    record ListRow(UUID id,
                   FlightAircraftType flightAircraftType,
                   @Nullable LocalDate flightDate,
                   @Nullable Instant startDateTime,
                   @Nullable Instant ldgDateTime,
                   UUID aircraftId,
                   UUID processStateId,
                   long version,
                   boolean noStartTimeInformation,
                   boolean noLdgTimeInformation,
                   @Nullable Instant flightPlanOpenedOn) {

        public FlightAirState airState() {
            return FlightAirState.compute(ldgDateTime, startDateTime,
                    noLdgTimeInformation, noStartTimeInformation, flightPlanOpenedOn);
        }
    }

    Flight save(Flight flight);

    Optional<Flight> findByIdWithCrew(FlightId id);

    List<ListRow> findListWindow(@Nullable LocalDate from,
                                 @Nullable LocalDate to,
                                 @Nullable LocalDate cursorFlightDate,
                                 @Nullable UUID cursorId,
                                 int limit,
                                 @Nullable UUID personId);

    List<Flight> findByTowFlightId(FlightId towFlightId);

    List<Flight> findByProcessStateId(UUID processStateId);

    List<Flight> findUnreported();

    long countByFlightDate(LocalDate flightDate);

    long countAll();

    long countByProcessStateIdIn(Collection<UUID> processStateIds);

    Optional<Flight> findLastByAircraftAndDate(UUID aircraftId, LocalDate flightDate);

    List<UUID> findAllLiveIds();

    List<UUID> findIdsByAircraftId(UUID aircraftId);

    List<UUID> findIdsByLocationId(UUID locationId);

    List<UUID> findIdsByFlightTypeId(UUID flightTypeId);
}
