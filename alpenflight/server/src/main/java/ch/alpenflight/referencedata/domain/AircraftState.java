package ch.alpenflight.referencedata.domain;

import ch.alpenflight.platform.id.AircraftStateId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Aircraft state catalog (OK, INFORMATION, ATTENTION, MALFUNCTION,
 * MAINTENANCE, UNINSURED, END_OF_LIFE). System-global; not tenant-scoped.
 * Mapped to the V3 {@code aircraft_state} table; data lives in the V3
 * Flyway seed. Read-only.
 *
 * <p>{@code isAircraftFlyable} drives the "list flyable aircraft" filter
 * on the picker endpoint and the airworthiness badge on the list view.
 */
@Entity
@Table(name = "aircraft_state")
public class AircraftState {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 32)
    private String code = "";

    @Column(name = "legacy_int_id", nullable = false)
    private short legacyIntId;

    @Column(name = "description", nullable = false, length = 200)
    private String description = "";

    @Column(name = "is_aircraft_flyable", nullable = false)
    private boolean isAircraftFlyable;

    protected AircraftState() {
        // JPA.
    }

    public @Nullable AircraftStateId getId() {
        return AircraftStateId.ofNullable(id);
    }

    public String getCode() {
        return code;
    }

    public short getLegacyIntId() {
        return legacyIntId;
    }

    public String getDescription() {
        return description;
    }

    public boolean isAircraftFlyable() {
        return isAircraftFlyable;
    }
}
