package ch.alpenflight.referencedata.domain;

import ch.alpenflight.platform.id.LocationTypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_location_type")
public class LocationType {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 32)
    private String code = "";

    @Column(name = "legacy_int_id", nullable = false)
    private short legacyIntId;

    @Column(name = "description", nullable = false, length = 200)
    private String description = "";

    @Column(name = "is_airfield", nullable = false)
    private boolean isAirfield;

    protected LocationType() {
    }

    public @Nullable LocationTypeId getId() {
        return LocationTypeId.ofNullable(id);
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

    public boolean isAirfield() {
        return isAirfield;
    }
}
