package ch.alpenflight.referencedata.domain;

import ch.alpenflight.platform.id.AircraftTypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_aircraft_type")
public class AircraftType {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 32)
    private String code = "";

    @Column(name = "legacy_int_id", nullable = false)
    private short legacyIntId;

    @Column(name = "description", nullable = false, length = 200)
    private String description = "";

    @Column(name = "has_engine")
    private @Nullable Boolean hasEngine;

    @Column(name = "requires_towing_info")
    private @Nullable Boolean requiresTowingInfo;

    @Column(name = "may_be_towing_aircraft")
    private @Nullable Boolean mayBeTowingAircraft;

    protected AircraftType() {
    }

    public @Nullable AircraftTypeId getId() {
        return AircraftTypeId.ofNullable(id);
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

    public @Nullable Boolean getHasEngine() {
        return hasEngine;
    }

    public @Nullable Boolean getRequiresTowingInfo() {
        return requiresTowingInfo;
    }

    public @Nullable Boolean getMayBeTowingAircraft() {
        return mayBeTowingAircraft;
    }
}
