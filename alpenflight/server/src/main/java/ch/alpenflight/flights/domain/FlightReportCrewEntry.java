package ch.alpenflight.flights.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate-internal crew entry under {@link FlightReportRow} — one live
 * {@code (personId, flightCrewTypeId)} pair of the projected flight. Managed
 * only via {@link FlightReportRow}'s projection methods; never persisted
 * standalone. Serves the report's person filter and person-role summary
 * flags at query time (RM-3).
 *
 * <p>Composite PK {@code (flight_id, person_id, flight_crew_type_id)} —
 * identity IS the content; the projector reconciles by this key.
 *
 * <p>{@code operating_club_id} is a plain copied column, NOT {@code @TenantId}
 * (FlightCrew / PlanningDayAssignment child precedent): the parent row carries
 * the structural discriminator, and the value here is supplied by the
 * projector from the same resolver Hibernate stamps the parent with — keeping
 * this child out of the S-024 leakage-sweep roster (it has no repository).
 */
@Entity
@Table(name = "t_flight_report_crew")
@IdClass(FlightReportCrewEntry.Pk.class)
public class FlightReportCrewEntry {

    @Id
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id", nullable = false)
    @SuppressWarnings("UnusedVariable") // JPA mappedBy target + derived id.
    private @Nullable FlightReportRow row;

    @Id
    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId = new UUID(0L, 0L);

    @Id
    @Column(name = "flight_crew_type_id", nullable = false, updatable = false)
    private UUID flightCrewTypeId = new UUID(0L, 0L);

    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private UUID operatingClubId = new UUID(0L, 0L);

    protected FlightReportCrewEntry() {
        // JPA.
    }

    FlightReportCrewEntry(FlightReportRow row,
                          UUID personId,
                          UUID flightCrewTypeId,
                          UUID operatingClubId) {
        if (row == null) {
            throw new IllegalArgumentException("row must not be null");
        }
        if (personId == null) {
            throw new IllegalArgumentException("personId must not be null");
        }
        if (flightCrewTypeId == null) {
            throw new IllegalArgumentException("flightCrewTypeId must not be null");
        }
        if (operatingClubId == null) {
            throw new IllegalArgumentException("operatingClubId must not be null");
        }
        this.row = row;
        this.personId = personId;
        this.flightCrewTypeId = flightCrewTypeId;
        this.operatingClubId = operatingClubId;
    }

    public UUID getPersonId() {
        return personId;
    }

    public UUID getFlightCrewTypeId() {
        return flightCrewTypeId;
    }

    public UUID getOperatingClubId() {
        return operatingClubId;
    }

    /** Composite-PK class (JPA {@code @IdClass}; {@code row} = parent's id). */
    public static final class Pk implements Serializable {

        private static final long serialVersionUID = 1L;

        private @Nullable UUID row;
        private @Nullable UUID personId;
        private @Nullable UUID flightCrewTypeId;

        public Pk() {
            // JPA.
        }

        public Pk(UUID row, UUID personId, UUID flightCrewTypeId) {
            this.row = row;
            this.personId = personId;
            this.flightCrewTypeId = flightCrewTypeId;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pk other)) {
                return false;
            }
            return Objects.equals(row, other.row)
                    && Objects.equals(personId, other.personId)
                    && Objects.equals(flightCrewTypeId, other.flightCrewTypeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(row, personId, flightCrewTypeId);
        }
    }
}
