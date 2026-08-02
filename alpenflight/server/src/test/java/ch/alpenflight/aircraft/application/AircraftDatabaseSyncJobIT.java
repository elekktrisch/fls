package ch.alpenflight.aircraft.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration proof of the OGN device-database sync (S-088, J-15 AC #5) against a
 * recorded registry served over real HTTP — the client's transport and JSON
 * binding are exercised, not stubbed, but no live network is touched.
 *
 * <p>The fixture carries an entry for an aircraft we do not own, which is the
 * update-only assertion: the registry never creates aircraft.
 */
class AircraftDatabaseSyncJobIT extends PostgresIntegrationTest {

    /** Matches the fixture's {@code HB-3000} entry (dash-insensitively). */
    private static final String KNOWN_IMMATRICULATION = "HB-3000";

    /** In the fixture, never in our fleet. */
    private static final String UNKNOWN_IMMATRICULATION = "HB-9999";

    private static HttpServer ddbServer;

    @Autowired JdbcTemplate jdbc;
    @Autowired AircraftDatabaseSyncJob job;
    @Autowired AircraftRepository aircraft;

    @DynamicPropertySource
    static void pointClientAtTheFixture(DynamicPropertyRegistry registry) {
        registry.add("alpenflight.ogn.ddb-url", () -> "http://localhost:" + ddbPort() + "/ddb");
    }

    private static int ddbPort() {
        if (ddbServer == null) {
            ddbServer = startFixtureServer();
        }
        return ddbServer.getAddress().getPort();
    }

    @AfterAll
    static void stopFixtureServer() {
        if (ddbServer != null) {
            ddbServer.stop(0);
            ddbServer = null;
        }
    }

    private final List<String> seeded = new ArrayList<>();

    @BeforeEach
    void removeOurFixtureAircraft() {
        seeded.clear();
        dropSeeded(KNOWN_IMMATRICULATION, UNKNOWN_IMMATRICULATION);
    }

    /**
     * Immatriculation is globally unique across tenants, so a leftover row here
     * would collide with another spec's fixture — this test owns fixed
     * immatriculations, so it has to hand them back.
     */
    @AfterEach
    void dropSeededAircraft() {
        dropSeeded(seeded.toArray(new String[0]));
    }

    private void dropSeeded(String... immatriculations) {
        for (String immatriculation : immatriculations) {
            jdbc.update("DELETE FROM t_aircraft WHERE immatriculation = ?", immatriculation);
        }
    }

    @Test
    void runOnce_updatesTheMatchedAircraft_andNeverCreatesOne() {
        UUID matched = seedAircraft(KNOWN_IMMATRICULATION);

        AircraftDatabaseSyncJob.RunSummary summary = job.runOnce();

        Aircraft synced = aircraft.findActiveById(matched).orElseThrow();
        assertThat(synced.getFlarmId()).isEqualTo("DD1234");
        assertThat(synced.getAircraftModel()).isEqualTo("ASK-21");
        assertThat(synced.getCompetitionSign()).isEqualTo("7X");
        assertThat(summary.updatedCount()).isGreaterThanOrEqualTo(1);

        assertThat(countOf(UNKNOWN_IMMATRICULATION))
                .as("a registry entry we own no aircraft for is never created")
                .isZero();
    }

    @Test
    void runOnce_leavesAnAircraftTheRegistryDoesNotKnow() {
        UUID unknown = seedAircraft("HB-" + UUID.randomUUID().toString().substring(0, 4));

        AircraftDatabaseSyncJob.RunSummary summary = job.runOnce();

        Aircraft untouched = aircraft.findActiveById(unknown).orElseThrow();
        assertThat(untouched.getFlarmId()).isNull();
        assertThat(untouched.getCompetitionSign()).isNull();
        assertThat(summary.unmatchedCount()).isGreaterThanOrEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private long countOf(String immatriculation) {
        Long v = jdbc.queryForObject(
                "SELECT count(*) FROM t_aircraft WHERE immatriculation = ?",
                Long.class, immatriculation);
        return v == null ? 0 : v;
    }

    private UUID seedAircraft(String immatriculation) {
        seeded.add(immatriculation);
        UUID acType = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        UUID club = jdbc.queryForObject("SELECT id FROM t_club LIMIT 1", UUID.class);
        Aircraft craft = Aircraft.register(club, club, acType,
                immatriculation, null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
        return aircraft.save(craft).getId().value();
    }

    private static HttpServer startFixtureServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] body = readFixture();
            server.createContext("/ddb", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the OGN fixture server", e);
        }
    }

    private static byte[] readFixture() throws IOException {
        try (InputStream in = AircraftDatabaseSyncJobIT.class
                .getResourceAsStream("/ogn/ddb-sample.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /ogn/ddb-sample.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
