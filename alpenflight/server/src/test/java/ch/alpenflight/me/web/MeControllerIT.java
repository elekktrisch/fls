package ch.alpenflight.me.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
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
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class MeControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CLUB_UUID =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID LANG_DE_UUID =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM \"user\" WHERE username LIKE 'me-it-%'");
        jdbc.update("DELETE FROM person WHERE firstname = 'MeIT'");
    }

    @Test
    void me_returnsResolvedIdentity_whenJwtSubMatchesUserWithPerson() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID kcSub = UUID.randomUUID();
        jdbc.update("INSERT INTO person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "MeIT", "Linked");
        jdbc.update("""
                INSERT INTO "user" (id, club_id, username, friendly_name, person_id,
                                    notification_email, language_id,
                                    keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), CLUB_UUID.toString(),
                "me-it-linked", "Me IT Linked", personId.toString(),
                "linked@example.com", LANG_DE_UUID.toString(), kcSub.toString());

        String token = jwts.mint(c -> c
                .subject(kcSub.toString())
                .claim("clubId", CLUB_UUID.toString())
                .claim("preferred_username", "kc-name-ignored")
                .claim("given_name", "Jwt")
                .claim("family_name", "FromClaim")
                .claim("email", "jwt-claim@example.com")
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));

        ResponseEntity<String> res = get("/api/v1/me", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getCacheControl())
                .as("Per-principal PII must not be cached by intermediaries")
                .contains("no-store");
        JsonNode body = readJson(res);
        assertThat(body.get("id").asText()).isEqualTo(userId.toString());
        assertThat(body.get("personId").asText())
                .as("personId carries the `pn-` external prefix per ADR 0019")
                .isEqualTo(PersonId.of(personId).toExternal());
        assertThat(body.get("clubId").asText())
                .as("clubId carries the `clb-` external prefix per ADR 0019")
                .isEqualTo(ClubId.of(CLUB_UUID).toExternal());
        assertThat(body.get("username").asText()).isEqualTo("me-it-linked");
        assertThat(body.get("firstName").asText())
                .as("firstName resolves from Person row when linked, not from JWT given_name")
                .isEqualTo("MeIT");
        assertThat(body.get("lastName").asText()).isEqualTo("Linked");
        assertThat(body.get("email").asText()).isEqualTo("linked@example.com");
        JsonNode roles = body.get("roles");
        assertThat(roles).isNotNull();
        assertThat(roles.isArray()).isTrue();
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).asText()).isEqualTo("PILOT");
        assertThat(body.has("keycloak_sub"))
                .as("Forensic JWT sub is NOT echoed to the SPA (audit-only)")
                .isFalse();
    }

    @Test
    void me_filtersKeycloakBuiltinRoles_keepsOnlyAlpenFlightCatalog() {
        // Keycloak ships realm-wide built-in roles (uma_authorization,
        // offline_access, default-roles-*) that aren't part of AlpenFlight's
        // role model. /me must strip them so SPA consumers couple only to
        // the project's own role vocabulary (AppRole union).
        UUID kcSub = UUID.randomUUID();
        String token = jwts.mint(c -> c
                .subject(kcSub.toString())
                .claim("clubId", CLUB_UUID.toString())
                .claim("realm_access", Map.of("roles", List.of(
                        "CLUB_ADMINISTRATOR",
                        "uma_authorization",
                        "offline_access",
                        "default-roles-alpenflight"))));

        ResponseEntity<String> res = get("/api/v1/me", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode roles = readJson(res).get("roles");
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).asText()).isEqualTo("CLUB_ADMINISTRATOR");
    }

    @Test
    void me_returnsNullPersonId_whenUserRowHasNoPerson() {
        UUID userId = UUID.randomUUID();
        UUID kcSub = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO "user" (id, club_id, username, friendly_name, person_id,
                                    notification_email, language_id,
                                    keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, NULL, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), CLUB_UUID.toString(),
                "me-it-nopers", "Me IT No Person",
                "nopers@example.com", LANG_DE_UUID.toString(), kcSub.toString());

        String token = jwts.mint(c -> c
                .subject(kcSub.toString())
                .claim("clubId", CLUB_UUID.toString())
                .claim("given_name", "FallFirst")
                .claim("family_name", "FallLast")
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));

        ResponseEntity<String> res = get("/api/v1/me", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.get("personId").isNull())
                .as("personId is null when the user row carries no person_id link")
                .isTrue();
        assertThat(body.get("id").asText()).isEqualTo(userId.toString());
        assertThat(body.get("clubId").asText())
                .as("clubId carries the `clb-` external prefix per ADR 0019")
                .isEqualTo(ClubId.of(CLUB_UUID).toExternal());
        assertThat(body.get("firstName").asText())
                .as("With no Person link, firstName falls back to the JWT given_name claim")
                .isEqualTo("FallFirst");
        assertThat(body.get("lastName").asText()).isEqualTo("FallLast");
    }

    @Test
    void me_returnsNullsForUnknownSub_butEchoesJwtClaims() {
        UUID unknownSub = UUID.randomUUID();
        String token = jwts.mint(c -> c
                .subject(unknownSub.toString())
                .claim("clubId", CLUB_UUID.toString())
                .claim("preferred_username", "federated-newcomer")
                .claim("given_name", "Federated")
                .claim("family_name", "Newcomer")
                .claim("email", "federated@example.com")
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));

        ResponseEntity<String> res = get("/api/v1/me", token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.get("id").isNull())
                .as("Unknown JWT sub → id null (no user row to bind to)")
                .isTrue();
        assertThat(body.get("personId").isNull()).isTrue();
        assertThat(body.get("clubId").isNull()).isTrue();
        assertThat(body.get("username").asText()).isEqualTo("federated-newcomer");
        assertThat(body.get("firstName").asText()).isEqualTo("Federated");
        assertThat(body.get("lastName").asText()).isEqualTo("Newcomer");
        assertThat(body.get("email").asText()).isEqualTo("federated@example.com");
    }

    @Test
    void me_unauthenticated_returns401() {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/me")).build(), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON: " + res.getBody(), e);
        }
    }
}
