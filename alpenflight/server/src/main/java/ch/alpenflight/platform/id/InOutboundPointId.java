package ch.alpenflight.platform.id;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(
        type = "string",
        format = "uuid",
        pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        example = "019e30c3-2c00-7001-8000-0000000000aa")
public record InOutboundPointId(UUID value) {

    public InOutboundPointId {
        if (value == null) {
            throw new IllegalArgumentException("InOutboundPointId value must not be null");
        }
    }

    public static InOutboundPointId of(UUID value) {
        return new InOutboundPointId(value);
    }

    public static @Nullable InOutboundPointId ofNullable(@Nullable UUID value) {
        return value == null ? null : new InOutboundPointId(value);
    }

    public static InOutboundPointId parse(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("InOutboundPointId wire form must not be null");
        }
        try {
            return new InOutboundPointId(UUID.fromString(wire));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "InOutboundPointId payload '" + wire + "' is not a valid UUID", e);
        }
    }

    public String toWire() {
        return value.toString();
    }

    @Override
    public String toString() {
        return toWire();
    }
}
