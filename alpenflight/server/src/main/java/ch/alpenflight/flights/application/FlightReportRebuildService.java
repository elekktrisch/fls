package ch.alpenflight.flights.application;

import ch.alpenflight.flights.domain.FlightReportRowRepository;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.tenancy.Tenants;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent per-club rebuild / backfill of the flight-report read-model
 * (J-7 RM-2, ADR 0027 §2). Covers every flight that did NOT arrive through
 * {@code FlightRepository.save} — and therefore never hit the synchronous
 * {@link FlightReportProjector}:
 *
 * <ul>
 *   <li><strong>Migration ingest</strong> — legacy flights land via JDBC
 *       COPY/INSERT inside the bundle transaction
 *       ({@code MigrationBundleIngestService} calls this right after that
 *       transaction commits);</li>
 *   <li><strong>SQL dev seeds</strong> — V36-seeded flights
 *       ({@code FlightReportDevSeedRebuildRunner} at dev startup);</li>
 *   <li><strong>Showcase seeder</strong> — JDBC base inserts under
 *       deterministic ids ({@code ShowcaseSeeder} calls this at the end of
 *       its seed run).</li>
 * </ul>
 *
 * <p>Mechanism: under {@link Tenants#runAs} for the club, one transaction per
 * club iterates the union of the club's live flight ids and its existing
 * read-model row ids, re-projecting each through the projector's
 * {@link FlightReportProjector#refresh} seam — the SAME projection logic the
 * save-time sync uses, never a duplicate. Live flights get rows created /
 * refreshed; ids whose flight is gone or soft-deleted get their orphaned row
 * deleted. Running twice is a no-op by construction.
 *
 * <p>The {@code runAs} wrap sits OUTSIDE the {@link TransactionTemplate} so
 * Hibernate resolves the club as the session tenant at session-open — the
 * {@code @TenantId} filter scopes every read, and row inserts are stamped
 * with the club (the ShowcaseSeeder phase-B lesson: a session opened before
 * the tenant push stays {@code NO_TENANT} for its lifetime).
 */
@Component
public class FlightReportRebuildService {

    private static final Logger LOG = LoggerFactory.getLogger(FlightReportRebuildService.class);

    private final FlightRepository flights;
    private final FlightReportRowRepository rows;
    private final FlightReportProjector projector;
    private final TransactionTemplate txTemplate;

    public FlightReportRebuildService(FlightRepository flights,
                                      FlightReportRowRepository rows,
                                      FlightReportProjector projector,
                                      PlatformTransactionManager txManager) {
        this.flights = flights;
        this.rows = rows;
        this.projector = projector;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /** Outcome of one club rebuild — counts for the caller's log line. */
    public record RebuildResult(UUID clubId, int liveFlights, int orphanRowsDeleted) { }

    /**
     * Rebuilds the read-model for one club, in one dedicated transaction.
     * Idempotent — safe to call from startup hooks, post-ingest, and seed
     * re-runs alike.
     */
    public RebuildResult rebuildForClub(UUID clubId) {
        if (clubId == null) {
            throw new IllegalArgumentException("clubId must not be null");
        }
        RebuildResult result = Tenants.runAs(clubId, () -> txTemplate.execute(status -> {
            List<UUID> live = flights.findAllLiveIds();
            Set<UUID> affected = new LinkedHashSet<>(live);
            List<UUID> existingRows = rows.findAllFlightIds();
            int orphans = 0;
            for (UUID rowId : existingRows) {
                if (affected.add(rowId)) {
                    // In the row set but not in the live flight set — the
                    // refresh below resolves it to a delete.
                    orphans++;
                }
            }
            for (UUID flightId : affected) {
                projector.refresh(flightId);
            }
            return new RebuildResult(clubId, live.size(), orphans);
        }));
        if (result == null) {
            // TransactionTemplate.execute returns null only when the callback
            // does — which it cannot here. Defensive (matches the ingest
            // service's posture).
            throw new IllegalStateException(
                    "flight-report rebuild returned no result for club " + clubId);
        }
        LOG.info("flight-report read-model rebuild: club={} liveFlights={} orphanRowsDeleted={}",
                result.clubId(), result.liveFlights(), result.orphanRowsDeleted());
        return result;
    }
}
