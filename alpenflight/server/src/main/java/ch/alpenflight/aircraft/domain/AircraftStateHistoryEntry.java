package ch.alpenflight.aircraft.domain;

import ch.alpenflight.audit.domain.AuditRedact;
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
 * Aggregate-internal child of {@link Aircraft}: a single airworthiness
 * state period. The state "current" row is the one with
 * {@code validTo == null}; closing it sets {@code validTo} to the new
 * period's {@code validFrom}. Composite-shape parity with legacy
 * {@code AircraftAircraftState}, but the new schema gives every row a
 * UUID surrogate PK (V3 ships {@code id UUID PRIMARY KEY}).
 *
 * <p>No top-level CRUD endpoint exists. Mutators are package-private;
 * only {@link Aircraft} drives them via {@code changeState(...)}.
 */
@Entity
@Table(name = "t_aircraft_aircraft_state")
public class AircraftStateHistoryEntry {

    private static final int MAX_REMARKS_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "aircraft_id", nullable = false)
    @SuppressWarnings("UnusedVariable")
    private @Nullable Aircraft aircraft;

    @Column(name = "aircraft_state_id", nullable = false)
    private @Nullable UUID aircraftStateId;

    @Column(name = "valid_from", nullable = false)
    private @Nullable Instant validFrom;

    @Column(name = "valid_to")
    private @Nullable Instant validTo;

    @Column(name = "noticed_by_person_id")
    @AuditRedact
    private @Nullable UUID noticedByPersonId;

    @Column(name = "remarks")
    @AuditRedact
    private @Nullable String remarks;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    protected AircraftStateHistoryEntry() {
        // JPA.
    }

    static AircraftStateHistoryEntry open(UUID aircraftStateId,
                                          Instant validFrom,
                                          @Nullable UUID noticedByPersonId,
                                          @Nullable String remarks) {
        if (aircraftStateId == null) {
            throw new IllegalArgumentException("aircraftStateId must not be null");
        }
        if (validFrom == null) {
            throw new IllegalArgumentException("validFrom must not be null");
        }
        AircraftStateHistoryEntry e = new AircraftStateHistoryEntry();
        e.aircraftStateId = aircraftStateId;
        e.validFrom = validFrom;
        e.noticedByPersonId = noticedByPersonId;
        e.remarks = capRemarks(blankToNull(remarks));
        return e;
    }

    private static @Nullable String capRemarks(@Nullable String value) {
        if (value != null && value.length() > MAX_REMARKS_LENGTH) {
            throw new IllegalArgumentException(
                    "remarks exceeds " + MAX_REMARKS_LENGTH + " characters");
        }
        return value;
    }

    void attachTo(Aircraft parent) {
        this.aircraft = parent;
    }

    void closeAt(Instant closingValidTo) {
        if (validFrom != null && closingValidTo.isBefore(validFrom)) {
            throw new AircraftStateConflictException(
                    "valid_to (" + closingValidTo + ") must not be before valid_from (" + validFrom + ")");
        }
        this.validTo = closingValidTo;
    }

    public boolean isOpen() {
        return validTo == null;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getAircraftStateId() {
        return aircraftStateId;
    }

    public @Nullable Instant getValidFrom() {
        return validFrom;
    }

    public @Nullable Instant getValidTo() {
        return validTo;
    }

    public @Nullable UUID getNoticedByPersonId() {
        return noticedByPersonId;
    }

    public @Nullable String getRemarks() {
        return remarks;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
