package ch.alpenflight.persons.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.net.URI;
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
 * Contract assertions that the four S-051 endpoint families exist. Drives
 * the implementation: until the module ships, every assertion fails with
 * a {@code 404 Not Found} (the route doesn't resolve), which is the right
 * reason for the red state. Full happy/edge/error coverage lives in
 * {@code PersonsControllerIT} + {@code PersonsTenantIsolationIT} +
 * {@code PersonsAuthorizationIT} once the slice is green.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class PersonsContractIT extends PostgresIntegrationTest {

    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;

    private String clubAdminToken;

    @BeforeEach
    void mintToken() {
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", CLUB_ID)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void listPersons_endpoint_is_wired() {
        ResponseEntity<String> res = get("/api/v1/persons");
        assertThat(res.getStatusCode())
                .as("GET /api/v1/persons must resolve")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void lookupPerson_endpoint_is_wired() {
        Map<String, Object> body = Map.of("email", "nobody@example.test");
        ResponseEntity<String> res = post("/api/v1/persons/lookup", body);
        assertThat(res.getStatusCode())
                .as("POST /api/v1/persons/lookup must resolve (empty matches = 200 with empty array)")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void createPerson_endpoint_is_wired() {
        Map<String, Object> body = Map.of(
                "firstname", "Ada",
                "lastname", "Lovelace");
        ResponseEntity<String> res = post("/api/v1/persons", body);
        assertThat(res.getStatusCode())
                .as("POST /api/v1/persons must resolve")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void memberStates_endpoint_is_wired() {
        ResponseEntity<String> res = get("/api/v1/club/member-states");
        assertThat(res.getStatusCode())
                .as("GET /api/v1/club/member-states must resolve")
                .isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(
                authed(RequestEntity.get(URI.create(path))).build(),
                String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.exchange(
                authed(RequestEntity.post(URI.create(path))
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
