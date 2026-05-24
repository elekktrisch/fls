package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Typed identifier for the {@code FlightCostBalanceType} aggregate root.
 * Wraps a {@link UUID} so service / controller / DTO signatures cannot
 * accidentally accept a {@code FlightType} or other UUID in an FCBT slot.
 *
 * <p>External form is {@code fcb-<uuid>} per ADR 0019. The 4-char prefix
 * (vs. the usual 3-char convention) disambiguates FCBT from
 * {@link FlightTypeId} {@code ft-} so an `f`-prefix typo in a payload is
 * caught at the boundary.
 */
@Schema(
        type = "string",
        pattern = "^fcb-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "fcb-019e2e15-2c00-7268-8000-000000004268")
public record FlightCostBalanceTypeId(UUID value) {

    public static final String PREFIX = "fcb-";

    public FlightCostBalanceTypeId {
        if (value == null) {
            throw new IllegalArgumentException("FlightCostBalanceTypeId value must not be null");
        }
    }

    public static FlightCostBalanceTypeId of(UUID value) {
        return new FlightCostBalanceTypeId(value);
    }

    public static @Nullable FlightCostBalanceTypeId ofNullable(@Nullable UUID value) {
        return value == null ? null : new FlightCostBalanceTypeId(value);
    }

    public static FlightCostBalanceTypeId parse(String external) {
        if (external == null) {
            throw new IllegalArgumentException("FlightCostBalanceTypeId external form must not be null");
        }
        if (!external.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "FlightCostBalanceTypeId external form must start with '" + PREFIX + "', got: " + external);
        }
        String payload = external.substring(PREFIX.length());
        try {
            return new FlightCostBalanceTypeId(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "FlightCostBalanceTypeId payload '" + payload + "' is not a valid UUID", e);
        }
    }

    public String toExternal() {
        return PREFIX + value.toString();
    }

    @Override
    public String toString() {
        return toExternal();
    }
}
