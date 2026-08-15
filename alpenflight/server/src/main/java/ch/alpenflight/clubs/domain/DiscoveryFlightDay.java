package ch.alpenflight.clubs.domain;

import ch.alpenflight.platform.id.UuidV7;
import ch.alpenflight.platform.persistence.SoftDeletableAggregate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_discovery_flight_day")
public class DiscoveryFlightDay extends SoftDeletableAggregate {

    @Id
    @UuidV7
    private @Nullable UUID id;

    @TenantId
    @Column(name = "club_id", nullable = false, updatable = false)
    private @Nullable UUID clubId;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate = LocalDate.EPOCH;

    protected DiscoveryFlightDay() {
    }

    public static DiscoveryFlightDay schedule(LocalDate eventDate, LocalDate today) {
        DiscoveryFlightDay day = new DiscoveryFlightDay();
        day.assignEventDate(eventDate, today);
        return day;
    }

    public void reschedule(LocalDate newEventDate, LocalDate today) {
        if (isDeleted()) {
            throw new IllegalStateException("Cannot mutate a withdrawn DiscoveryFlightDay");
        }
        assignEventDate(newEventDate, today);
    }

    public boolean isBookableOn(LocalDate today) {
        return !isDeleted() && !eventDate.isBefore(today);
    }

    private void assignEventDate(LocalDate value, LocalDate today) {
        if (value == null) {
            throw new IllegalArgumentException("eventDate must not be null");
        }
        if (today == null) {
            throw new IllegalArgumentException("today must not be null");
        }
        if (value.isBefore(today)) {
            throw new IllegalArgumentException(
                    "eventDate must not be in the past (got: " + value + ", today: " + today + ")");
        }
        this.eventDate = value;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getClubId() {
        return clubId;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }
}
