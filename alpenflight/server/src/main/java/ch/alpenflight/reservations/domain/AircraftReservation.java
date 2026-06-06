package ch.alpenflight.reservations.domain;

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

/**
 * Aircraft reservation aggregate root (J-5). A booking of one aircraft for a
 * window — timed (explicit start/end) or all-day. Per ADR 0022 directive 2 the
 * business rules live here, NOT the schema (V4 ships NO {@code EXCLUDE}
 * constraint and NO {@code CHECK} on the range):
 *
 * <ul>
 *   <li>{@link #validateDuration()} — timed reservations reject
 *       {@code end <= start} (incl. the empty-range degenerate
 *       {@code start == end}); all-day stores {@code isAllDay=true} + the date
 *       and normalises the <em>effective</em> span to the full day
 *       {@code [date 00:00, date+1 00:00)} (NOT the legacy zero-length
 *       {@code start==end} artifact — see J-5 assumption #2).</li>
 *   <li>{@link #conflictsWith(AircraftReservation)} — half-open overlap on the
 *       SAME aircraft: {@code existing.start < new.end && new.start <
 *       existing.end}, so an adjacent {@code end == next.start} booking does
 *       NOT conflict. Self-excluded by id (an edit does not conflict with
 *       itself). All-day vs timed compose correctly because both sides compare
 *       their <em>effective</em> span.</li>
 * </ul>
 *
 * <p>The pure overlap predicate + effective-span computation are owned here so
 * the infra GiST range-probe (T-04) and the service self-exclusion (T-05) reuse
 * the same semantics. The DB probe itself is the repository's job.
 *
 * <p>Tenant-scoped via {@code @TenantId} on {@link #operatingClubId} (ADR 0008).
 * The {@code aircraftId} FK crosses tenants freely — legacy-open parity
 * (operator 2026-06-06): any operating club may reserve any aircraft, with no
 * charter gate.
 */
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

    /**
     * The {@code reservation_range tstzrange} column is
     * {@code GENERATED ALWAYS AS STORED} in V4, derived from start+end — the
     * aggregate never writes it. Marked {@link Transient} so JPA ignores it.
     */
    @Transient
    @SuppressWarnings("UnusedVariable")
    private final boolean rangeIsDatabaseGenerated = true;

    protected AircraftReservation() {
        // JPA.
    }

    /**
     * Factory for a new reservation. For a timed reservation pass
     * {@code isAllDay=false} with explicit {@code start}/{@code end}; for an
     * all-day reservation pass {@code isAllDay=true} — {@code start}'s calendar
     * date (UTC) defines the full-day effective span and the stored
     * {@code reservation_start}/{@code reservation_end} are normalised to that
     * full day. {@link #validateDuration()} runs at construction so an invalid
     * timed range never reaches the persistence layer.
     *
     * @param operatingClubId the tenant stamp — the operating club. The
     *     aircraft FK may belong to a different club (legacy-open parity).
     */
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

    /**
     * Moves the reservation in time / toggles all-day. Re-runs
     * {@link #validateDuration()}. For all-day, {@code start}'s UTC calendar
     * date defines the full-day span; for timed, {@code start}/{@code end} are
     * stored verbatim.
     */
    public void reschedule(Instant start, Instant end, boolean isAllDay) {
        if (start == null) {
            throw new IllegalArgumentException("start must not be null");
        }
        if (end == null) {
            throw new IllegalArgumentException("end must not be null");
        }
        this.allDay = isAllDay;
        if (isAllDay) {
            Instant dayStart = start.atZone(ZoneOffset.UTC).toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            this.reservationStart = dayStart;
            this.reservationEnd = dayStart.plus(java.time.Duration.ofDays(1));
        } else {
            this.reservationStart = start;
            this.reservationEnd = end;
        }
        validateDuration();
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

    /**
     * Rejects a timed reservation whose {@code end} is not strictly after its
     * {@code start} (incl. the empty-range degenerate {@code start == end},
     * which produces a GiST-invisible empty {@code tstzrange}). All-day spans
     * are full-day by construction, so they always satisfy {@code end > start}.
     */
    public void validateDuration() {
        Instant start = effectiveStart();
        Instant end = effectiveEnd();
        if (!end.isAfter(start)) {
            throw new InvalidReservationDurationException(
                    "reservation end (" + end + ") must be strictly after start ("
                            + start + ")");
        }
    }

    /**
     * Half-open overlap on the SAME aircraft against {@code other}:
     * {@code this.start < other.end && other.start < this.end}. Adjacent
     * ranges ({@code end == next.start}) do NOT conflict. Returns {@code false}
     * for a different aircraft, for either side soft-deleted, and for the same
     * id (self-exclusion — an edit does not conflict with itself). Both sides
     * compare their <em>effective</em> span so all-day vs timed compose
     * correctly.
     */
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

    /**
     * True when both reservations carry the same non-null id — the
     * self-exclusion key used by {@link #conflictsWith} (and mirrored by the
     * service / repository {@code excludeId} on update).
     */
    public boolean sameIdentity(AircraftReservation other) {
        return this.id != null && Objects.equals(this.id, other.id);
    }

    /**
     * Effective span start used for overlap + duration. For timed this is the
     * stored {@code reservation_start}; for all-day it is the day's
     * {@code 00:00} (already normalised at {@link #reschedule}).
     */
    public Instant effectiveStart() {
        return Objects.requireNonNull(reservationStart, "reservationStart not set");
    }

    /**
     * Effective span end (exclusive — half-open). For all-day this is the next
     * day's {@code 00:00}.
     */
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
        String trimmed = value == null ? null : value.strip();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > MAX_INFO_LENGTH) {
            throw new IllegalArgumentException(
                    "info exceeds " + MAX_INFO_LENGTH + " characters");
        }
        this.info = trimmed;
    }

    /**
     * Test-only id stamp so domain unit tests can exercise self-exclusion
     * without a persistence round-trip. Not on the production write path —
     * JPA assigns the id at persist.
     */
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
