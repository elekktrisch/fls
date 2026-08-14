package ch.alpenflight.persons.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class PersonsAuthorizationIT extends PostgresIntegrationTest {

    private static final String TEST_NAME_PREFIX = "IT_PAU_";
    private static final String TEST_KEY_PREFIX = "IT_PA_";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;
    private String clubAAdminToken;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, TEST_NAME_PREFIX, TEST_KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        clubAAdminToken = jwts.mint(c -> c
                .claim("clubId", clubA.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void cross_tenant_get_returns_404_not_403() {
        UUID personId = UUID.fromString("019e30c3-2c00-7001-8000-00000000aaaa");
        UUID personClubId = UUID.fromString("019e30c3-2c00-7001-8000-00000000bbbb");
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "OnlyInB", "Smith");
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                personClubId.toString(), personId.toString(), clubB.toString());

        ResponseEntity<String> res = get("/api/v1/persons/pn-" + personId);
        assertThat(res.getStatusCode())
                .as("404 not 403: existence of cross-tenant Persons must stay opaque to other tenants")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void list_excludes_persons_not_in_callers_tenant() {
        UUID personA = UUID.fromString("019e30c3-2c00-7001-8000-00000000a01a");
        UUID pcA = UUID.fromString("019e30c3-2c00-7001-8000-00000000a02a");
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personA.toString(), "AnnaA", "Smith");
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                pcA.toString(), personA.toString(), clubA.toString());

        UUID personB = UUID.fromString("019e30c3-2c00-7001-8000-00000000b01b");
        UUID pcB = UUID.fromString("019e30c3-2c00-7001-8000-00000000b02b");
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personB.toString(), "BobB", "Jones");
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                pcB.toString(), personB.toString(), clubB.toString());

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
