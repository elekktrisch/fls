package ch.alpenflight.reservations.api;

import java.time.LocalDate;
import java.util.UUID;

public interface ReservationCountPort {

    long countActiveOnDateAtLocation(LocalDate date, UUID locationId);
}
