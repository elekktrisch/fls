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
        assertThat(body.size())
                .as("a floor, not a pin — V4 seeded eight filter types and V42 added two more")
                .isGreaterThanOrEqualTo(10);

        JsonNode first = body.get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.get("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(first.has("code")).isTrue();
        assertThat(first.has("name")).isTrue();
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
        assertThat(legacyIds)
                .as("the legacy ints the SPA drives its conditional sections off, "
                        + "plus the two V42 types")
                .contains(5, 10, 20, 30, 55);
        assertThat(first.get("legacyId").asInt())
                .as("ordered by legacy_int_id, so V42's DO_NOT_INVOICE (5) now leads")
                .isEqualTo(5);
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
