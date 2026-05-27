package ch.alpenflight.referencedata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Counter unit catalog (HOURS_DECIMAL, HOURS_MINUTES, LANDINGS, STARTS).
 * System-global; not tenant-scoped. Mapped to the V2 {@code counter_unit_type}
 * table; data lives in the V2 Flyway seed. Read-only.
 *
 * <p>Referenced by {@code aircraft.flight_operating_counter_unit_type_id} +
 * {@code aircraft.engine_operating_counter_unit_type_id} — the Add Aircraft
 * form needs the catalog as dropdown fuel.
 */
@Entity
@Table(name = "t_counter_unit_type")
public class CounterUnitType {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 32)
    private String code = "";

    @Column(name = "name", nullable = false, length = 50)
    private String name = "";

    @Column(name = "short_name", length = 20)
    private @Nullable String shortName;

    @Column(name = "comment", length = 200)
    private @Nullable String comment;

    protected CounterUnitType() {
        // JPA.
    }

    public @Nullable UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public @Nullable String getShortName() {
        return shortName;
    }

    public @Nullable String getComment() {
        return comment;
    }
}
