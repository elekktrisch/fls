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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class LocationTypeControllerIT extends PostgresIntegrationTest {

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
    void listLocationTypes_returns_200_with_six_seeded_rows() {
        ResponseEntity<String> res = get("/api/v1/location-types");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size())
                .as("V3 seeds 6 location_type rows")
                .isEqualTo(6);
    }

    @Test
    void listLocationTypes_payload_carries_id_code_description_isAirfield() {
        ResponseEntity<String> res = get("/api/v1/location-types");
        JsonNode first = readJson(res).get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.get("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(first.has("code")).isTrue();
        assertThat(first.has("description")).isTrue();
        assertThat(first.has("isAirfield")).isTrue();
        assertThat(first.get("isAirfield").isBoolean()).isTrue();
    }

    @Test
    void listLocationTypes_sortsByDescriptionAscending() {
        ResponseEntity<String> res = get("/api/v1/location-types");
        List<String> descriptions = new ArrayList<>();
        readJson(res).forEach(n -> descriptions.add(n.get("description").asText()));
        List<String> sorted = new ArrayList<>(descriptions);
        sorted.sort(String::compareTo);
        assertThat(descriptions).containsExactlyElementsOf(sorted);
    }

    @Test
    void listLocationTypes_anonymous_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/location-types")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listLocationTypes_returns_identical_rows_under_two_different_tenant_claims() {
        String tokenA = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-0000000000a1")
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR"))));
        String tokenB = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-0000000000a2")
                .claim("realm_access", Map.of("roles", List.of("FLIGHT_OPERATOR"))));

        ResponseEntity<String> rA = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/location-types"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA).build(),
                String.class);
        ResponseEntity<String> rB = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/location-types"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB).build(),
                String.class);

        List<String> idsA = collectIds(rA);
        List<String> idsB = collectIds(rB);
        assertThat(idsA)
                .as("Reference-data reads must surface the IDENTICAL row set across tenant claims")
                .containsExactlyElementsOf(idsB);
    }

    @Test
    void postLocationTypes_returns_405_method_not_allowed() {
        ResponseEntity<String> res = rest.exchange(
                authed(RequestEntity.post(URI.create("/api/v1/location-types"))
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
            throw new IllegalStateException("Failed to parse: " + res.getBody(), e);
        }
    }

    private static List<String> collectIds(ResponseEntity<String> res) {
        List<String> ids = new ArrayList<>();
        readJson(res).forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }
}
