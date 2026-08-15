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

    public record RebuildResult(UUID clubId, int liveFlights, int orphanRowsDeleted) { }

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
                    orphans++;
                }
            }
            for (UUID flightId : affected) {
                projector.refresh(flightId);
            }
            return new RebuildResult(clubId, live.size(), orphans);
        }));
        if (result == null) {
            throw new IllegalStateException(
                    "flight-report rebuild returned no result for club " + clubId);
        }
        LOG.info("flight-report read-model rebuild: club={} liveFlights={} orphanRowsDeleted={}",
                result.clubId(), result.liveFlights(), result.orphanRowsDeleted());
        return result;
    }
}
