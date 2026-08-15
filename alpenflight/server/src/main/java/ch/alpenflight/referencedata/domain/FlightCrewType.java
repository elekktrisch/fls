package ch.alpenflight.referencedata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_flight_crew_type")
public class FlightCrewType {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 48)
    private String code = "";

    @Column(name = "legacy_int_id", nullable = false)
    private short legacyIntId;

    @Column(name = "description", nullable = false, length = 200)
    private String description = "";

    protected FlightCrewType() {
    }

    public @Nullable UUID getId() {
        return id;
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
}
