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

    @Column(name = "required_nr_of_assignments", nullable = false)
    private short requiredNrOfAssignments = 1;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    protected PlanningDayAssignmentType() {
    }

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
