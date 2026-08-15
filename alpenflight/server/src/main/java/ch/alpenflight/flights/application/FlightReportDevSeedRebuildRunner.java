package ch.alpenflight.flights.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class FlightReportDevSeedRebuildRunner implements ApplicationRunner {

    private static final Logger LOG =
            LoggerFactory.getLogger(FlightReportDevSeedRebuildRunner.class);

    static final UUID SEED_CLUB_1 = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private final FlightReportRebuildService rebuild;

    public FlightReportDevSeedRebuildRunner(FlightReportRebuildService rebuild) {
        this.rebuild = rebuild;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("dev profile active — backfilling the flight-report read-model "
                + "for the SQL-seeded dev club");
        rebuild.rebuildForClub(SEED_CLUB_1);
    }
}
