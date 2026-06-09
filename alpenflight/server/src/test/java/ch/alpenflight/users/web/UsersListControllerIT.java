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

/**
 * J-6b T-15 — regression for the operator field-test bug "Users menu shows
 * '400 Bad Request' for clubadmin1". The bug is NOT an authz issue
 * (CLUB_ADMINISTRATOR is authorized); it surfaces on the list request shape /
 * backend query. This IT drives {@code GET /api/v1/users} as a real
 * club-admin principal and asserts 200 + the tenant-scoped rows.
 *
 * <p>The Keycloak adapter is mocked ({@link UserDirectoryPort}) so the list
 * path completes without a live IdP — the bug under test is independent of KC.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class UsersListControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID OTHER_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000002");
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

        // A second tenant for the cross-tenant-leak guard (only seed-club-1
        // ships in the migrations).
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
                adminId.toString(), CLUB.toString(), "list-it-admin", "List IT Admin",
                "admin@example.com", LANG_DE.toString(), adminSub.toString());

        // Another active row in the same club (should appear in the list).
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name,
                                    notification_email, language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), CLUB.toString(), "list-it-peer", "List IT Peer",
                "peer@example.com", LANG_DE.toString(), UUID.randomUUID().toString());

        // A row in another club (must NOT leak into the list).
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name,
                                    notification_email, language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                UUID.randomUUID().toString(), OTHER_CLUB.toString(), "list-it-other", "List IT Other",
                "other@example.com", LANG_DE.toString(), UUID.randomUUID().toString());

        // KC adapter: directory is reachable, returns no extra metadata.
        when(directory.findUsersInClub(any(UUID.class), anyInt())).thenReturn(List.of());
        when(directory.getRealmRoleMappings(any(UUID.class)))
                .thenReturn(List.<RealmRoleRef>of());

        // The realm-export gives the dev club-admins a SYMBOLIC clubId
        // attribute ("club-1"), NOT a UUID. The token therefore carries a
        // non-UUID clubId claim — exactly clubadmin1 in the dev stack.
        // Tenant resolution must fall through to the keycloak_sub lookup of
        // the seeded t_user row (the admin row above), so the list still
        // scopes to the club.
        adminToken = jwts.mint(c -> c
                .subject(adminSub.toString())
                .claim("clubId", "club-1") // symbolic, non-UUID — as in realm-export.json
                .claim("preferred_username", "list-it-admin")
                .claim("given_name", "List")
                .claim("email", "admin@example.com")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void clubAdmin_symbolicClubIdClaim_listsUsers_returns200_tenantScoped() {
        // AC #13: "clubadmin1 opens the Users menu and the list renders
        // (no 400 Bad Request)." Drives the exact dev clubadmin1 principal
        // (symbolic clubId claim + seeded t_user row); the list must render
        // 200 with the club's rows, no cross-tenant leak.
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
