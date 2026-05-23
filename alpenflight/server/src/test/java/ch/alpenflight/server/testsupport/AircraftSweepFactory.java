package ch.alpenflight.server.testsupport;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders.SweepFixtureContext;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal-object factory for {@link Aircraft} consumed by the S-024 leakage
 * sweep. Looks up the first seeded {@code aircraft_type} row; throws if
 * missing (sweep is meaningless without reference data).
 *
 * <p>Per-run immatriculation uniqueness is regulator-global ({@code
 * ux_aircraft_immatriculation}), so the suffix mixes nanoTime + a process
 * counter to keep cross-test runs isolated.
 */
final class AircraftSweepFactory {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private AircraftSweepFactory() {}

    static Aircraft build(SweepFixtureContext ctx) {
        UUID aircraftTypeId = firstId(ctx, "aircraft_type");
        String immatriculation = uniqueImmatriculation();
        // Ownership defaults to null at sweep time; managing_club_id is set
        // by the @TenantId resolver on save.
        return Aircraft.register(
                null,
                aircraftTypeId,
                immatriculation,
                "IT_SWP", "ASK-21",
                null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                false, false, false, false,
                "S-024 sweep fixture",
                null);
    }

    private static UUID firstId(SweepFixtureContext ctx, String table) {
        UUID id = ctx.jdbc().queryForObject("SELECT id FROM " + table + " LIMIT 1", UUID.class);
        if (id == null) {
            throw new IllegalStateException(
                    "No row in " + table + " — seed must populate at least one reference row");
        }
        return id;
    }

    private static String uniqueImmatriculation() {
        int n = COUNTER.incrementAndGet();
        // 15-char cap on immatriculation (V3 + Immatriculation VO). HB-Z + 8
        // base-36 nanoTime chars + 2-digit counter = 14, with one slack char.
        String stamp = Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        if (stamp.length() > 8) {
            stamp = stamp.substring(stamp.length() - 8);
        }
        return "HB-Z" + stamp + String.format(Locale.ROOT, "%02d", n % 100);
    }
}
