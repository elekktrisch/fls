package ch.alpenflight.reservations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_aircraft_reservation_type")
public class AircraftReservationType {

    private static final int MAX_NAME_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "reservation_type_name", nullable = false, length = MAX_NAME_LENGTH)
    private String reservationTypeName = "";

    @Column(name = "is_instructor_required", nullable = false)
    private boolean instructorRequired;

    @Column(name = "is_maintenance", nullable = false)
    private boolean maintenance;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "remarks")
    private @Nullable String remarks;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    protected AircraftReservationType() {
    }

    public static AircraftReservationType create(UUID operatingClubId,
                                                 String reservationTypeName,
                                                 boolean instructorRequired,
                                                 boolean maintenance,
                                                 boolean active,
                                                 @Nullable String remarks) {
        if (operatingClubId == null) {
            throw new IllegalArgumentException("operatingClubId must not be null");
        }
        AircraftReservationType t = new AircraftReservationType();
        t.operatingClubId = operatingClubId;
        t.rename(reservationTypeName);
        t.instructorRequired = instructorRequired;
        t.maintenance = maintenance;
        t.active = active;
        t.remarks = blankToNull(remarks);
        return t;
    }

    public void rename(String newName) {
        String trimmed = newName == null ? null : newName.strip();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new IllegalArgumentException("reservationTypeName must not be blank");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "reservationTypeName exceeds " + MAX_NAME_LENGTH + " characters");
        }
        this.reservationTypeName = trimmed;
    }

    public void softDelete(@Nullable UUID userId, Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
            this.deletedByUserId = userId;
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public String getReservationTypeName() {
        return reservationTypeName;
    }

    public boolean isInstructorRequired() {
        return instructorRequired;
    }

    public boolean isMaintenance() {
        return maintenance;
    }

    public boolean isActive() {
        return active;
    }

    public @Nullable String getRemarks() {
        return remarks;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }
}
