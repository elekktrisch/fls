package ch.alpenflight.flights.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Lightweight {@code id + code} projection over the {@code t_start_type}
 * reference table ({@link FlightProcessStateProjection} precedent). RM-1 only
 * needs code-by-id for the flight-report read-model's denormalized
 * {@code start_type_code} column; a full start-type port (admin CRUD, names,
 * applicable categories) is deferred until a downstream story requires it.
 */
@Entity
@Table(name = "t_start_type")
class StartTypeProjection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, updatable = false)
    private @Nullable String code;

    protected StartTypeProjection() {
        // JPA.
    }

    @Nullable UUID getId() {
        return id;
    }

    @Nullable String getCode() {
        return code;
    }
}
