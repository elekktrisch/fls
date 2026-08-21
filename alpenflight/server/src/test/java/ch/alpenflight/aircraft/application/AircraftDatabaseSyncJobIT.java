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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class AircraftDatabaseSyncJobIT extends PostgresIntegrationTest {

    private static final String IMMATRICULATION_WE_OWN_AND_THE_FIXTURE_KNOWS = "HB-3000";

    private static final String IMMATRICULATION_ONLY_THE_FIXTURE_KNOWS = "HB-9999";

    private static final String IMMATRICULATION_THE_FIXTURE_GIVES_A_REJECTED_COMPETITION_SIGN =
            "HB-9998";

    private static final String REJECTED_COMPETITION_SIGN_MESSAGE = "competitionSign must match";

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
        dropSeeded(IMMATRICULATION_WE_OWN_AND_THE_FIXTURE_KNOWS,
                IMMATRICULATION_ONLY_THE_FIXTURE_KNOWS,
                IMMATRICULATION_THE_FIXTURE_GIVES_A_REJECTED_COMPETITION_SIGN);
    }

    @AfterEach
    void dropSeededAircraftBecauseImmatriculationIsGloballyUniqueAcrossTenants() {
        dropSeeded(seeded.toArray(new String[0]));
    }

    private void dropSeeded(String... immatriculations) {
        for (String immatriculation : immatriculations) {
            jdbc.update("DELETE FROM t_aircraft WHERE immatriculation = ?", immatriculation);
        }
    }

    @Test
    void runOnce_updatesTheMatchedAircraft_andNeverCreatesOne() {
        UUID matched = seedAircraft(IMMATRICULATION_WE_OWN_AND_THE_FIXTURE_KNOWS);

        AircraftDatabaseSyncJob.RunSummary summary = job.runOnce();

        Aircraft synced = aircraft.findActiveById(matched).orElseThrow();
        assertThat(synced.getFlarmId()).isEqualTo("DD1234");
        assertThat(synced.getAircraftModel()).isEqualTo("ASK-21");
        assertThat(synced.getCompetitionSign()).isEqualTo("7X");
        assertThat(summary.updatedCount()).isGreaterThanOrEqualTo(1);

        assertThat(countOf(IMMATRICULATION_ONLY_THE_FIXTURE_KNOWS))
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


    @Test
    void runScheduled_rollsBackTheAircraftItAlreadySaved_whenALaterAircraftOfTheSameRunFails() {
        UUID savedBeforeTheFailure = seedAircraft(IMMATRICULATION_WE_OWN_AND_THE_FIXTURE_KNOWS);
        UUID rejectedLaterInTheSameRun =
                seedAircraft(IMMATRICULATION_THE_FIXTURE_GIVES_A_REJECTED_COMPETITION_SIGN);

        try {
            job.runScheduled();
        } catch (RuntimeException failureThatReachedTheScheduledEntryPoint) {
            assertThat(failureThatReachedTheScheduledEntryPoint)
                    .hasMessageContaining(REJECTED_COMPETITION_SIGN_MESSAGE);
        }

        assertThat(registryValuesOf(savedBeforeTheFailure))
                .as("the scheduled entry point must open a real transaction, so the aircraft that "
                        + "the run saved before the failure keeps no registry value")
                .containsOnlyNulls();
        assertThat(registryValuesOf(rejectedLaterInTheSameRun))
                .as("the aircraft that the domain rejected keeps no registry value either")
                .containsOnlyNulls();
    }

    private List<@Nullable String> registryValuesOf(UUID aircraftId) {
        Aircraft found = aircraft.findActiveById(aircraftId).orElseThrow();
        return Arrays.asList(
                found.getFlarmId(), found.getAircraftModel(), found.getCompetitionSign());
    }

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
