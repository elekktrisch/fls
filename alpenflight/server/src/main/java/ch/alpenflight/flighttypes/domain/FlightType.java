package ch.alpenflight.flighttypes.domain;

import ch.alpenflight.platform.id.FlightTypeId;
import ch.alpenflight.platform.persistence.SoftDeletableAggregate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.DomainEvents;

@Entity
@Table(name = "t_flight_type")
public class FlightType extends SoftDeletableAggregate {

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

    protected FlightType() {
    }

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
}
