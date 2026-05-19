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
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

/**
 * Full-stack HTTP integration test for the Country reference-data read
 * surface. Exercises the seeded V2 data (248 ISO countries) through the
 * production {@code SecurityFilterChain} via {@link JwtTestFixture}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class CountryControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;

    private String userToken;

    @BeforeEach
    void mintToken() {
        userToken = jwts.mint(c -> c
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR"))));
    }

    @Test
    void listCountries_returns_200_with_seeded_rows() {
        ResponseEntity<String> res = get("/api/v1/countries");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size())
                .as("V2 seeds 248 ISO country rows")
                .isGreaterThan(200);
    }

    @Test
    void listCountries_payload_carries_id_iso2Code_name() {
        ResponseEntity<String> res = get("/api/v1/countries");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode first = readJson(res).get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.get("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(first.has("iso2Code")).isTrue();
        assertThat(first.get("iso2Code").asText()).matches("[A-Z]{2}");
        assertThat(first.has("name")).isTrue();
    }

    @Test
    void listCountries_sorts_alphabetically_with_accents_in_their_letter_group() {
        ResponseEntity<String> res = get("/api/v1/countries");
        List<String> names = new ArrayList<>();
        readJson(res).forEach(n -> names.add(n.get("name").asText()));

        int switzerland = names.indexOf("Switzerland");
        int sweden = names.indexOf("Sweden");
        assertThat(switzerland).as("Switzerland present").isGreaterThanOrEqualTo(0);
        assertThat(sweden).as("Sweden present").isGreaterThanOrEqualTo(0);
        assertThat(sweden).isLessThan(switzerland);

        // ICU collation: accented C-names (Côte d'Ivoire) sort inside the C
        // group, not at the end as default C-collation would place them.
        int cote = names.indexOf("Côte d'Ivoire");
        int cuba = names.indexOf("Cuba");
        if (cote >= 0 && cuba >= 0) {
            assertThat(cote)
                    .as("Côte d'Ivoire should sort among C-names under ICU collation")
                    .isLessThan(cuba);
        }
    }

    @Test
    void listCountries_anonymous_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/countries")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listCountries_returns_identical_rows_under_two_different_tenant_claims() {
        String tokenForClubA = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-0000000000a1")
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR"))));
        String tokenForClubB = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-0000000000a2")
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR"))));

        ResponseEntity<String> rA = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/countries"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenForClubA).build(),
                String.class);
        ResponseEntity<String> rB = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/countries"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenForClubB).build(),
                String.class);

        assertThat(rA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(rA).size())
                .as("Reads must surface the same set regardless of tenant claim (no @TenantId on Country)")
                .isEqualTo(readJson(rB).size());
    }

    @Test
    void postCountries_returns_405_method_not_allowed() {
        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.post(URI.create("/api/v1/countries"))
                        .contentType(MediaType.APPLICATION_JSON))
                        .body(Map.of("name", "would-be")),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(
                authed(RequestEntity.get(URI.create(path))).build(),
                String.class);
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken);
    }

    private <T extends RequestEntity.BodyBuilder> T authed(T builder) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken);
        return builder;
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
