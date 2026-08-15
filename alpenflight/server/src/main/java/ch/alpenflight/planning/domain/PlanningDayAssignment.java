package ch.alpenflight.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_planning_day_assignment")
public class PlanningDayAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private UUID operatingClubId = new UUID(0L, 0L);

    @ManyToOne(optional = false)
    @JoinColumn(name = "planning_day_id", nullable = false)
    @SuppressWarnings("UnusedVariable")
    private @Nullable PlanningDay planningDay;

    @Column(name = "assigned_person_id", nullable = false)
    private UUID assignedPersonId = new UUID(0L, 0L);

    @Column(name = "assignment_type_id", nullable = false)
    private UUID assignmentTypeId = new UUID(0L, 0L);

    @Column(name = "info")
    private @Nullable String info;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    protected PlanningDayAssignment() {
    }

    PlanningDayAssignment(PlanningDay planningDay,
                          UUID operatingClubId,
                          UUID assignmentTypeId,
                          UUID assignedPersonId,
                          @Nullable String info) {
        if (planningDay == null) {
            throw new IllegalArgumentException("PlanningDayAssignment.planningDay must not be null");
        }
        if (operatingClubId == null) {
            throw new IllegalArgumentException("PlanningDayAssignment.operatingClubId must not be null");
        }
        if (assignmentTypeId == null) {
            throw new IllegalArgumentException("PlanningDayAssignment.assignmentTypeId must not be null");
        }
        if (assignedPersonId == null) {
            throw new IllegalArgumentException("PlanningDayAssignment.assignedPersonId must not be null");
        }
        this.planningDay = planningDay;
        this.operatingClubId = operatingClubId;
        this.assignmentTypeId = assignmentTypeId;
        this.assignedPersonId = assignedPersonId;
        this.info = info;
    }

    void reassignTo(UUID newAssignedPersonId) {
        if (newAssignedPersonId == null) {
            throw new IllegalArgumentException("assignedPersonId must not be null");
        }
        this.assignedPersonId = newAssignedPersonId;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public UUID getOperatingClubId() {
        return operatingClubId;
    }

    public UUID getAssignedPersonId() {
        return assignedPersonId;
    }

    public UUID getAssignmentTypeId() {
        return assignmentTypeId;
    }

    public @Nullable String getInfo() {
        return info;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }
}
