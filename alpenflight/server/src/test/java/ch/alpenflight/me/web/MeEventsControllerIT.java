package ch.alpenflight.me.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.me.application.MePrincipalEventBus;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(JwtTestFixture.class)
@TestPropertySource(properties = "alpenflight.sse.heartbeat-interval-ms=300")
class MeEventsControllerIT extends PostgresIntegrationTest {

    @LocalServerPort int port;
    @Autowired JwtTestFixture jwts;
    @Autowired MePrincipalEventBus eventBus;

    private final List<HttpClient> openClients = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeClients() {
        openClients.forEach(HttpClient::close);
        openClients.clear();
    }

    @Test
    void authenticatedStream_returnsEventStream_andReceivesPublishedEvent() throws Exception {
        String sub = UUID.randomUUID().toString();
        String token = jwts.mint(c -> c
                .subject(sub)
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));

        StreamReader reader = openStream(token);
        try {
            assertThat(reader.statusCode())
                    .as("authenticated open → 200")
                    .isEqualTo(200);
            assertThat(reader.contentType())
                    .as("SSE content type")
                    .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);

            waitUntil(() -> {
                eventBus.publish(sub, "flight.created", Map.of("flightId", "f-1"));
                return reader.lines().stream()
                        .anyMatch(l -> l.replace(" ", "").startsWith("event:flight.created"));
            });

            assertThat(reader.lines())
                    .as("payload JSON delivered on a data: line")
                    .anyMatch(l -> l.contains("\"flightId\":\"f-1\""));
        } finally {
            reader.cancel();
        }
    }

    @Test
    void idleStream_receivesHeartbeatComment() throws Exception {
        String sub = UUID.randomUUID().toString();
        String token = jwts.mint(c -> c
                .subject(sub)
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));

        StreamReader reader = openStream(token);
        try {
            assertThat(reader.statusCode()).isEqualTo(200);
            waitUntil(() -> reader.lines().stream().anyMatch(l -> l.startsWith(":")));
            assertThat(reader.lines())
                    .as("heartbeat comment on an idle stream")
                    .anyMatch(l -> l.startsWith(":"));
        } finally {
            reader.cancel();
        }
    }

    @Test
    void noToken_returns401_andNoStream() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        openClients.add(client);
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode())
                .as("no token → 401, resource server rejects before the stream opens")
                .isEqualTo(401);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition not met within 5s");
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/me/events";
    }

    private StreamReader openStream(String token) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newSingleThreadExecutor())
                .build();
        openClients.add(client);
        HttpResponse<Stream<String>> res = client.sendAsync(
                        HttpRequest.newBuilder(URI.create(baseUrl()))
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
                } catch (RuntimeException e) {
                }
            }, "sse-it-pump");
            this.pump.setDaemon(true);
            this.pump.start();
        }

        int statusCode() {
            return response.statusCode();
        }

        String contentType() {
            return response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("");
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
