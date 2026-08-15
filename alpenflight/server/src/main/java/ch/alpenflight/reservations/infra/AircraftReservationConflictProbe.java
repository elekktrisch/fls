package ch.alpenflight.reservations.infra;

import ch.alpenflight.reservations.domain.AircraftReservationRepository.Range;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

interface AircraftReservationConflictProbe {

    boolean existsActiveConflict(UUID aircraftId, Range window, @Nullable UUID excludeId);
}
