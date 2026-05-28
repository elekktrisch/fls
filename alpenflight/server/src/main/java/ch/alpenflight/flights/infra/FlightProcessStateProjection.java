package ch.alpenflight.flights.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lightweight {@code id + code} projection over the {@code
 * flight_process_state} reference table. S-058 only needs lookup-by-code at
 * startup ({@code FlightInitialState} caches {@code not_processed}); a full
 * reference-data port (admin CRUD, descriptions, legacy_int_id round-trip)
 * is deferred until a downstream story requires it.
 */
@Entity
@Table(name = "t_flight_process_state")
class FlightProcessStateProjection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, updatable = false)
    private @Nullable String code;

    protected FlightProcessStateProjection() {
        // JPA.
    }

    @Nullable UUID getId() {
        return id;
    }

    @Nullable String getCode() {
        return code;
    }
}
