package ch.alpenflight.flights.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for the flight-report read-model rows (ADR 0027 §2).
 * Implemented by {@code ch.alpenflight.flights.infra.JpaFlightReportRowRepository}
 * with plain Spring Data JPA — every query rides the structural
 * {@code @TenantId} filter (ADR 0008); no native SQL.
 *
 * <p>Write access is reserved for {@code FlightReportProjector} (the
 * single sync seam off {@link FlightSaved}); the report query path adopts
 * the read side in RM-3.
 */
public interface FlightReportRowRepository {

    FlightReportRow save(FlightReportRow row);

    /** Row by flight id (crew eagerly fetched); empty cross-tenant. */
    Optional<FlightReportRow> findByFlightId(UUID flightId);

    void delete(FlightReportRow row);

    /**
     * Flight ids of rows whose tow block points at {@code towFlightId} —
     * the projector's reverse lookup to repair glider rows when their tow
     * flight is saved / unlinked / soft-deleted.
     */
    List<UUID> findFlightIdsByTowFlightId(UUID towFlightId);

    /**
     * Flight ids of rows whose {@code towed_glider_flight_id} back-reference
     * points at {@code towedGliderFlightId} — the projector's reverse lookup
     * to repair tow rows when their glider is saved / unlinked / soft-deleted.
     */
    List<UUID> findFlightIdsByTowedGliderFlightId(UUID towedGliderFlightId);
}
