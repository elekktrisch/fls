package ch.alpenflight.persons.domain;

import ch.alpenflight.audit.domain.AuditRedact;
import jakarta.persistence.CascadeType;
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
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate-internal child of {@link Person}: a single club-membership row.
 * Tenant-scoped via Hibernate's {@code @TenantId} discriminator on
 * {@link #clubId} — even though the parent {@link Person} is cross-tenant,
 * Hibernate automatically restricts the {@code personClubs} collection to
 * rows whose {@code club_id} matches the caller's tenant.
 *
 * <p>Surrogate-PK reshape (V2): legacy carried a composite PK
 * ({@code person_id}, {@code club_id}); the new schema gives every row a UUID
 * surrogate {@code id} plus partial UNIQUE {@code ux_person_club_alive} on
 * ({@code person_id}, {@code club_id}) WHERE {@code deleted_on IS NULL} —
 * which is the structural safety net for the "max one alive membership per
 * pair" invariant {@link Person#joinClub} enforces in the aggregate.
 *
 * <p>No top-level CRUD endpoint exists. Mutators are package-private; only
 * {@link Person} drives them via {@code joinClub} / {@code leaveClub} /
 * {@code updateClubMembership}.
 */
@Entity
@Table(name = "t_person_club")
public class PersonClub {

    private static final int MAX_MEMBER_NUMBER_LENGTH = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @ManyToOne(optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "person_id", nullable = false)
    // The back-reference walks into the cross-tenant Person aggregate; Person
    // is in audit.redaction.deny-all so the redactor would scrub it anyway,
    // but @AuditRedact here makes the decision explicit + survives a future
    // policy relax. Also satisfies AuditRedactionCoverageTest's "every field
    // gets an explicit decision" contract.
    @AuditRedact
    @SuppressWarnings("UnusedVariable")
    private @Nullable Person person;

    @TenantId
    @Column(name = "club_id", nullable = false, updatable = false)
    private @Nullable UUID clubId;

    @Column(name = "member_number", length = MAX_MEMBER_NUMBER_LENGTH)
    private @Nullable String memberNumber;

    @Column(name = "member_state_id")
    private @Nullable UUID memberStateId;

    @Column(name = "is_motor_pilot", nullable = false)
    private boolean motorPilot;

    @Column(name = "is_tow_pilot", nullable = false)
    private boolean towPilot;

    @Column(name = "is_glider_instructor", nullable = false)
    private boolean gliderInstructor;

    @Column(name = "is_glider_pilot", nullable = false)
    private boolean gliderPilot;

    @Column(name = "is_glider_trainee", nullable = false)
    private boolean gliderTrainee;

    @Column(name = "is_passenger", nullable = false)
    private boolean passenger;

    @Column(name = "is_winch_operator", nullable = false)
    private boolean winchOperator;

    @Column(name = "is_motor_instructor", nullable = false)
    private boolean motorInstructor;

    @Column(name = "receive_flight_reports", nullable = false)
    private boolean receiveFlightReports;

    @Column(name = "receive_aircraft_reservation_notifications", nullable = false)
    private boolean receiveAircraftReservationNotifications;

    @Column(name = "receive_planning_day_role_reminder", nullable = false)
    private boolean receivePlanningDayRoleReminder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    protected PersonClub() {
        // JPA.
    }

    static PersonClub open(UUID clubId,
                           @Nullable String memberNumber,
                           @Nullable UUID memberStateId,
                           PersonRoleFlags roles,
                           PersonNotificationPrefs prefs,
                           boolean active) {
        if (clubId == null) {
            throw new IllegalArgumentException("clubId must not be null");
        }
        PersonClub pc = new PersonClub();
        pc.clubId = clubId;
        pc.applyMembership(memberNumber, memberStateId, roles, prefs, active);
        return pc;
    }

    /**
     * Test-fixture factory: build a PersonClub <strong>without setting</strong>
     * the {@code clubId} field, attached to {@code parent}. Hibernate's
     * {@code @TenantId} resolver populates {@code club_id} on save —
     * matching the {@link MemberState}-style "transient construction, fill
     * tenant on flush" pattern. Production code goes through
     * {@link Person#joinClub} which sets the proposed {@code clubId} for
     * the in-memory duplicate check; this factory bypasses that because
     * sweep fixtures intentionally don't deduplicate.
     *
     * <p>The returned child is attached to {@code parent}. Save via
     * {@code JpaPersonClubRepository.save(...)} — {@code CascadeType.PERSIST}
     * on {@link #person} cascade-inserts the transient {@code parent} at
     * flush.
     */
    public static PersonClub forSweepFixture(Person parent,
                                             PersonRoleFlags roles,
                                             PersonNotificationPrefs prefs,
                                             boolean active) {
        if (parent == null) {
            throw new IllegalArgumentException("parent must not be null");
        }
        PersonClub pc = new PersonClub();
        pc.person = parent;
        pc.applyMembership(null, null, roles, prefs, active);
        return pc;
    }

    void attachTo(Person parent) {
        this.person = parent;
    }

    void applyMembership(@Nullable String newMemberNumber,
                         @Nullable UUID newMemberStateId,
                         PersonRoleFlags roles,
                         PersonNotificationPrefs prefs,
                         boolean newActive) {
        if (roles == null) {
            throw new IllegalArgumentException("roles must not be null");
        }
        if (prefs == null) {
            throw new IllegalArgumentException("prefs must not be null");
        }
        this.memberNumber = capMemberNumber(blankToNull(newMemberNumber));
        this.memberStateId = newMemberStateId;
        this.motorPilot = roles.motorPilot();
        this.towPilot = roles.towPilot();
        this.gliderInstructor = roles.gliderInstructor();
        this.gliderPilot = roles.gliderPilot();
        this.gliderTrainee = roles.gliderTrainee();
        this.passenger = roles.passenger();
        this.winchOperator = roles.winchOperator();
        this.motorInstructor = roles.motorInstructor();
        this.receiveFlightReports = prefs.receiveFlightReports();
        this.receiveAircraftReservationNotifications = prefs.receiveAircraftReservationNotifications();
        this.receivePlanningDayRoleReminder = prefs.receivePlanningDayRoleReminder();
        this.active = newActive;
    }

    /**
     * Focused notification-prefs self-edit (J-4 T-10, the Notifications tab).
     * Changes ONLY the three notification booleans
     * ({@link #receiveFlightReports},
     * {@link #receiveAircraftReservationNotifications},
     * {@link #receivePlanningDayRoleReminder}); the admin-only membership
     * identity fields — {@link #memberNumber}, {@link #memberStateId}, the role
     * flags ({@code motorPilot} … {@code motorInstructor}) and {@link #active}
     * — are deliberately left untouched. Unlike {@link #applyMembership} (which
     * replaces the WHOLE membership shape, driven by the admin
     * {@code updateClubMembership} flow), this is the narrow business rule the
     * caller may apply to their own membership.
     *
     * <p>Package-private: only {@link Person#updateNotificationPrefs} drives it,
     * mirroring how {@code applyMembership} is reached only via the aggregate
     * root.
     */
    void updateNotificationPrefs(PersonNotificationPrefs prefs) {
        if (prefs == null) {
            throw new IllegalArgumentException("prefs must not be null");
        }
        this.receiveFlightReports = prefs.receiveFlightReports();
        this.receiveAircraftReservationNotifications = prefs.receiveAircraftReservationNotifications();
        this.receivePlanningDayRoleReminder = prefs.receivePlanningDayRoleReminder();
    }

    void softDelete(@Nullable UUID userId, Instant at) {
        if (this.deletedOn == null) {
            this.deletedOn = at;
            this.deletedByUserId = userId;
        }
    }

    void reactivate() {
        this.deletedOn = null;
        this.deletedByUserId = null;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getClubId() {
        return clubId;
    }

    public @Nullable String getMemberNumber() {
        return memberNumber;
    }

    public @Nullable UUID getMemberStateId() {
        return memberStateId;
    }

    public boolean isMotorPilot() {
        return motorPilot;
    }

    public boolean isTowPilot() {
        return towPilot;
    }

    public boolean isGliderInstructor() {
        return gliderInstructor;
    }

    public boolean isGliderPilot() {
        return gliderPilot;
    }

    public boolean isGliderTrainee() {
        return gliderTrainee;
    }

    public boolean isPassenger() {
        return passenger;
    }

    public boolean isWinchOperator() {
        return winchOperator;
    }

    public boolean isMotorInstructor() {
        return motorInstructor;
    }

    public boolean isReceiveFlightReports() {
        return receiveFlightReports;
    }

    public boolean isReceiveAircraftReservationNotifications() {
        return receiveAircraftReservationNotifications;
    }

    public boolean isReceivePlanningDayRoleReminder() {
        return receivePlanningDayRoleReminder;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static @Nullable String capMemberNumber(@Nullable String value) {
        if (value != null && value.length() > MAX_MEMBER_NUMBER_LENGTH) {
            throw new IllegalArgumentException(
                    "memberNumber exceeds " + MAX_MEMBER_NUMBER_LENGTH + " characters");
        }
        return value;
    }
}
