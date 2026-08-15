package ch.alpenflight.users.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.users.domain.UserDirectoryPort;
import ch.alpenflight.users.domain.UserDirectoryPort.RealmRoleRef;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class UsersListControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID MIGRATION_SEEDED_CLUB_1 =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID OTHER_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-0000000ab102");
    private static final String SYMBOLIC_CLUB_ID_CLAIM_FORCING_KEYCLOAK_SUB_TENANT_FALLBACK =
            "club-1";
    private static final UUID LANG_DE =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    @MockitoBean UserDirectoryPort directory;

    private UUID adminSub;
    private String adminToken;

    @BeforeEach
    void seedAndAuth() {
        jdbc.update("DELETE FROM t_user WHERE username LIKE 'list-it-%'");

        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id,
                                    slug, public_registration_enabled)
                VALUES (?::uuid, 'List IT Other Club', 'LISTIT2',
                        '019e2e15-2c00-74be-8000-0000000004be',
                        '019e2e15-2c00-7bb8-8000-000000000bb8',
                        'list-it-other-club', false)
                ON CONFLICT (id) DO NOTHING
                """, OTHER_CLUB.toString());

        adminSub = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name,
                                    notification_email, language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                adminId.toString(), MIGRATION_SEEDED_CLUB_1.toString(), "list-it-admin", "List IT Admin",
                "admin@example.com", LANG_DE.toString(), adminSub.toString());

        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name,
                                    notification_email, language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), MIGRATION_SEEDED_CLUB_1.toString(), "list-it-peer", "List IT Peer",
                "peer@example.com", LANG_DE.toString(), UUID.randomUUID().toString());

        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name,
                                    notification_email, language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), OTHER_CLUB.toString(), "list-it-other", "List IT Other",
                "other@example.com", LANG_DE.toString(), UUID.randomUUID().toString());

        when(directory.findUsersInClub(any(UUID.class), anyInt())).thenReturn(List.of());
        when(directory.getRealmRoleMappings(any(UUID.class)))
                .thenReturn(List.<RealmRoleRef>of());

        adminToken = jwts.mint(c -> c
                .subject(adminSub.toString())
                .claim("clubId", SYMBOLIC_CLUB_ID_CLAIM_FORCING_KEYCLOAK_SUB_TENANT_FALLBACK)
                .claim("preferred_username", "list-it-admin")
                .claim("given_name", "List")
                .claim("email", "admin@example.com")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void clubAdmin_orphanedKeycloakSubRow_stillRenders200_withEmptyRoles() {
        UUID orphanSub = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name,
                                    notification_email, language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), MIGRATION_SEEDED_CLUB_1.toString(), "list-it-orphan",
                "List IT Orphan", "orphan@example.com", LANG_DE.toString(), orphanSub.toString());

        when(directory.getRealmRoleMappings(orphanSub)).thenReturn(List.<RealmRoleRef>of());

        ResponseEntity<String> res = get("/api/v1/users", adminToken);

        assertThat(res.getStatusCode())
                .as("an orphaned keycloak_sub row must not fail the list — body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = readJson(res);
        JsonNode orphanRow = null;
        for (JsonNode row : body) {
            if ("list-it-orphan".equals(row.path("username").asText(null))) {
                orphanRow = row;
            }
        }
        assertThat(orphanRow)
                .as("the orphaned-sub row is still listed")
                .isNotNull();
        assertThat(orphanRow.get("roles").isArray()).isTrue();
        assertThat(orphanRow.get("roles")).isEmpty();
    }

    @Test
    void clubAdmin_symbolicClubIdClaim_listsUsers_returns200_tenantScoped() {
        ResponseEntity<String> res = get("/api/v1/users", adminToken);

        assertThat(res.getStatusCode())
                .as("clubadmin1's Users list must render (no 400 Bad Request) — body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        List<String> usernames = body.findValuesAsText("username");
        assertThat(usernames).contains("list-it-admin", "list-it-peer");
        assertThat(usernames)
                .as("cross-tenant users must not leak")
                .doesNotContain("list-it-other");
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
