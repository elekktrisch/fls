package ch.alpenflight.planning.domain;

import ch.alpenflight.platform.text.FreeText;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Planning-day aggregate root (J-6). Fixes who is on duty (instructor, tow
 * pilot, flight operator) at a {@code location} for a {@code planningDate}, and
 * carries free-text {@code info} (legacy {@code Remarks}). Owns its child
 * {@link PlanningDayAssignment} rows (cascade + orphan-removal, mirroring the
 * V4 {@code fk_pda_planning_day_id … ON DELETE CASCADE}).
 *
 * <p>Storage is the <em>generic</em> typed-assignment model: each crew slot is
 * a {@link PlanningDayAssignment} keyed by a per-club
 * {@link PlanningDayAssignmentType}. The three well-known {@link PlanningRole}s
 * are upserted/cleared via {@link #assignRole} (null person clears the row),
 * mirroring legacy {@code MappingExtensions.cs:3302/3325/3348} upsert-or-delete.
 *
 * <p>Per ADR 0022 directive 2 the business rules live here, NOT the schema:
 * <ul>
 *   <li>{@link #validatePlanningDate(LocalDate)} — a planning-date sanity range
 *       runs at construction (V4 drops {@code ck_pln_planning_date_reasonable}).</li>
 *   <li>The duplicate-{@code (club,date,location)} rule is enforced at the
 *       repository/unique-index layer ({@code ux_pln_club_date_loc}, T-03); the
 *       aggregate exposes {@link #dedupKey()} so that layer + callers share one
 *       identity tuple.</li>
 * </ul>
 *
 * <p>Tenant-scoped on {@code operating_club_id} (ADR 0008); {@code location_id}
 * references the cross-tenant Location catalog by raw UUID.
 */
@Entity
@Table(name = "t_planning_day")
public class PlanningDay {

    /**
     * Lower sanity bound for a planning date — far enough back to admit any
     * legitimately migrated historical day, but rejecting an obviously
     * garbage/zero date. Mirrors the intent of the dropped
     * {@code ck_pln_planning_date_reasonable} (V4 comment).
     */
    private static final LocalDate MIN_PLANNING_DATE = LocalDate.of(1900, 1, 1);

    /** Upper sanity bound — rejects an obviously garbage far-future date. */
    private static final LocalDate MAX_PLANNING_DATE = LocalDate.of(2100, 12, 31);

    private static final int MAX_INFO_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "planning_date", nullable = false)
    private @Nullable LocalDate planningDate;

    @Column(name = "location_id", nullable = false)
    private @Nullable UUID locationId;

    @Column(name = "info")
    private @Nullable String info;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    @OneToMany(mappedBy = "planningDay",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<PlanningDayAssignment> assignments = new ArrayList<>();

    protected PlanningDay() {
        // JPA.
    }

    /**
     * Factory for a new planning day. {@link #validatePlanningDate(LocalDate)}
     * runs at construction so an out-of-range date never reaches persistence.
     * The day starts with no crew — assign roles via {@link #assignRole}.
     *
     * @param operatingClubId the tenant stamp — the operating club
     * @param planningDate the day this plan is for (pure DATE, no tz shift)
     * @param locationId the Location this plan is at (cross-tenant catalog FK)
     * @param info free-text remarks (legacy {@code Remarks}), nullable
     */
    public static PlanningDay create(UUID operatingClubId,
                                     LocalDate planningDate,
                                     UUID locationId,
                                     @Nullable String info) {
        if (operatingClubId == null) {
            throw new IllegalArgumentException("operatingClubId must not be null");
        }
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
        validatePlanningDate(planningDate);
        PlanningDay d = new PlanningDay();
        d.operatingClubId = operatingClubId;
        d.planningDate = planningDate;
        d.locationId = locationId;
        d.setInfo(info);
        return d;
    }

    /**
     * Rejects a planning date outside the sanity range
     * {@code [1900-01-01, 2100-12-31]} (or null). The bounds are deliberately
     * wide — this guards against a garbage/zero date, not against scheduling
     * policy, which the wizard's range check owns (T-05).
     */
    public static void validatePlanningDate(@Nullable LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("planningDate must not be null");
        }
        if (date.isBefore(MIN_PLANNING_DATE) || date.isAfter(MAX_PLANNING_DATE)) {
            throw new InvalidPlanningDateException(
                    "planningDate " + date + " is outside the sane range ["
                            + MIN_PLANNING_DATE + ", " + MAX_PLANNING_DATE + "]");
        }
    }

    /** Moves the day to a different date, re-running the sanity range check. */
    public void reschedule(LocalDate newPlanningDate) {
        validatePlanningDate(newPlanningDate);
        this.planningDate = newPlanningDate;
    }

    public void reassignLocation(UUID newLocationId) {
        if (newLocationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
        this.locationId = newLocationId;
    }

    public void updateInfo(@Nullable String newInfo) {
        setInfo(newInfo);
    }

    /**
     * Upserts-or-clears the crew assignment for {@code role}, mirroring legacy
     * upsert-or-delete ({@code MappingExtensions.cs:3302/3325/3348}). With a
     * non-null {@code personId}: reuse the existing row for the role's type
     * (re-point its person) or add a new one. With a null {@code personId}:
     * remove the role's row if present (orphan-removal cascades the DELETE).
     *
     * <p>{@code assignmentTypeId} is the caller-resolved per-club
     * {@link PlanningDayAssignmentType} id for {@code role} — the role→type-id
     * resolution is per-club and lives outside the aggregate (the type
     * repository, T-04). The {@code role} parameter documents intent + lets the
     * aggregate stay parity-faithful to the legacy 3-field write.
     *
     * @return {@code true} if the assignment set changed
     */
    public boolean assignRole(PlanningRole role, UUID assignmentTypeId, @Nullable UUID personId) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (assignmentTypeId == null) {
            throw new IllegalArgumentException("assignmentTypeId must not be null");
        }
        Optional<PlanningDayAssignment> existing = activeAssignmentForType(assignmentTypeId);
        if (personId == null) {
            return existing.map(a -> {
                assignments.remove(a);
                return true;
            }).orElse(false);
        }
        if (existing.isPresent()) {
            PlanningDayAssignment row = existing.get();
            if (Objects.equals(row.getAssignedPersonId(), personId)) {
                return false;
            }
            row.reassignTo(personId);
            return true;
        }
        assignments.add(new PlanningDayAssignment(
                this, requireClub(), assignmentTypeId, personId, null));
        return true;
    }

    /**
     * Clears the crew assignment for {@code role} (convenience for
     * {@link #assignRole}{@code (role, typeId, null)}).
     *
     * @return {@code true} if a row was removed
     */
    public boolean clearRole(PlanningRole role, UUID assignmentTypeId) {
        return assignRole(role, assignmentTypeId, null);
    }

    /**
     * The active person assigned for {@code assignmentTypeId}, or empty when
     * the role is unassigned.
     */
    public Optional<UUID> assignedPersonForType(UUID assignmentTypeId) {
        return activeAssignmentForType(assignmentTypeId)
                .map(PlanningDayAssignment::getAssignedPersonId);
    }

    private Optional<PlanningDayAssignment> activeAssignmentForType(UUID assignmentTypeId) {
        return assignments.stream()
                .filter(a -> !a.isDeleted())
                .filter(a -> Objects.equals(a.getAssignmentTypeId(), assignmentTypeId))
                .findFirst();
    }

    /**
     * The {@code (club, date, location)} identity the {@code ux_pln_club_date_loc}
     * unique index keys on — shared with the repository's dedup check (T-03) so
     * one tuple definition backs both layers.
     */
    public DedupKey dedupKey() {
        return new DedupKey(requireClub(),
                Objects.requireNonNull(planningDate, "planningDate not set"),
                Objects.requireNonNull(locationId, "locationId not set"));
    }

    public void softDelete(@Nullable UUID userId, Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
            this.deletedByUserId = userId;
        }
    }

    private UUID requireClub() {
        return Objects.requireNonNull(operatingClubId, "operatingClubId not set");
    }

    private void setInfo(@Nullable String value) {
        this.info = FreeText.normalize(value, MAX_INFO_LENGTH);
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public @Nullable LocalDate getPlanningDate() {
        return planningDate;
    }

    public @Nullable UUID getLocationId() {
        return locationId;
    }

    public @Nullable String getInfo() {
        return info;
    }

    /** Live (non-deleted) child assignment rows, read-only. */
    public List<PlanningDayAssignment> getAssignments() {
        return assignments.stream().filter(a -> !a.isDeleted()).toList();
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    /** Identity tuple for the {@code ux_pln_club_date_loc} unique constraint. */
    public record DedupKey(UUID operatingClubId, LocalDate planningDate, UUID locationId) {}
}
