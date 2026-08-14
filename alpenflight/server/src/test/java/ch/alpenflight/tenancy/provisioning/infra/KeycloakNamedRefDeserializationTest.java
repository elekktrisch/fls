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

class KeycloakNamedRefDeserializationTest {

    private static final ObjectMapper PROD_LIKE_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build();

    private static final UUID ROLE_ID = UUID.fromString("7d3f9c2a-1111-2222-3333-444455556666");

    @Test
    void parsesRealKeycloakRoleRepresentationObject() {
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
