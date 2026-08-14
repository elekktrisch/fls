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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class KeycloakAdminClientRoleMappingsIT {

    private static final UUID ORPHAN_SUB =
            UUID.fromString("b365dc65-b93a-4d6e-8005-fc77377b418f");
    private static final UUID BIND_SUB =
            UUID.fromString("46d526dc-4981-4ccf-aa5f-b0a3fcda5b39");

    private HttpServer server;
    private int port;
    private volatile int roleMappingsStatus;
    private volatile String roleMappingsBody;
    private volatile String usersListBody;
    private volatile String bindUserGetBody;
    private volatile String lastBindPutBody;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();

        bindUserGetBody = """
                {"id":"46d526dc-4981-4ccf-aa5f-b0a3fcda5b39",
                 "username":"e2e-bind@example.com","email":"e2e-bind@example.com",
                 "firstName":"E2e","lastName":"Bind","enabled":false,"emailVerified":true,
                 "requiredActions":["VERIFY_EMAIL","UPDATE_PASSWORD"],
                 "attributes":{"locale":["en"]}}
                """;

        server.createContext("/realms/test-realm/protocol/openid-connect/token", ex -> {
            byte[] out = "{\"access_token\":\"stub-token\",\"expires_in\":300}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });

        server.createContext("/admin/realms/test-realm/users/" + ORPHAN_SUB + "/role-mappings/realm",
                ex -> {
                    byte[] out = roleMappingsBody.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(roleMappingsStatus, out.length == 0 ? -1 : out.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(out);
                    }
                });

        server.createContext("/admin/realms/test-realm/users/" + BIND_SUB, ex -> {
            if ("PUT".equals(ex.getRequestMethod())) {
                lastBindPutBody = new String(
                        ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            byte[] out = bindUserGetBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });

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

    private static ObjectMapper prodLikeMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .build();
    }

    private static JsonNode parse(String json) {
        try {
            assertThat(json).as("a PUT body must have been captured").isNotNull();
            return prodLikeMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("captured PUT body is not JSON: " + json, e);
        }
    }

    @Test
    void missingKeycloakIdentity_404_yieldsEmptyRoleSet() {
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
        usersListBody = """
                [{"id":"b365dc65-b93a-4d6e-8005-fc77377b418f","attributes":{"clubId":["not-a-uuid"]}}]
                """;

        DirectoryUser user = client().findUserByEmail("garbage@example.com").orElseThrow();

        assertThat(user.clubId())
                .as("a present-but-unparseable clubId must fail closed to the corrupted sentinel (attached)")
                .isEqualTo(DirectoryUser.CORRUPTED_CLUB_ID);
    }

    @Test
    void writeClubIdAttribute_resendsEveryMutableField_soTheBindCantDowngradeTheUser() {
        UUID clubId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        client().writeClubIdAttribute(BIND_SUB, clubId);

        JsonNode put = parse(lastBindPutBody);
        assertIdentityAndPostureSurvive(put);

        JsonNode attrs = put.path("attributes");
        assertThat(attrs.path("clubId").get(0).asText())
                .as("the merge adds clubId")
                .isEqualTo(clubId.toString());
        assertThat(attrs.path("locale").get(0).asText())
                .as("the merge preserves the signup-set locale attribute")
                .isEqualTo("en");
    }

    @Test
    void clearClubIdAttribute_presentClubId_dropsOnlyClubId_keepsIdentityAndPosture() {
        bindUserGetBody = """
                {"id":"46d526dc-4981-4ccf-aa5f-b0a3fcda5b39",
                 "username":"e2e-bind@example.com","email":"e2e-bind@example.com",
                 "firstName":"E2e","lastName":"Bind","enabled":false,"emailVerified":true,
                 "requiredActions":["VERIFY_EMAIL","UPDATE_PASSWORD"],
                 "attributes":{"clubId":["11111111-2222-3333-4444-555555555555"],"locale":["en"]}}
                """;

        client().clearClubIdAttribute(BIND_SUB);

        JsonNode put = parse(lastBindPutBody);
        assertIdentityAndPostureSurvive(put);

        JsonNode attrs = put.path("attributes");
        assertThat(attrs.has("clubId"))
                .as("the clear drops clubId")
                .isFalse();
        assertThat(attrs.path("locale").get(0).asText())
                .as("the clear preserves the locale attribute")
                .isEqualTo("en");
    }

    @Test
    void clearClubIdAttribute_absentClubId_isANoOpWithNoPut() {
        lastBindPutBody = null;

        client().clearClubIdAttribute(BIND_SUB);

        assertThat(lastBindPutBody)
                .as("clearing an absent clubId is a no-op — no PUT, so identity can't be touched")
                .isNull();
    }

    private static void assertIdentityAndPostureSurvive(JsonNode put) {
        assertThat(put.path("username").asText())
                .as("the PUT must re-send username or KC nulls it")
                .isEqualTo("e2e-bind@example.com");
        assertThat(put.path("email").asText()).isEqualTo("e2e-bind@example.com");
        assertThat(put.path("firstName").asText()).isEqualTo("E2e");
        assertThat(put.path("lastName").asText()).isEqualTo("Bind");
        assertThat(put.path("emailVerified").asBoolean()).isTrue();
        assertThat(put.path("enabled").asBoolean())
                .as("a disabled invitee must stay disabled — the bind can't re-enable it")
                .isFalse();
        assertThat(put.path("requiredActions"))
                .as("pending required actions must survive the write (no posture downgrade)")
                .hasSize(2);
        List<String> actions = List.of(
                put.path("requiredActions").get(0).asText(),
                put.path("requiredActions").get(1).asText());
        assertThat(actions).containsExactlyInAnyOrder("VERIFY_EMAIL", "UPDATE_PASSWORD");
    }
}
