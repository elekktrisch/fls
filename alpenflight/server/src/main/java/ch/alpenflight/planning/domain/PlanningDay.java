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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_planning_day")
public class PlanningDay {

    private static final LocalDate MIN_PLANNING_DATE = LocalDate.of(1900, 1, 1);

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

    @Column(name = "created_by_user_id", updatable = false)
    private @Nullable UUID createdByUserId;

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
    }

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

    public static final long MAX_RULE_SPAN_DAYS = 366;

    public static List<LocalDate> expandRuleDates(LocalDate startDate,
                                                  LocalDate endDate,
                                                  Set<DayOfWeek> weekdays) {
        if (startDate == null) {
            throw new IllegalArgumentException("startDate must not be null");
        }
        if (endDate == null) {
            throw new IllegalArgumentException("endDate must not be null");
        }
        if (weekdays == null) {
            throw new IllegalArgumentException("weekdays must not be null");
        }
        validatePlanningDate(startDate);
        validatePlanningDate(endDate);
        if (weekdays.isEmpty() || endDate.isBefore(startDate)) {
            return List.of();
        }
        long span = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (span > MAX_RULE_SPAN_DAYS) {
            throw new PlanningRuleRangeException(
                    "Rule range " + startDate + "…" + endDate + " spans " + span
                            + " days, exceeding the cap of " + MAX_RULE_SPAN_DAYS);
        }
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (weekdays.contains(date.getDayOfWeek())) {
                dates.add(date);
            }
        }
        return List.copyOf(dates);
    }

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

    public boolean clearRole(PlanningRole role, UUID assignmentTypeId) {
        return assignRole(role, assignmentTypeId, null);
    }

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

    public DedupKey dedupKey() {
        return new DedupKey(requireClub(),
                Objects.requireNonNull(planningDate, "planningDate not set"),
                Objects.requireNonNull(locationId, "locationId not set"));
    }

    public void recordCreatedBy(@Nullable UUID userId) {
        this.createdByUserId = userId;
    }

    public @Nullable UUID getCreatedByUserId() {
        return createdByUserId;
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

    public List<PlanningDayAssignment> getAssignments() {
        return assignments.stream().filter(a -> !a.isDeleted()).toList();
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    public record DedupKey(UUID operatingClubId, LocalDate planningDate, UUID locationId) {}
}
