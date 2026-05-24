package ch.alpenflight.flights.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lightweight {@code id + code} projection over the {@code flight_air_state}
 * reference table. Mirror of {@link FlightProcessStateProjection}; same
 * "deferred full port" rationale.
 */
@Entity
@Table(name = "flight_air_state")
class FlightAirStateProjection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, updatable = false)
    private @Nullable String code;

    protected FlightAirStateProjection() {
        // JPA.
    }

    @Nullable UUID getId() {
        return id;
    }

    @Nullable String getCode() {
        return code;
    }
}
