package ch.alpenflight.users.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public enum Role {
    SYSTEM_ADMINISTRATOR,
    CLUB_ADMINISTRATOR,
    FLIGHT_OPERATOR,
    PILOT,
    OFFICE_USER,
    GUEST;

    private static final Set<String> WIRE_NAMES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    public static @Nullable Role fromWire(@Nullable String name) {
        if (name == null || !WIRE_NAMES.contains(name)) {
            return null;
        }
        return Role.valueOf(name);
    }

    public static boolean isKnown(@Nullable String name) {
        return name != null && WIRE_NAMES.contains(name);
    }

    public static Set<String> knownNames() {
        return WIRE_NAMES;
    }
}
