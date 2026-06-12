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
 * single sync seam off {@link FlightSaved}); the report query path reads
 * the rows via {@link FlightReportRepository} (RM-3).
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

    /**
     * Flight ids of ALL rows visible under the caller's tenant — the
     * orphan-detection set of the read-model rebuild (J-7 RM-2): ids present
     * here but absent from the live flight set are stale rows to delete.
     */
    List<UUID> findAllFlightIds();

    /**
     * Flight ids of rows carrying a crew entry for {@code personId} (the
     * {@code t_flight_report_crew} child, {@code ix_frc_person}) — the
     * affected-flight lookup when a Person rename must refresh denormalized
     * read-model crew names (J-7 RM-2). The child carries no own
     * {@code @TenantId}; ids outside the caller's tenant are harmless to the
     * re-projection (every per-flight read is tenant-scoped and no-ops).
     */
    List<UUID> findFlightIdsByCrewPersonId(UUID personId);
}
