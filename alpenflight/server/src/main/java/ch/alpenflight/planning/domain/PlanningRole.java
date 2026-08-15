package ch.alpenflight.planning.domain;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

public enum PlanningRole {
    // ext: legacy assignment_type_name values (migrated rows + club-creation seed)
    FLIGHT_OPERATOR("segelflugleiter"),
    TOWING_PILOT("schlepppilot"),
    INSTRUCTOR("fluglehrer");

    private final String typeName;

    PlanningRole(String typeName) {
        this.typeName = typeName;
    }

    public String typeName() {
        return typeName;
    }

    public boolean matches(@Nullable String assignmentTypeName) {
        if (assignmentTypeName == null) {
            return false;
        }
        return typeName.equals(assignmentTypeName.strip().toLowerCase(Locale.ROOT));
    }

    public static @Nullable PlanningRole fromTypeName(@Nullable String assignmentTypeName) {
        if (assignmentTypeName == null) {
            return null;
        }
        for (PlanningRole role : values()) {
            if (role.matches(assignmentTypeName)) {
                return role;
            }
        }
        return null;
    }
}
