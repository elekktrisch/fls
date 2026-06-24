package ch.alpenflight.users.infra.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.platform.keycloak.KeycloakAdminProperties;
import ch.alpenflight.platform.keycloak.KeycloakAdminTokenSupplier;
import ch.alpenflight.users.domain.UserDirectoryException;
import ch.alpenflight.users.domain.UserDirectoryPort.DirectoryUser;
import ch.alpenflight.users.domain.UserDirectoryPort.RealmRoleRef;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * J-26 T-30c — {@link KeycloakAdminClient#getRealmRoleMappings} must tolerate a
 * Keycloak {@code 404} for a {@code sub} that has no KC identity.
 *
 * <p><strong>Root cause this guards.</strong> {@code GET /api/v1/users}
 * ({@code listInCurrentTenant}) reads the realm role-mappings of EVERY active
 * {@code t_user} row in the caller's club, one KC call per row. A local row
 * whose {@code keycloak_sub} is absent from the realm (a seed / bulk-import
 * row, or a KC user later deleted) makes KC answer {@code 404}. Before this
 * fix the adapter mapped that {@code 404} to a {@link UserDirectoryException}
 * (HTTP 502) — and when the KC admin client could not even reach Keycloak the
 * connection error surfaced as a {@code 400 urn:alpenflight:problem:bad-request}
 * ("Invalid request") — so a SINGLE orphaned row blew up the whole list. The
 * real-idp nightly hit exactly this (clubadmin1's club carries seeded
 * clubadmin2/3/4 + bulk rows whose subs aren't in the freshly-built realm).
 *
 * <p>The fix makes a missing KC identity equivalent to "no realm roles" — the
 * row renders with an empty role set instead of failing the list — mirroring
 * {@code findUsersByRoleName}'s existing {@code 404 → empty} handling. Any
 * other upstream status still throws (502), so a genuine KC outage is not
 * silently swallowed.
 *
 * <p>Drives the REAL adapter (its own internal {@link org.springframework.web.client.RestClient}
 * + bearer interceptor) against a JDK {@link HttpServer} stub so the HTTP
 * status path is exercised end-to-end — the pure-deser sibling test
 * ({@code KeycloakAdminWireDtoDeserializationTest}) cannot, it never makes a
 * request.
 */
class KeycloakAdminClientRoleMappingsIT {

    private static final UUID ORPHAN_SUB =
            UUID.fromString("b365dc65-b93a-4d6e-8005-fc77377b418f");

    private HttpServer server;
    private int port;
    private volatile int roleMappingsStatus;
    private volatile String roleMappingsBody;
    private volatile String usersListBody;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();

        // Service-account token endpoint — the supplier fetches a bearer once.
        server.createContext("/realms/test-realm/protocol/openid-connect/token", ex -> {
            byte[] out = "{\"access_token\":\"stub-token\",\"expires_in\":300}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });

        // role-mappings/realm — status + body are per-test programmable.
        server.createContext("/admin/realms/test-realm/users/" + ORPHAN_SUB + "/role-mappings/realm",
                ex -> {
                    byte[] out = roleMappingsBody.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(roleMappingsStatus, out.length == 0 ? -1 : out.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(out);
                    }
                });

        // users (email-lookup) — body is per-test programmable.
        server.createContext("/admin/realms/test-realm/users", ex -> {
            byte[] out = usersListBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length == 0 ? -1 : out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });

        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private KeycloakAdminClient client() {
        KeycloakAdminProperties props = new KeycloakAdminProperties(
                "http://127.0.0.1:" + port, "test-realm", "stub-client", "stub-secret", 30);
        KeycloakAdminTokenSupplier tokens =
                new KeycloakAdminTokenSupplier(props, Clock.systemUTC());
        ObjectMapper mapper = prodLikeMapper();
        return new KeycloakAdminClient(tokens, props, mapper);
    }

    /**
     * Mirrors the two load-bearing deserialization flags the production
     * (auto-configured) {@code tools.jackson} ObjectMapper runs with per
     * application.yml — the same prod-like mapper the sibling
     * {@code KeycloakAdminWireDtoDeserializationTest} builds.
     */
    private static ObjectMapper prodLikeMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .build();
    }

    @Test
    void missingKeycloakIdentity_404_yieldsEmptyRoleSet() {
        // A t_user row whose keycloak_sub is absent from the realm: KC answers
        // 404. The list endpoint must keep rendering — the orphaned row simply
        // has no roles, not a 502/400 for the whole list.
        roleMappingsStatus = 404;
        roleMappingsBody =
                "{\"error\":\"User not found\",\"errorMessage\":\"User does not exist\"}";

        List<RealmRoleRef> roles = client().getRealmRoleMappings(ORPHAN_SUB);

        assertThat(roles)
                .as("a 404 (KC identity gone) must read as the empty role set, not blow up the list")
                .isEmpty();
    }

    @Test
    void otherUpstreamStatus_500_stillThrowsUserDirectoryException() {
        // A genuine KC outage must NOT be swallowed — only 404 is benign.
        roleMappingsStatus = 500;
        roleMappingsBody = "{\"error\":\"server_error\"}";

        assertThatThrownBy(() -> client().getRealmRoleMappings(ORPHAN_SUB))
                .isInstanceOf(UserDirectoryException.class)
                .hasMessageContaining("status 500");
    }

    @Test
    void presentIdentity_200_returnsTheMappedRoles() {
        roleMappingsStatus = 200;
        roleMappingsBody = """
                [
                  {"id":"7d3f9c2a","name":"CLUB_ADMINISTRATOR","description":"x",
                   "composite":false,"clientRole":false,"containerId":"alpenflight"}
                ]
                """;

        List<RealmRoleRef> roles = client().getRealmRoleMappings(ORPHAN_SUB);

        assertThat(roles).extracting(RealmRoleRef::name).containsExactly("CLUB_ADMINISTRATOR");
    }

    @Test
    void emailLookup_absentClubIdAttribute_mapsToUnattached() {
        // No clubId attribute → genuinely unattached → null → the legit bind case.
        usersListBody = """
                [{"id":"b365dc65-b93a-4d6e-8005-fc77377b418f","attributes":{"locale":["en"]}}]
                """;

        DirectoryUser user = client().findUserByEmail("absent@example.com").orElseThrow();

        assertThat(user.clubId())
                .as("an ABSENT clubId attribute is the genuinely-unattached bind case")
                .isNull();
    }

    @Test
    void emailLookup_garbageClubIdAttribute_failsClosedToAttached() {
        // A present-but-unparseable clubId attribute is an anomaly, never proof
        // of being unattached: the wire mapping must fail CLOSED to a non-null
        // sentinel so the invite treats it as attached (→ 409), never relocating
        // a possibly-attached identity into a new tenant.
        usersListBody = """
                [{"id":"b365dc65-b93a-4d6e-8005-fc77377b418f","attributes":{"clubId":["not-a-uuid"]}}]
                """;

        DirectoryUser user = client().findUserByEmail("garbage@example.com").orElseThrow();

        assertThat(user.clubId())
                .as("a present-but-unparseable clubId must fail closed to the corrupted sentinel (attached)")
                .isEqualTo(DirectoryUser.CORRUPTED_CLUB_ID);
    }
}
