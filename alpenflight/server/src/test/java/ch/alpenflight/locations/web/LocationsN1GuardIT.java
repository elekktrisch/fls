package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

/**
 * Hibernate-Statistics-driven N+1 guard. Confirms:
 *
 * <ul>
 *   <li>{@code GET /api/v1/locations} issues exactly 1 SQL query regardless
 *       of how many Locations exist (no per-row IOP fetch on the list path).</li>
 *   <li>{@code GET /api/v1/locations/{id}} issues exactly 1 SQL query —
 *       proving the {@link org.springframework.data.jpa.repository.EntityGraph}
 *       on {@code findActiveByIdWithPoints} pulls the {@code inOutboundPoints}
 *       collection in the same round-trip as the parent row.</li>
 * </ul>
 *
 * <p>{@code spring.jpa.properties.hibernate.generate_statistics=true} is
 * pinned in {@code application-test.yml} so every RANDOM_PORT IT shares a
 * single cached Spring context — this class used to declare it via
 * {@code @TestPropertySource}, forking a separate context (~15 s boot).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class LocationsN1GuardIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JwtTestFixture jwts;

    private String clubAdminToken;

    @BeforeEach
    void mintClubAdminToken() {
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-000000000001")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void listLocations_issuesExactlyOneSqlStatement() throws Exception {
        // Seed three Locations with IOPs to make N+1 visible if it lurks.
        for (int i = 0; i < 3; i++) {
            Map<String, Object> payload = LocationsControllerIT.createPayload(
                    "N1 list " + LocationsControllerIT.suffix(),
                    LocationsControllerIT.uniqueIcao());
            payload.put("inOutboundPoints", List.of(
                    iop("p1"), iop("p2")));
            post("/api/v1/locations", payload);
        }

        Statistics stats = statistics();
        stats.clear();

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.get(URI.create("/api/v1/locations"))).build(),
                String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        long count = stats.getPrepareStatementCount();
        assertThat(count)
                .as("GET /api/v1/locations must issue exactly 1 prepared SQL statement; "
                        + "an N+1 leak would scale with row count")
                .isEqualTo(1L);
    }

    @Test
    void getLocationById_issuesExactlyOneSqlStatement_evenWithEagerIops() throws Exception {
        Map<String, Object> payload = LocationsControllerIT.createPayload(
                "N1 detail " + LocationsControllerIT.suffix(),
                LocationsControllerIT.uniqueIcao());
        payload.put("inOutboundPoints", List.of(
                iop("d1"), iop("d2"), iop("d3")));
        ResponseEntity<String> created = post("/api/v1/locations", payload);
        String externalId = MAPPER.readTree(created.getBody()).get("id").asText();

        Statistics stats = statistics();
        stats.clear();

        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.get(URI.create("/api/v1/locations/" + externalId))).build(),
                String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode body = MAPPER.readTree(res.getBody());
        assertThat(body.get("inOutboundPoints").size()).isEqualTo(3);

        long count = stats.getPrepareStatementCount();
        assertThat(count)
                .as("GET /api/v1/locations/{id} must issue exactly 1 prepared SQL statement; "
                        + "join-fetch / EntityGraph is in play")
                .isEqualTo(1L);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static Map<String, Object> iop(String name) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("pointName", name);
        return n;
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return rest.exchange(authed(
                        RequestEntity.post(URI.create(path))
                                .contentType(MediaType.APPLICATION_JSON))
                        .body(body),
                String.class);
    }

    private <T extends RequestEntity.BodyBuilder> T authed(T builder) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken);
        return builder;
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken);
    }
}
