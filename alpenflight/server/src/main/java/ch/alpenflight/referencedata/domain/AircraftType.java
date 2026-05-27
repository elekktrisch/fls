package ch.alpenflight.referencedata.domain;

import ch.alpenflight.platform.id.AircraftTypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Aircraft type catalog (GLIDER, GLIDER_WITH_MOTOR, MOTOR_GLIDER,
 * MOTOR_AIRCRAFT, MULTI_ENGINE, JET, HELICOPTER, UNKNOWN). System-global;
 * not tenant-scoped. Mapped to the V3 {@code t_aircraft_type} table; data
 * lives in the V3 Flyway seed. Read-only.
 *
 * <p>{@code hasEngine}, {@code requiresTowingInfo}, {@code mayBeTowingAircraft}
 * carry the type-discriminator boolean shape the new code uses for the
 * glider / tow / motor list-filter slices (replaces legacy bitmask-int
 * comparisons against the {@code AircraftTypeId} enum).
 */
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
        // JPA.
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
