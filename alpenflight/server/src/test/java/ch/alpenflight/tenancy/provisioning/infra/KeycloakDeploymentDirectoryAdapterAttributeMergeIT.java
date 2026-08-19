package ch.alpenflight.tenancy.provisioning.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.keycloak.KeycloakAdminProperties;
import ch.alpenflight.platform.keycloak.KeycloakAdminTokenSupplier;
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

class KeycloakDeploymentDirectoryAdapterAttributeMergeIT {

    private static final UUID OWNER_SUB =
            UUID.fromString("0b3f7e58-1c2d-4a19-9c7e-2f0a5d8b6e41");

    private HttpServer server;
    private int port;
    private volatile String ownerGetBody;
    private volatile String lastOwnerPutBody;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();

        ownerGetBody = """
                {"id":"0b3f7e58-1c2d-4a19-9c7e-2f0a5d8b6e41","createdTimestamp":1750000000000,
                 "username":"owner@example.com","email":"owner@example.com",
                 "firstName":"Deployment","lastName":"Owner","enabled":true,"emailVerified":true,
                 "requiredActions":["UPDATE_PASSWORD"],
                 "attributes":{"locale":["de"],"deploymentId":["9a1c0c2e-5b6d-4f70-8a11-0c9e2d3f4a5b"]}}
                """;

        server.createContext("/realms/test-realm/protocol/openid-connect/token", exchange -> {
            byte[] out = "{\"access_token\":\"stub-token\",\"expires_in\":300}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(out);
            }
        });

        server.createContext("/admin/realms/test-realm/users/" + OWNER_SUB, exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                lastOwnerPutBody = new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            byte[] out = ownerGetBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(out);
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

    @Test
    void setUserAttribute_resendsIdentity_soTheAttributeWriteCannotNullEmailFirstNameOrLastName() {
        adapter().setUserAttribute(OWNER_SUB, "clubId", List.of(A_CLUB_ID));

        JsonNode put = parse(lastOwnerPutBody);
        assertThat(put.path("username").asText())
                .as("the PUT must re-send username or Keycloak nulls it")
                .isEqualTo("owner@example.com");
        assertThat(put.path("email").asText())
                .as("a nulled email removes the owner from every email lookup")
                .isEqualTo("owner@example.com");
        assertThat(put.path("firstName").asText()).isEqualTo("Deployment");
        assertThat(put.path("lastName").asText()).isEqualTo("Owner");
        assertThat(put.path("enabled").asBoolean()).isTrue();
        assertThat(put.path("emailVerified").asBoolean())
                .as("a nulled emailVerified sends the owner back through verification")
                .isTrue();
        assertThat(put.path("requiredActions"))
                .as("pending required actions must survive the write")
                .hasSize(1);
        assertThat(put.path("requiredActions").get(0).asText()).isEqualTo("UPDATE_PASSWORD");
    }

    @Test
    void setUserAttribute_mergesIntoTheReadAttributes_soEveryOtherAttributeSurvives() {
        adapter().setUserAttribute(OWNER_SUB, "clubId", List.of(A_CLUB_ID));

        JsonNode attributes = parse(lastOwnerPutBody).path("attributes");
        assertThat(attributes.path("clubId").path(0).asText())
                .as("the merge writes the requested attribute")
                .isEqualTo(A_CLUB_ID);
        assertThat(attributes.path("locale").path(0).asText())
                .as("the merge keeps the locale attribute the signup set")
                .isEqualTo("de");
        assertThat(attributes.path("deploymentId").path(0).asText())
                .as("the merge keeps every attribute it did not write")
                .isEqualTo("9a1c0c2e-5b6d-4f70-8a11-0c9e2d3f4a5b");
    }

    private static final String A_CLUB_ID = "11111111-2222-3333-4444-555555555555";

    private KeycloakDeploymentDirectoryAdapter adapter() {
        KeycloakAdminProperties props = new KeycloakAdminProperties(
                "http://127.0.0.1:" + port, "test-realm", "stub-client", "stub-secret", 30);
        KeycloakAdminTokenSupplier tokens =
                new KeycloakAdminTokenSupplier(props, Clock.systemUTC());
        return new KeycloakDeploymentDirectoryAdapter(tokens, props, prodLikeMapper());
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
}
