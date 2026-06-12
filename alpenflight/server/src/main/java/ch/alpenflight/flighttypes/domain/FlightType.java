package ch.alpenflight.flighttypes.domain;

import ch.alpenflight.platform.id.FlightTypeId;
import ch.alpenflight.platform.persistence.PersistedAuditActor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.DomainEvents;

/**
 * FlightType aggregate root. Per-club masterdata describing a kind of flight
 * (Schulflug, Streckenflug, Schleppflug, …). Tenant-scoped via Hibernate's
 * {@code @TenantId} on {@code operatingClubId} (S-159 / S-013 reclassification);
 * every read + write is filtered to the caller's managing tenant by Hibernate
 * before the service ever sees the row.
 *
 * <p>Per ADR 0022 directive 2, business rules live on the aggregate, not the
 * schema:
 *
 * <ul>
 *   <li>{@code flightTypeName} is non-blank + length-capped at 100 chars
 *       (matches V3 column width).</li>
 *   <li>{@code minNrOfAircraftSeatsRequired} is {@code null} ("no constraint")
 *       or {@code >= 1}. Legacy treated 0 and NULL identically; the new stack
 *       rejects 0 at the DTO boundary and the aggregate enforces the same
 *       invariant for direct callers.</li>
 *   <li>{@code instructorRequired} and
 *       {@code observerPilotOrInstructorRequired} are mutually exclusive
 *       (legacy CHECK {@code CK_FlightTypes_InstructorRequiredXORObserverPilotRequired}
 *       forbids {@code (1,1)}); see {@link #updateFlags}. All other boolean
 *       flags are independently composable. The V3 schema carries no CHECK —
 *       the rule lives here.</li>
 * </ul>
 *
 * <p>Identity-bearing partial UNIQUE on
 * {@code (operating_club_id, flight_type_name) WHERE deleted_on IS NULL}
 * (V11) lets a tenant soft-delete and recreate the same name; the
 * structural UNIQUE catches concurrent INSERT races the service-layer
 * pre-check can't.
 */
@Entity
@Table(name = "t_flight_type")
public class FlightType {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_CODE_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "flight_type_name", nullable = false, length = MAX_NAME_LENGTH)
    private String flightTypeName = "";

    @Column(name = "flight_code", length = MAX_CODE_LENGTH)
    private @Nullable String flightCode;

    @Column(name = "instructor_required", nullable = false)
    private boolean instructorRequired;

    @Column(name = "observer_pilot_or_instructor_required", nullable = false)
    private boolean observerPilotOrInstructorRequired;

    @Column(name = "is_check_flight", nullable = false)
    private boolean checkFlight;

    @Column(name = "is_passenger_flight", nullable = false)
    private boolean passengerFlight;

    @Column(name = "is_solo_flight", nullable = false)
    private boolean soloFlight;

    @Column(name = "is_for_glider_flights", nullable = false)
    private boolean forGliderFlights;

    @Column(name = "is_for_tow_flights", nullable = false)
    private boolean forTowFlights;

    @Column(name = "is_for_motor_flights", nullable = false)
    private boolean forMotorFlights;

    @Column(name = "is_flight_cost_balance_selectable", nullable = false)
    private boolean flightCostBalanceSelectable;

    @Column(name = "is_coupon_number_required", nullable = false)
    private boolean couponNumberRequired;

    @Column(name = "is_for_aircraft_reservation_type", nullable = false)
    private boolean forAircraftReservationType;

    @Column(name = "min_nr_of_aircraft_seats_required")
    private @Nullable Integer minNrOfAircraftSeatsRequired;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @PersistedAuditActor
    @SuppressWarnings({"UnusedVariable", "FieldCanBeLocal"})
    private @Nullable UUID deletedByUserId;

    protected FlightType() {
        // JPA.
    }

    /**
     * Factory for a new FlightType. The tenant ({@link #operatingClubId}) is
     * set by Hibernate's {@code @TenantId} resolver on persist — do NOT pass
     * it here.
     */
    public static FlightType register(String name,
                                      @Nullable String flightCode,
                                      boolean instructorRequired,
                                      boolean observerPilotOrInstructorRequired,
                                      boolean checkFlight,
                                      boolean passengerFlight,
                                      boolean soloFlight,
                                      boolean forGliderFlights,
                                      boolean forTowFlights,
                                      boolean forMotorFlights,
                                      boolean flightCostBalanceSelectable,
                                      boolean couponNumberRequired,
                                      boolean forAircraftReservationType,
                                      @Nullable Integer minNrOfAircraftSeatsRequired) {
        FlightType ft = new FlightType();
        ft.assignName(name);
        ft.assignFlightCode(flightCode);
        ft.updateFlags(instructorRequired,
                observerPilotOrInstructorRequired,
                checkFlight,
                passengerFlight,
                soloFlight,
                forGliderFlights,
                forTowFlights,
                forMotorFlights,
                flightCostBalanceSelectable,
                couponNumberRequired,
                forAircraftReservationType);
        ft.assignMinSeats(minNrOfAircraftSeatsRequired);
        return ft;
    }

    public void rename(String newName) {
        assignName(newName);
    }

    public void changeFlightCode(@Nullable String newFlightCode) {
        assignFlightCode(newFlightCode);
    }

    /**
     * Replaces all eleven flags atomically. Rejects the contradictory
     * combination {@code instructorRequired && observerPilotOrInstructorRequired}
     * — "observer pilot OR instructor" is the weaker requirement, so pairing
     * it with the strict "instructor required" is meaningless (legacy CHECK
     * {@code CK_FlightTypes_InstructorRequiredXORObserverPilotRequired});
     * the guard fires before any assignment, so a rejected update leaves the
     * aggregate unchanged. Covers create too ({@link #register} delegates here).
     *
     * @throws InstructorObserverExclusionException when both crew-requirement
     *         flags are set
     */
    public void updateFlags(boolean newInstructorRequired,
                            boolean newObserverPilotOrInstructorRequired,
                            boolean newCheckFlight,
                            boolean newPassengerFlight,
                            boolean newSoloFlight,
                            boolean newForGliderFlights,
                            boolean newForTowFlights,
                            boolean newForMotorFlights,
                            boolean newFlightCostBalanceSelectable,
                            boolean newCouponNumberRequired,
                            boolean newForAircraftReservationType) {
        if (newInstructorRequired && newObserverPilotOrInstructorRequired) {
            throw new InstructorObserverExclusionException();
        }
        this.instructorRequired = newInstructorRequired;
        this.observerPilotOrInstructorRequired = newObserverPilotOrInstructorRequired;
        this.checkFlight = newCheckFlight;
        this.passengerFlight = newPassengerFlight;
        this.soloFlight = newSoloFlight;
        this.forGliderFlights = newForGliderFlights;
        this.forTowFlights = newForTowFlights;
        this.forMotorFlights = newForMotorFlights;
        this.flightCostBalanceSelectable = newFlightCostBalanceSelectable;
        this.couponNumberRequired = newCouponNumberRequired;
        this.forAircraftReservationType = newForAircraftReservationType;
    }

    public void changeMinSeats(@Nullable Integer newMinNrOfAircraftSeatsRequired) {
        assignMinSeats(newMinNrOfAircraftSeatsRequired);
    }

    public void softDelete(@Nullable UUID userId, Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
            this.deletedByUserId = userId;
        }
    }

    /**
     * Spring Data publishes a {@link FlightTypeSaved} event on every
     * {@code FlightTypeRepository.save} (the Flight {@code @DomainEvents}
     * precedent, J-7 RM-2) — at which point JPA's UUID generator has populated
     * {@link #id}. Unconditional: the flight-report read-model keeps its
     * denormalized flight-type name / code strings fresh by observing EVERY
     * persisted state change (ADR 0027 §2).
     */
    @DomainEvents
    Collection<Object> domainEvents() {
        return List.of(new FlightTypeSaved(Objects.requireNonNull(
                this.id, "FlightType.id null at domain-event publication — save() runs first")));
    }

    private void assignName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("flightTypeName must not be blank");
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "flightTypeName exceeds " + MAX_NAME_LENGTH + " characters");
        }
        this.flightTypeName = trimmed;
    }

    private void assignFlightCode(@Nullable String value) {
        if (value == null) {
            this.flightCode = null;
            return;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            this.flightCode = null;
            return;
        }
        if (trimmed.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "flightCode exceeds " + MAX_CODE_LENGTH + " characters");
        }
        this.flightCode = trimmed;
    }

    private void assignMinSeats(@Nullable Integer value) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(
                    "minNrOfAircraftSeatsRequired must be >= 1 or null (got: " + value + ")");
        }
        this.minNrOfAircraftSeatsRequired = value;
    }

    public @Nullable FlightTypeId getId() {
        return FlightTypeId.ofNullable(id);
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public String getFlightTypeName() {
        return flightTypeName;
    }

    public @Nullable String getFlightCode() {
        return flightCode;
    }

    public boolean isInstructorRequired() {
        return instructorRequired;
    }

    public boolean isObserverPilotOrInstructorRequired() {
        return observerPilotOrInstructorRequired;
    }

    public boolean isCheckFlight() {
        return checkFlight;
    }

    public boolean isPassengerFlight() {
        return passengerFlight;
    }

    public boolean isSoloFlight() {
        return soloFlight;
    }

    public boolean isForGliderFlights() {
        return forGliderFlights;
    }

    public boolean isForTowFlights() {
        return forTowFlights;
    }

    public boolean isForMotorFlights() {
        return forMotorFlights;
    }

    public boolean isFlightCostBalanceSelectable() {
        return flightCostBalanceSelectable;
    }

    public boolean isCouponNumberRequired() {
        return couponNumberRequired;
    }

    public boolean isForAircraftReservationType() {
        return forAircraftReservationType;
    }

    public @Nullable Integer getMinNrOfAircraftSeatsRequired() {
        return minNrOfAircraftSeatsRequired;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }
}
