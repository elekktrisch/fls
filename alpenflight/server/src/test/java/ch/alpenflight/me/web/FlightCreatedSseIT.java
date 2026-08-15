package ch.alpenflight.me.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(JwtTestFixture.class)
@TestPropertySource(properties = "alpenflight.sse.heartbeat-interval-ms=300")
class FlightCreatedSseIT extends PostgresIntegrationTest {

    private static final String SEED_CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";
    private static final String SEED_AIRCRAFT_TYPE_GLIDER = "019e2e15-2c00-7af9-8000-000000002af9";

    @LocalServerPort int port;
    @Autowired JwtTestFixture jwts;
    @Autowired JdbcTemplate jdbc;

    private final List<HttpClient> openClients = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeClients() {
        openClients.forEach(HttpClient::close);
        openClients.clear();
        jdbc.update("DELETE FROM t_flight WHERE operating_club_id = ?::uuid", SEED_CLUB_ID);
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id = ?::uuid "
                + "AND immatriculation LIKE 'HB-SSE%'", SEED_CLUB_ID);
    }

    @Test
    void creatingAFlight_deliversFlightCreatedSseToTheCreatingPrincipal() throws Exception {
        String sub = UUID.randomUUID().toString();
        String token = jwts.mint(c -> c
                .subject(sub)
                .claim("clubId", SEED_CLUB_ID)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        UUID aircraftId = seedAircraft();

        StreamReader reader = openStream(token);
        try {
            assertThat(reader.statusCode()).as("authenticated open → 200").isEqualTo(200);

            retryUntil(() -> {
                createFlight(token, aircraftId);
                return reader.lines().stream()
                        .anyMatch(l -> l.replace(" ", "").startsWith("event:flight.created"));
            });

            assertThat(reader.lines())
                    .as("flight.created SSE delivered to the creating principal with a flightId payload")
                    .anyMatch(l -> l.contains("\"flightId\""));
        } finally {
            reader.cancel();
        }
    }

    private UUID seedAircraft() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                                      immatriculation, is_towing_or_winch_required,
                                      is_towing_start_allowed, is_winch_start_allowed,
                                      is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, false, false, false, false, false, 2)
                """,
                id.toString(), SEED_CLUB_ID, SEED_CLUB_ID, SEED_AIRCRAFT_TYPE_GLIDER,
                "HB-SSE" + Integer.toHexString((int) (System.nanoTime() & 0xFFFF)));
        return id;
    }

    private void createFlight(String token, UUID aircraftId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightAircraftType", "GLIDER");
        body.put("aircraftId", "ac-" + aircraftId);
        body.put("flightDate", "2026-05-01");
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
        body.put("crew", List.of());
        try {
            HttpClient client = HttpClient.newHttpClient();
            client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/flights"))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("flight create failed", e);
        }
    }

    private static String toJson(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(jsonValue(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String jsonValue(Object v) {
        if (v instanceof Boolean || v instanceof Number) {
            return v.toString();
        }
        if (v instanceof List<?> list && list.isEmpty()) {
            return "[]";
        }
        return '"' + String.valueOf(v) + '"';
    }

    private static void retryUntil(BooleanSupplier attempt) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (attempt.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("flight.created SSE not received within 10s");
    }

    private StreamReader openStream(String token) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newSingleThreadExecutor())
                .build();
        openClients.add(client);
        HttpResponse<Stream<String>> res = client.sendAsync(
                        HttpRequest.newBuilder(URI.create(
                                        "http://localhost:" + port + "/api/v1/me/events"))
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofLines())
                .get(10, TimeUnit.SECONDS);
        return new StreamReader(res);
    }

    private static final class StreamReader {
        private final HttpResponse<Stream<String>> response;
        private final List<String> received = new CopyOnWriteArrayList<>();
        private final Thread pump;

        StreamReader(HttpResponse<Stream<String>> response) {
            this.response = response;
            this.pump = new Thread(() -> {
                try {
                    response.body().forEach(received::add);
                } catch (RuntimeException streamCancelledAtTeardown) {
                }
            }, "sse-it-pump");
            this.pump.setDaemon(true);
            this.pump.start();
        }

        int statusCode() {
            return response.statusCode();
        }

        List<String> lines() {
            return List.copyOf(received);
        }

        void cancel() {
            response.body().close();
            pump.interrupt();
        }
    }
}
