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

@Entity
@Table(name = "t_flight_report_crew")
@IdClass(FlightReportCrewEntry.Pk.class)
public class FlightReportCrewEntry {

    @Id
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id", nullable = false)
    @SuppressWarnings("UnusedVariable")
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

    public static final class Pk implements Serializable {

        private static final long serialVersionUID = 1L;

        private @Nullable UUID row;
        private @Nullable UUID personId;
        private @Nullable UUID flightCrewTypeId;

        public Pk() {
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
