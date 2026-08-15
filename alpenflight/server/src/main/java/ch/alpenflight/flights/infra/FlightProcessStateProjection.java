package ch.alpenflight.flights.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_flight_process_state")
class FlightProcessStateProjection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, updatable = false)
    private @Nullable String code;

    protected FlightProcessStateProjection() {
    }

    @Nullable UUID getId() {
        return id;
    }

    @Nullable String getCode() {
        return code;
    }
}
