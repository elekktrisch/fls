package ch.alpenflight.flighttypes.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * FlightCostBalanceType GET — cross-tenant reference catalogue. Any
 * authenticated principal can read regardless of role (S-047 pattern).
 * Returns the 5 V3-seeded rows, ordered by legacy_int_id.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class FlightCostBalanceTypesControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_A = "019e30c3-2c00-7001-8000-000000000001";

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;

    @Test
    void list_returns_5_seedRows_orderedByLegacyId() {
        String token = mintToken(CLUB_A, "CLUB_ADMINISTRATOR");
        ResponseEntity<String> res = get("/api/v1/flight-cost-balance-types", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        // V3 seeds 5 rows (PILOT_PAYS_ALL, FIFTY_FIFTY_PILOT_COPILOT,
        // TOW_PILOT_PAYS_TOW, NO_INSTRUCTOR_FEE, INVOICE_TO_PERSON).
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.get(0).get("code").asText()).isEqualTo("PILOT_PAYS_ALL");
        assertThat(body.get(4).get("code").asText()).isEqualTo("INVOICE_TO_PERSON");
    }

    @ParameterizedTest(name = "{0} reads FCBT catalogue")
    @MethodSource("roleMatrix")
    void any_authenticated_role_can_read(String role, String clubId) {
        String token = mintToken(clubId, role);
        ResponseEntity<String> res = get("/api/v1/flight-cost-balance-types", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static Stream<Arguments> roleMatrix() {
        return Stream.of(
                Arguments.of("CLUB_ADMINISTRATOR", CLUB_A),
                Arguments.of("FLIGHT_OPERATOR", CLUB_A),
                Arguments.of("OFFICE_USER", CLUB_A),
                // Sysadmin has no clubId claim — FCBT is cross-tenant, so the
                // missing clubId is fine for reads.
                Arguments.of("SYSTEM_ADMINISTRATOR", null)
        );
    }

    @Test
    void list_unauthenticated_returns_401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/flight-cost-balance-types")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String mintToken(String clubId, String role) {
        Consumer<com.nimbusds.jwt.JWTClaimsSet.Builder> body = c -> {
            if (clubId != null) {
                c.claim("clubId", clubId);
            }
            c.claim("realm_access", Map.of("roles", List.of(role)));
        };
        return jwts.mint(body);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
