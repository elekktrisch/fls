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

/**
 * Aggregate-internal crew row under {@link PlanningDay}
 * ({@code t_planning_day_assignment}). One person assigned to one
 * {@code PlanningDayAssignmentType} for the day. Managed only via the parent's
 * {@link PlanningDay#assignRole} / {@link PlanningDay#clearRole}; never
 * persisted standalone.
 *
 * <p>Identity within the day is the {@code (assignmentTypeId, assignedPersonId)}
 * pair (the V4 {@code ux_pda_composite} partial-unique key). The parent reuses
 * an existing row for a role rather than delete-and-reinsert it — the same
 * flush-ordering reason as {@code FlightCrew} (re-INSERT before orphan DELETE
 * would trip the partial-unique index).
 *
 * <p>{@code assigned_person_id} is a cross-tenant reference (a Person may belong
 * to a different tenant).
 *
 * <p>Tenancy: the V4 table carries a NOT NULL {@code operating_club_id}, but
 * this aggregate-internal child does <strong>not</strong> bear {@code @TenantId}
 * — the same pattern as {@code FlightCrew} under {@code Flight} (V7 comment).
 * Rows are reached only through the tenant-filtered {@link PlanningDay} root;
 * the column is populated from the parent's club at construction so the FK +
 * NOT NULL are satisfied without making the child a second sweep-participating
 * tenant root.
 */
@Entity
@Table(name = "t_planning_day_assignment")
public class PlanningDayAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    // No @TenantId — aggregate-internal child reached only through the
    // tenant-filtered PlanningDay root (FlightCrew/V7 pattern). The column is
    // set from the parent's club at construction.
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private UUID operatingClubId = new UUID(0L, 0L);

    @ManyToOne(optional = false)
    @JoinColumn(name = "planning_day_id", nullable = false)
    @SuppressWarnings("UnusedVariable") // JPA mappedBy target.
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
        // JPA.
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

    /**
     * Re-points this row at a different person, keeping its type identity. Used
     * by {@link PlanningDay#assignRole} to upsert in place rather than
     * delete-and-reinsert (partial-unique flush ordering).
     */
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
