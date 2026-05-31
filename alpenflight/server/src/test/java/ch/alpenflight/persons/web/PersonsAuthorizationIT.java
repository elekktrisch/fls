package ch.alpenflight.persons.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The IDOR contract: a CLUB_ADMINISTRATOR who tries to read a Person whose
 * only PersonClub is in another tenant receives 404, never 403. 403 leaks
 * existence — the Person whose only home is club B should be opaque to
 * club A's admins.
 *
 * <p>Counterpart to {@code LocationsAuthorizationIT}'s
 * {@code club_admin_cross_tenant_sees_404_not_403}; closes the security-plan
 * §"404 vs 403" row.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class PersonsAuthorizationIT extends PostgresIntegrationTest {

    private static final String CLUB_A_LITERAL = "019e30c3-2c00-7001-8000-0000000000a3";
    private static final String CLUB_B_LITERAL = "019e30c3-2c00-7001-8000-0000000000a4";
    private static final UUID CLUB_A = UUID.fromString(CLUB_A_LITERAL);
    private static final UUID CLUB_B = UUID.fromString(CLUB_B_LITERAL);

    private static final String TEST_NAME_PREFIX = "IT_PAU_";
    private static final String TEST_KEY_PREFIX = "IT_PA_";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String clubAAdminToken;

    @BeforeEach
    void seed() {
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, TEST_NAME_PREFIX, TEST_KEY_PREFIX).seed();
        clubAAdminToken = jwts.mint(c -> c
                .claim("clubId", CLUB_A_LITERAL)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void cross_tenant_get_returns_404_not_403() {
        // Seed a Person whose only PersonClub is under CLUB_B via JDBC; the
        // PersonsService path can't do this directly because @TenantId scopes
        // the join through PersonClub, and we want to construct the precise
        // "Person exists, but not in caller's tenant" state.
        UUID personId = UUID.fromString("019e30c3-2c00-7001-8000-00000000aaaa");
        UUID personClubId = UUID.fromString("019e30c3-2c00-7001-8000-00000000bbbb");
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "OnlyInB", "Smith");
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                personClubId.toString(), personId.toString(), CLUB_B.toString());

        // CLUB_A admin asks for the Person. The service finds it by PK
        // (cross-tenant) but `hasActiveMembershipInCurrentTenant` returns
        // false; service throws PersonNotFoundException → handler → 404.
        ResponseEntity<String> res = get("/api/v1/persons/pn-" + personId);
        assertThat(res.getStatusCode())
                .as("404 not 403: existence of cross-tenant Persons must stay opaque to other tenants")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void list_excludes_persons_not_in_callers_tenant() {
        // One Person attached to CLUB_A, one Person attached to CLUB_B.
        // CLUB_A admin listing /persons must see only the CLUB_A row.
        UUID personA = UUID.fromString("019e30c3-2c00-7001-8000-00000000a01a");
        UUID pcA = UUID.fromString("019e30c3-2c00-7001-8000-00000000a02a");
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personA.toString(), "AnnaA", "Smith");
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                pcA.toString(), personA.toString(), CLUB_A.toString());

        UUID personB = UUID.fromString("019e30c3-2c00-7001-8000-00000000b01b");
        UUID pcB = UUID.fromString("019e30c3-2c00-7001-8000-00000000b02b");
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personB.toString(), "BobB", "Jones");
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                pcB.toString(), personB.toString(), CLUB_B.toString());

        ResponseEntity<String> res = get("/api/v1/persons");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = res.getBody();
        assertThat(body)
                .as("CLUB_A's list should contain AnnaA (in CLUB_A) and exclude BobB (in CLUB_B only)")
                .contains("AnnaA")
                .doesNotContain("BobB");
    }

    @Test
    void anonymous_returns_401_on_list() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/persons")).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(
                authed(RequestEntity.get(URI.create(path))).build(),
                String.class);
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> b) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAAdminToken);
    }

    @SuppressWarnings("unused")
    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
