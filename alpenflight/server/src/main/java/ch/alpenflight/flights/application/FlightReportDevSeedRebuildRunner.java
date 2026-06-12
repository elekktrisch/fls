package ch.alpenflight.flights.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-profile startup backfill of the flight-report read-model (J-7 RM-2).
 * The V36 dev seed INSERTs flights for {@code seed-club-1} directly in SQL —
 * they never pass {@code FlightRepository.save}, so the synchronous
 * {@link FlightReportProjector} never sees them and a fresh dev / e2e
 * bring-up would serve an empty report for the seed club. This runner closes
 * that gap with one idempotent {@link FlightReportRebuildService} pass at
 * boot (the {@code ShowcaseSeedRunner} ApplicationRunner precedent, gated the
 * same way).
 *
 * <p>{@code @Profile("dev")}: prod never pays the rebuild (its flights arrive
 * via migration ingest, which triggers its own per-club rebuild), and the IT
 * bootstrap (test profile) stays lean per ADR 0021 — ITs that need rows seed
 * flights through production code, which projects synchronously. The
 * showcase clubs are covered by {@code ShowcaseSeeder}'s own rebuild call.
 */
@Component
@Profile("dev")
public class FlightReportDevSeedRebuildRunner implements ApplicationRunner {

    private static final Logger LOG =
            LoggerFactory.getLogger(FlightReportDevSeedRebuildRunner.class);

    /**
     * The canonical dev/test club from {@code V5__clubs_walking_skeleton.sql}
     * — the only club the SQL dev seeds (V36) attach flights to.
     */
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
