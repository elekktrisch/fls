package ch.alpenflight.referencedata.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

/**
 * Full-stack HTTP integration test for the accounting reference-data read
 * surface (filter-types / unit-types / crew-types). Asserts each GET returns
 * the Flyway-seeded rows with the {@code legacyId} field populated — the
 * filter-types {@code legacyId} is load-bearing: the J-8 SPA drives its
 * conditional-section visibility off the legacy int.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AccountingReferenceControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;

    private String userToken;

    @BeforeEach
    void mintToken() {
        userToken = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-000000000001")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void filterTypes_returns_seedRows_each_with_legacyId_populated() {
        JsonNode body = readJson(get("/api/v1/accounting-rule-filter-types"));
        assertThat(body.isArray()).isTrue();
        // V4 seeded 8 rows (10,20,30,40,50,60,70,80); V42 (J-8 T-09) added
        // DO_NOT_INVOICE (5) + START_TAX (55) — assert the table, not a hardcoded
        // count.
        assertThat(body.size()).isGreaterThanOrEqualTo(10);

        // Ordered by legacy_int_id ASC; the first row is now DO_NOT_INVOICE
        // (legacyId 5), the lowest legacy enum value after the V42 seed.
        JsonNode first = body.get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.get("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(first.has("code")).isTrue();
        assertThat(first.has("name")).isTrue();
        // legacyId is the load-bearing field — present and non-zero on every row.
        body.forEach(n -> {
            assertThat(n.has("legacyId"))
                    .as("every filter-type row must carry legacyId for the SPA")
                    .isTrue();
            assertThat(n.get("legacyId").asInt())
                    .as("legacyId is the legacy enum int (5/10/20/30/55/…), never 0")
                    .isGreaterThan(0);
        });

        List<Integer> legacyIds = new ArrayList<>();
        body.forEach(n -> legacyIds.add(n.get("legacyId").asInt()));
        // The four section-driving ids the SPA keys off, plus the two V42 types.
        assertThat(legacyIds).contains(5, 10, 20, 30, 55);
        assertThat(first.get("legacyId").asInt()).isEqualTo(5);
        assertThat(first.get("code").asText()).isEqualTo("DO_NOT_INVOICE");
    }

    @Test
    void unitTypes_returns_4_seedRows_each_with_legacyId_populated() {
        JsonNode body = readJson(get("/api/v1/accounting-unit-types"));
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(4);

        JsonNode first = body.get(0);
        assertThat(first.get("code").asText()).isEqualTo("MINUTES");
        assertThat(first.get("legacyId").asInt()).isEqualTo(10);
        assertThat(first.has("shortName")).isTrue();
        body.forEach(n -> assertThat(n.get("legacyId").asInt()).isGreaterThan(0));
    }

    @Test
    void crewTypes_returns_7_seedRows_each_with_legacyId_populated() {
        JsonNode body = readJson(get("/api/v1/flight-crew-types"));
        assertThat(body.isArray()).isTrue();
        // V3 seeds 7 rows (legacy_int_id ∈ {1..6, 10}).
        assertThat(body.size()).isEqualTo(7);

        JsonNode first = body.get(0);
        assertThat(first.get("code").asText()).isEqualTo("PILOT_OR_STUDENT");
        assertThat(first.get("legacyId").asInt()).isEqualTo(1);
        assertThat(first.has("description")).isTrue();

        List<Integer> legacyIds = new ArrayList<>();
        body.forEach(n -> legacyIds.add(n.get("legacyId").asInt()));
        assertThat(legacyIds).containsExactly(1, 2, 3, 4, 5, 6, 10);
    }

    @Test
    void referenceData_anonymous_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/accounting-rule-filter-types")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .build(),
                String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
