package ch.alpenflight.tenancy.provisioning.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.alpenflight.tenancy.provisioning.infra.KeycloakDeploymentDirectoryAdapter.KeycloakNamedRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the {@link KeycloakNamedRef} projection against REAL Keycloak
 * response shapes when deserialised by a mapper configured exactly like the
 * production one (J-0c T-18).
 *
 * <p>Why this exists: the adapter is handed Spring Boot's auto-configured
 * {@code ObjectMapper}, which runs with
 * {@code spring.jackson.deserialization.fail-on-unknown-properties: true} and
 * {@code spring.jackson.mapper.accept-case-insensitive-properties: false}
 * (see {@code application.yml}). Keycloak's real responses are far richer than
 * the {@code {id, name}} projection: a {@code RoleRepresentation} from
 * {@code GET /admin/realms/{realm}/roles/{name}} carries {@code composite},
 * {@code clientRole}, {@code containerId}, {@code attributes}; a
 * {@code UserRepresentation} (and {@code GroupRepresentation}) carry many more.
 *
 * <p>The first real-chain run (run 26799888533) blew up exactly here:
 * {@code provisionClubAdminIdentity} → {@code findRealmRoleIdByName} →
 * {@code readNamed} threw {@code UnrecognizedPropertyException} on
 * {@code composite}, which the adapter mislabels "malformed JSON object from
 * directory". Pinning {@code @JsonIgnoreProperties(ignoreUnknown = true)} on
 * the projection makes it independent of the global strict wire policy. These
 * tests fail (UnrecognizedPropertyException) without that annotation.
 */
class KeycloakNamedRefDeserializationTest {

    /** Mirrors application.yml's two load-bearing deserialization flags. */
    private static final ObjectMapper PROD_LIKE_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build();

    private static final UUID ROLE_ID = UUID.fromString("7d3f9c2a-1111-2222-3333-444455556666");

    @Test
    void parsesRealKeycloakRoleRepresentationObject() {
        // Verbatim shape of GET /admin/realms/alpenflight/roles/CLUB_ADMINISTRATOR
        // in Keycloak 26 — the body that broke run 26799888533.
        String body = """
                {
                  "id": "7d3f9c2a-1111-2222-3333-444455556666",
                  "name": "CLUB_ADMINISTRATOR",
                  "description": "",
                  "composite": false,
                  "clientRole": false,
                  "containerId": "alpenflight",
                  "attributes": {}
                }
                """;

        KeycloakNamedRef ref = PROD_LIKE_MAPPER.readValue(body, KeycloakNamedRef.class);

        assertThat(ref.id()).isEqualTo(ROLE_ID);
        assertThat(ref.name()).isEqualTo("CLUB_ADMINISTRATOR");
    }

    @Test
    void parsesRealKeycloakUserListWithRichFields() {
        // GET /admin/realms/{realm}/users?username=...&exact=true returns an
        // ARRAY of verbose UserRepresentation objects (findUserIdByUsername).
        String body = """
                [
                  {
                    "id": "9d08ed9c-699a-4c26-9036-9f0bd378009d",
                    "username": "club-admin-a",
                    "enabled": true,
                    "emailVerified": false,
                    "createdTimestamp": 1717305600000,
                    "totp": false,
                    "disableableCredentialTypes": [],
                    "requiredActions": ["UPDATE_PASSWORD"],
                    "notBefore": 0,
                    "access": {"manageGroupMembership": true, "view": true},
                    "attributes": {"clubId": ["0fa7b76f-47ba-4138-8f96-671400fd7c83"]}
                  }
                ]
                """;

        List<KeycloakNamedRef> refs = PROD_LIKE_MAPPER.readValue(
                body,
                PROD_LIKE_MAPPER.getTypeFactory()
                        .constructCollectionType(List.class, KeycloakNamedRef.class));

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).id())
                .isEqualTo(UUID.fromString("9d08ed9c-699a-4c26-9036-9f0bd378009d"));
    }

    @Test
    void parsesRealKeycloakGroupAndRoleMappingLists() {
        // GET /users/{id}/groups and /users/{id}/role-mappings/realm both
        // return arrays of verbose representations (addUserToGroupIfAbsent,
        // assignRoleIfAbsent).
        String groups = """
                [
                  {
                    "id": "11111111-2222-3333-4444-555555555555",
                    "name": "deployment-abc",
                    "path": "/deployment-abc",
                    "subGroupCount": 0,
                    "attributes": {}
                  }
                ]
                """;

        assertThatCode(() -> PROD_LIKE_MAPPER.readValue(
                groups,
                PROD_LIKE_MAPPER.getTypeFactory()
                        .constructCollectionType(List.class, KeycloakNamedRef.class)))
                .doesNotThrowAnyException();
    }
}
