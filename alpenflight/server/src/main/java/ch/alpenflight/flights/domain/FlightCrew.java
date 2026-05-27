package ch.alpenflight.flights.domain;

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
import org.jspecify.annotations.Nullable;

/**
 * Aggregate-internal crew row under {@link Flight}. Managed only via the
 * parent aggregate's {@link Flight#replaceCrew} mutator; never persisted
 * standalone.
 *
 * <p>{@code person_id} is a cross-tenant reference (sacred-cow ride-through
 * per ADR 0008 + S-051): a Flight's pilot can be a Person whose only
 * PersonClub membership is in a different tenant. Hibernate's
 * {@code @TenantId} on the parent Flight does not propagate to the FK —
 * PK-load resolves Person regardless of the caller's tenant scope.
 */
@Entity
@Table(name = "t_flight_crew")
public class FlightCrew {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "flight_id", nullable = false)
    @SuppressWarnings("UnusedVariable") // JPA mappedBy target.
    private @Nullable Flight flight;

    @Column(name = "person_id", nullable = false)
    private UUID personId = new UUID(0L, 0L);

    @Column(name = "flight_crew_type_id", nullable = false)
    private UUID flightCrewTypeId = new UUID(0L, 0L);

    @Column(name = "begin_flight_datetime")
    private @Nullable Instant beginFlightDatetime;

    @Column(name = "end_flight_datetime")
    private @Nullable Instant endFlightDatetime;

    @Column(name = "begin_instruction_datetime")
    private @Nullable Instant beginInstructionDatetime;

    @Column(name = "end_instruction_datetime")
    private @Nullable Instant endInstructionDatetime;

    @Column(name = "nr_of_ldgs")
    private @Nullable Short nrOfLdgs;

    @Column(name = "nr_of_starts")
    private @Nullable Short nrOfStarts;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    protected FlightCrew() {
        // JPA.
    }

    FlightCrew(Flight flight,
               UUID personId,
               UUID flightCrewTypeId,
               @Nullable Instant beginFlightDatetime,
               @Nullable Instant endFlightDatetime,
               @Nullable Instant beginInstructionDatetime,
               @Nullable Instant endInstructionDatetime,
               @Nullable Short nrOfLdgs,
               @Nullable Short nrOfStarts) {
        if (flight == null) {
            throw new IllegalArgumentException("FlightCrew.flight must not be null");
        }
        if (personId == null) {
            throw new IllegalArgumentException("FlightCrew.personId must not be null");
        }
        if (flightCrewTypeId == null) {
            throw new IllegalArgumentException("FlightCrew.flightCrewTypeId must not be null");
        }
        if (nrOfLdgs != null && nrOfLdgs < 0) {
            throw new IllegalArgumentException("FlightCrew.nrOfLdgs must be >= 0");
        }
        if (nrOfStarts != null && nrOfStarts < 0) {
            throw new IllegalArgumentException("FlightCrew.nrOfStarts must be >= 0");
        }
        this.flight = flight;
        this.personId = personId;
        this.flightCrewTypeId = flightCrewTypeId;
        this.beginFlightDatetime = beginFlightDatetime;
        this.endFlightDatetime = endFlightDatetime;
        this.beginInstructionDatetime = beginInstructionDatetime;
        this.endInstructionDatetime = endInstructionDatetime;
        this.nrOfLdgs = nrOfLdgs;
        this.nrOfStarts = nrOfStarts;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public UUID getPersonId() {
        return personId;
    }

    public UUID getFlightCrewTypeId() {
        return flightCrewTypeId;
    }

    public @Nullable Instant getBeginFlightDatetime() {
        return beginFlightDatetime;
    }

    public @Nullable Instant getEndFlightDatetime() {
        return endFlightDatetime;
    }

    public @Nullable Instant getBeginInstructionDatetime() {
        return beginInstructionDatetime;
    }

    public @Nullable Instant getEndInstructionDatetime() {
        return endInstructionDatetime;
    }

    public @Nullable Short getNrOfLdgs() {
        return nrOfLdgs;
    }

    public @Nullable Short getNrOfStarts() {
        return nrOfStarts;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    void softDelete(Instant at) {
        if (this.deletedOn == null) {
            this.deletedOn = at;
        }
    }
}
