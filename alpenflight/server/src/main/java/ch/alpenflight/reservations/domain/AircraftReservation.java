package ch.alpenflight.reservations.domain;

import ch.alpenflight.platform.text.FreeText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_aircraft_reservation")
public class AircraftReservation {

    private static final int MAX_INFO_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "aircraft_id", nullable = false)
    private @Nullable UUID aircraftId;

    @Column(name = "reservation_start", nullable = false)
    private @Nullable Instant reservationStart;

    @Column(name = "reservation_end", nullable = false)
    private @Nullable Instant reservationEnd;

    @Column(name = "is_all_day", nullable = false)
    private boolean allDay;

    @Column(name = "pilot_person_id", nullable = false)
    private @Nullable UUID pilotPersonId;

    @Column(name = "second_crew_person_id")
    private @Nullable UUID secondCrewPersonId;

    @Column(name = "location_id", nullable = false)
    private @Nullable UUID locationId;

    @Column(name = "reservation_type_id")
    private @Nullable UUID reservationTypeId;

    @Column(name = "flight_type_id")
    private @Nullable UUID flightTypeId;

    @Column(name = "info")
    private @Nullable String info;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    @Transient
    @SuppressWarnings("UnusedVariable")
    private final boolean rangeIsDatabaseGenerated = true;

    protected AircraftReservation() {
    }

    public static AircraftReservation create(UUID operatingClubId,
                                             UUID aircraftId,
                                             UUID pilotPersonId,
                                             UUID locationId,
                                             @Nullable UUID reservationTypeId,
                                             @Nullable UUID flightTypeId,
                                             Instant start,
                                             Instant end,
                                             boolean isAllDay,
                                             @Nullable UUID secondCrewPersonId,
                                             @Nullable String info) {
        if (operatingClubId == null) {
            throw new IllegalArgumentException("operatingClubId must not be null");
        }
        if (aircraftId == null) {
            throw new IllegalArgumentException("aircraftId must not be null");
        }
        if (pilotPersonId == null) {
            throw new IllegalArgumentException("pilotPersonId must not be null");
        }
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
        AircraftReservation r = new AircraftReservation();
        r.operatingClubId = operatingClubId;
        r.aircraftId = aircraftId;
        r.pilotPersonId = pilotPersonId;
        r.locationId = locationId;
        r.reservationTypeId = reservationTypeId;
        r.flightTypeId = flightTypeId;
        r.secondCrewPersonId = secondCrewPersonId;
        r.setInfo(info);
        r.reschedule(start, end, isAllDay);
        return r;
    }

    public void reschedule(Instant start, Instant end, boolean isAllDay) {
        if (start == null) {
            throw new IllegalArgumentException("start must not be null");
        }
        if (end == null) {
            throw new IllegalArgumentException("end must not be null");
        }
        this.allDay = isAllDay;
        EffectiveSpan span = effectiveSpan(start, end, isAllDay);
        this.reservationStart = span.start();
        this.reservationEnd = span.end();
        validateDuration();
    }

    public static EffectiveSpan effectiveSpan(Instant start, Instant end, boolean isAllDay) {
        if (start == null) {
            throw new IllegalArgumentException("start must not be null");
        }
        if (end == null) {
            throw new IllegalArgumentException("end must not be null");
        }
        if (isAllDay) {
            Instant dayStart = start.atZone(ZoneOffset.UTC).toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            return new EffectiveSpan(dayStart, dayStart.plus(java.time.Duration.ofDays(1)));
        }
        return new EffectiveSpan(start, end);
    }

    public record EffectiveSpan(Instant start, Instant end) {

        public boolean isValidDuration() {
            return end.isAfter(start);
        }
    }

    public void changeType(@Nullable UUID newReservationTypeId, @Nullable UUID newFlightTypeId) {
        this.reservationTypeId = newReservationTypeId;
        this.flightTypeId = newFlightTypeId;
    }

    public void changeCrew(UUID newPilotPersonId, @Nullable UUID newSecondCrewPersonId) {
        if (newPilotPersonId == null) {
            throw new IllegalArgumentException("pilotPersonId must not be null");
        }
        this.pilotPersonId = newPilotPersonId;
        this.secondCrewPersonId = newSecondCrewPersonId;
    }

    public void reassignLocation(UUID newLocationId) {
        if (newLocationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
        this.locationId = newLocationId;
    }

    public void changeAircraft(UUID newAircraftId) {
        if (newAircraftId == null) {
            throw new IllegalArgumentException("aircraftId must not be null");
        }
        this.aircraftId = newAircraftId;
    }

    public void updateInfo(@Nullable String newInfo) {
        setInfo(newInfo);
    }

    public void validateDuration() {
        Instant start = effectiveStart();
        Instant end = effectiveEnd();
        if (!end.isAfter(start)) {
            throw new InvalidReservationDurationException(
                    "reservation end (" + end + ") must be strictly after start ("
                            + start + ")");
        }
    }

    public boolean conflictsWith(AircraftReservation other) {
        if (other == null) {
            return false;
        }
        if (isDeleted() || other.isDeleted()) {
            return false;
        }
        if (!Objects.equals(this.aircraftId, other.aircraftId)) {
            return false;
        }
        if (sameIdentity(other)) {
            return false;
        }
        return this.effectiveStart().isBefore(other.effectiveEnd())
                && other.effectiveStart().isBefore(this.effectiveEnd());
    }

    public boolean sameIdentity(AircraftReservation other) {
        return this.id != null && Objects.equals(this.id, other.id);
    }

    public Instant effectiveStart() {
        return Objects.requireNonNull(reservationStart, "reservationStart not set");
    }

    public Instant effectiveEnd() {
        return Objects.requireNonNull(reservationEnd, "reservationEnd not set");
    }

    public void softDelete(@Nullable UUID userId, Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
            this.deletedByUserId = userId;
        }
    }

    private void setInfo(@Nullable String value) {
        this.info = FreeText.normalize(value, MAX_INFO_LENGTH);
    }

    void assignIdForTest(UUID assignedId) {
        this.id = assignedId;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public @Nullable UUID getAircraftId() {
        return aircraftId;
    }

    public @Nullable Instant getReservationStart() {
        return reservationStart;
    }

    public @Nullable Instant getReservationEnd() {
        return reservationEnd;
    }

    public boolean isAllDay() {
        return allDay;
    }

    public @Nullable UUID getPilotPersonId() {
        return pilotPersonId;
    }

    public @Nullable UUID getSecondCrewPersonId() {
        return secondCrewPersonId;
    }

    public @Nullable UUID getLocationId() {
        return locationId;
    }

    public @Nullable UUID getReservationTypeId() {
        return reservationTypeId;
    }

    public @Nullable UUID getFlightTypeId() {
        return flightTypeId;
    }

    public @Nullable String getInfo() {
        return info;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }
}
