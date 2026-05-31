package ch.alpenflight.users.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * AlpenFlight realm-role catalogue. Wire-format is the string name.
 *
 * <p>Single source of truth for both the backend (replaces the duplicate
 * {@code MeService.KNOWN_REALM_ROLES} set) and the SPA's {@code AppRole}
 * union — the OpenAPI snapshot projects {@code Role} as a string enum.
 *
 * <p>Built-in Keycloak roles ({@code uma_authorization}, {@code offline_access},
 * {@code default-roles-alpenflight}) and the {@code proffix-sync} client role
 * are deliberately NOT in this enum — they are realm plumbing, not
 * application authority. The wire boundary strips them out.
 */
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

    /** Returns the matching role, or {@code null} for unknown / built-in names. */
    public static @Nullable Role fromWire(@Nullable String name) {
        if (name == null || !WIRE_NAMES.contains(name)) {
            return null;
        }
        return Role.valueOf(name);
    }

    /** {@code true} iff {@code name} is one of the application realm roles. */
    public static boolean isKnown(@Nullable String name) {
        return name != null && WIRE_NAMES.contains(name);
    }

    public static Set<String> knownNames() {
        return WIRE_NAMES;
    }
}
