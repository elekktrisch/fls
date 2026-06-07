package ch.alpenflight.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Per-club lookup naming a kind of planning-day crew assignment
 * ({@code t_planning_day_assignment_type}). The three well-known role names
 * ({@code segelflugleiter} / {@code schlepppilot} / {@code fluglehrer}) are
 * seeded per club at creation and resolve to a {@link PlanningRole}; a club may
 * carry further types (not surfaced this journey).
 *
 * <p>Tenant-scoped on {@code operating_club_id} (ADR 0008).
 *
 * <p>{@code required_nr_of_assignments} is DEAD for J-6 (always 1, never read —
 * J-6 behavior oracle). The column is mapped so JPA round-trips the row, but no
 * behavior is built on it.
 */
@Entity
@Table(name = "t_planning_day_assignment_type")
public class PlanningDayAssignmentType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "assignment_type_name", nullable = false, length = 100)
    private @Nullable String assignmentTypeName;

    /** DEAD for J-6 — mapped only so JPA round-trips the column (defaults 1). */
    @Column(name = "required_nr_of_assignments", nullable = false)
    private short requiredNrOfAssignments = 1;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    protected PlanningDayAssignmentType() {
        // JPA.
    }

    /**
     * Factory for a club's assignment-type row (used by the clean-seed loader,
     * T-06). {@code requiredNrOfAssignments} is fixed at 1 — it is dead weight
     * this journey.
     */
    public static PlanningDayAssignmentType create(UUID operatingClubId, String assignmentTypeName) {
        if (operatingClubId == null) {
            throw new IllegalArgumentException("operatingClubId must not be null");
        }
        if (assignmentTypeName == null || assignmentTypeName.isBlank()) {
            throw new IllegalArgumentException("assignmentTypeName must not be blank");
        }
        PlanningDayAssignmentType t = new PlanningDayAssignmentType();
        t.operatingClubId = operatingClubId;
        t.assignmentTypeName = assignmentTypeName.strip();
        return t;
    }

    /** The role this type denotes, or {@code null} for a non-role club type. */
    public @Nullable PlanningRole resolveRole() {
        return PlanningRole.fromTypeName(assignmentTypeName);
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public @Nullable String getAssignmentTypeName() {
        return assignmentTypeName;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }
}
