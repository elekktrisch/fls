package ch.alpenflight.reservations.infra;

import ch.alpenflight.reservations.domain.AircraftReservationType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAircraftReservationTypeRepository
        extends JpaRepository<AircraftReservationType, UUID> {
}
