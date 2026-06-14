package ch.alpenflight.referencedata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Flight-crew-type catalog (PILOT_OR_STUDENT, CO_PILOT, FLIGHT_INSTRUCTOR,
 * PASSENGER, WINCH_OPERATOR, OBSERVER, FLIGHT_COST_INVOICE_RECIPIENT).
 * System-global; not tenant-scoped. Mapped to the V3 {@code t_flight_crew_type}
 * table; data lives in the V3 Flyway seed (7 rows; legacy_int_id ∈ {1..6, 10}).
 * Read-only.
 *
 * <p>FK-referenced by {@code flight_crew.flight_crew_type_id} (the flights
 * module holds the canonical UUIDs in {@code FlightCrewTypeIds}). Exposed as a
 * read-only reference catalog here so the accounting rule-filter edit form can
 * populate its {@code flightCrewTypes} match-list dropdown. {@code legacyIntId}
 * is exposed alongside the UUID id (the SPA match-list keys off the legacy int).
 */
@Entity
@Table(name = "t_flight_crew_type")
public class FlightCrewType {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 48)
    private String code = "";

    @Column(name = "legacy_int_id", nullable = false)
    private short legacyIntId;

    @Column(name = "description", nullable = false, length = 200)
    private String description = "";

    protected FlightCrewType() {
        // JPA.
    }

    public @Nullable UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public short getLegacyIntId() {
        return legacyIntId;
    }

    public String getDescription() {
        return description;
    }
}
