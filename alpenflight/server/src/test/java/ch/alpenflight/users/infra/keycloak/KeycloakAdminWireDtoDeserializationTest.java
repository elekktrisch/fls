package ch.alpenflight.users.infra.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.alpenflight.users.infra.keycloak.KeycloakAdminClient.RealmRoleWire;
import ch.alpenflight.users.infra.keycloak.KeycloakAdminClient.UserWire;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class KeycloakAdminWireDtoDeserializationTest {

    private static final ObjectMapper PROD_LIKE_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build();

    private static List<RealmRoleWire> readRoles(String body) {
        return PROD_LIKE_MAPPER.readValue(
                body,
                PROD_LIKE_MAPPER.getTypeFactory()
                        .constructCollectionType(List.class, RealmRoleWire.class));
    }

    @Test
    void parsesRealKeycloakRoleMappingsArrayWithRichFields() {
        String body = """
                [
                  {
                    "id": "7d3f9c2a-1111-2222-3333-444455556666",
                    "name": "CLUB_ADMINISTRATOR",
                    "description": "Club administrator",
                    "composite": false,
                    "clientRole": false,
                    "containerId": "alpenflight",
                    "attributes": {}
                  },
                  {
                    "id": "aa11bb22-cc33-dd44-ee55-ff6677889900",
                    "name": "default-roles-alpenflight",
                    "composite": true,
                    "clientRole": false,
                    "containerId": "alpenflight"
                  }
                ]
                """;

        List<RealmRoleWire> roles = readRoles(body);

        assertThat(roles).hasSize(2);
        assertThat(roles.get(0).id())
                .isEqualTo("7d3f9c2a-1111-2222-3333-444455556666");
        assertThat(roles.get(0).name()).isEqualTo("CLUB_ADMINISTRATOR");
        assertThat(roles.get(1).name()).isEqualTo("default-roles-alpenflight");
        assertThat(roles).extracting(RealmRoleWire::toRef)
                .extracting("name")
                .containsExactly("CLUB_ADMINISTRATOR", "default-roles-alpenflight");
    }

    @Test
    void parsesEmptyRoleMappingsArray() {
        assertThat(readRoles("[]")).isEmpty();
    }

    @Test
    void parsesRealKeycloakUserListWithRichFields() {
        String body = """
                [
                  {
                    "id": "9d08ed9c-699a-4c26-9036-9f0bd378009d",
                    "username": "club-admin-a",
                    "email": "admin-a@example.test",
                    "enabled": true,
                    "emailVerified": false,
                    "firstName": "Ada",
                    "lastName": "Admin",
                    "createdTimestamp": 1717305600000,
                    "totp": false,
                    "federationLink": null,
                    "disableableCredentialTypes": [],
                    "requiredActions": ["UPDATE_PASSWORD"],
                    "notBefore": 0,
                    "access": {"manageGroupMembership": true, "view": true},
                    "attributes": {"clubId": ["0fa7b76f-47ba-4138-8f96-671400fd7c83"]}
                  }
                ]
                """;

        List<UserWire> users = PROD_LIKE_MAPPER.readValue(
                body,
                PROD_LIKE_MAPPER.getTypeFactory()
                        .constructCollectionType(List.class, UserWire.class));

        assertThat(users).hasSize(1);
        UserWire u = users.get(0);
        assertThat(u.id()).isEqualTo(UUID.fromString("9d08ed9c-699a-4c26-9036-9f0bd378009d"));
        assertThat(u.username()).isEqualTo("club-admin-a");
        assertThat(u.email()).isEqualTo("admin-a@example.test");
        assertThat(u.enabled()).isTrue();
        assertThat(u.requiredActions()).containsExactly("UPDATE_PASSWORD");
        assertThat(u.createdTimestamp()).isEqualTo(1717305600000L);
        assertThat(u.toRow().id())
                .isEqualTo(UUID.fromString("9d08ed9c-699a-4c26-9036-9f0bd378009d"));
    }

    @Test
    void parsesEmptyUserListArray() {
        assertThatCode(() -> PROD_LIKE_MAPPER.readValue(
                "[]",
                PROD_LIKE_MAPPER.getTypeFactory()
                        .constructCollectionType(List.class, UserWire.class)))
                .doesNotThrowAnyException();
    }
}
