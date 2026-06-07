package ch.alpenflight.planning.domain;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The three well-known planning-day crew roles the legacy UI surfaces as fixed
 * person pickers. Each maps to a per-club {@code PlanningDayAssignmentType}
 * whose {@code assignment_type_name} is the German term legacy keys on
 * (case-insensitive — {@code MappingExtensions.cs:3302/3325/3348}):
 *
 * <ul>
 *   <li>{@link #FLIGHT_OPERATOR} ↔ {@code segelflugleiter}</li>
 *   <li>{@link #TOWING_PILOT} ↔ {@code schlepppilot}</li>
 *   <li>{@link #INSTRUCTOR} ↔ {@code fluglehrer}</li>
 * </ul>
 *
 * <p>Resolution is name-based (not id-based) because the types are per-club
 * rows — a migrated club brings its own type ids, but the names are stable.
 */
public enum PlanningRole {
    FLIGHT_OPERATOR("segelflugleiter"),
    TOWING_PILOT("schlepppilot"),
    INSTRUCTOR("fluglehrer");

    private final String typeName;

    PlanningRole(String typeName) {
        this.typeName = typeName;
    }

    /**
     * The well-known assignment-type name (lower-case German) this role binds
     * to. The match against a {@code PlanningDayAssignmentType} is
     * case-insensitive — see {@link #matches(String)}.
     */
    public String typeName() {
        return typeName;
    }

    /**
     * Whether {@code assignmentTypeName} names this role, case-insensitively
     * (mirroring legacy's {@code AssignmentTypeName.ToLower() == "…"}). A
     * {@code null} or blank name matches nothing.
     */
    public boolean matches(@Nullable String assignmentTypeName) {
        if (assignmentTypeName == null) {
            return false;
        }
        return typeName.equals(assignmentTypeName.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves the well-known role an assignment-type name denotes, or
     * {@code null} when the name is not one of the three (a non-role club type,
     * which this journey does not surface).
     */
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
