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

/**
 * DiscoveryFlightDay aggregate root — one day a club offers discovery flights
 * on, offered to anonymous visitors by the public registration form and managed
 * by a club admin. Tenant-scoped via Hibernate's {@code @TenantId} on
 * {@code clubId}; the public read runs inside a {@code Tenants.runAs(clubId, …)}
 * window, so the discriminator filters it with no authenticated principal.
 *
 * <p>Per ADR 0022 directive 2 the rules live here, not the schema:
 *
 * <ul>
 *   <li>A day is a calendar {@link LocalDate}. The registration books an all-day
 *       reservation, so a time-of-day would be stored and never read.</li>
 *   <li>A day may not be scheduled or moved into the past — see
 *       {@link #schedule} / {@link #reschedule}.</li>
 *   <li>{@link #isBookableOn} is the read rule the public day list and the
 *       submit-time re-check share: alive, and not before the caller's today.
 *       A day that has passed drops out on its own, with no cleanup job.</li>
 * </ul>
 *
 * <p>No per-day flight type. {@code Club.discoveryFlightTypeId} is the single
 * value stamped on every discovery reservation; a per-day column would be a
 * second source for the same setting and a precedence rule nothing asks for.
 * Adding one later is additive — this aggregate carries only the date.
 *
 * <p>Identity-bearing partial UNIQUE on
 * {@code (club_id, event_date) WHERE deleted_on IS NULL} (V58): two live rows
 * for one date would render as duplicate options in the day picker. Partial, so
 * a withdrawn day can be re-published.
 */
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
        // JPA.
    }

    /**
     * Publishes {@code eventDate} as a bookable discovery-flight day. The tenant
     * ({@link #clubId}) is set by Hibernate's {@code @TenantId} resolver on
     * persist — do NOT pass it here.
     *
     * @param today the caller's current date; a day already past cannot be
     *     published because no visitor could ever book it.
     */
    public static DiscoveryFlightDay schedule(LocalDate eventDate, LocalDate today) {
        DiscoveryFlightDay day = new DiscoveryFlightDay();
        day.assignEventDate(eventDate, today);
        return day;
    }

    /** Moves this day to {@code newEventDate}, under the same not-in-the-past rule. */
    public void reschedule(LocalDate newEventDate, LocalDate today) {
        if (isDeleted()) {
            throw new IllegalStateException("Cannot mutate a withdrawn DiscoveryFlightDay");
        }
        assignEventDate(newEventDate, today);
    }

    /** Whether a visitor may still pick this day on {@code today}. */
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
