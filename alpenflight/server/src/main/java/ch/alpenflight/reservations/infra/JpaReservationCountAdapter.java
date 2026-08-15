package ch.alpenflight.reservations.infra;

import ch.alpenflight.reservations.api.ReservationCountPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
class JpaReservationCountAdapter implements ReservationCountPort {

    private final JpaAircraftReservationRepository reservations;

    JpaReservationCountAdapter(JpaAircraftReservationRepository reservations) {
        this.reservations = reservations;
    }

    @Override
    public long countActiveOnDateAtLocation(LocalDate date, UUID locationId) {
        Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return reservations.countActiveOnDayAtLocation(dayStart, dayEnd, locationId);
    }
}
