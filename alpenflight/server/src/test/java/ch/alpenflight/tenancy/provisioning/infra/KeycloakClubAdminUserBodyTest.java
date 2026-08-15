package ch.alpenflight.tenancy.provisioning.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeycloakClubAdminUserBodyTest {

    private static final UUID CLUB_ID =
            UUID.fromString("0fa7b76f-47ba-4138-8f96-671400fd7c83");

    @Test
    void bodyCarriesFirstAndLastNameSoVerifyProfileNeverFires() {
        Map<String, Object> body = KeycloakDeploymentDirectoryAdapter.clubAdminUserBody(
                CLUB_ID,
                "migrated-admin+" + CLUB_ID + "@migrated.alpenflight.local",
                "migrated-admin+" + CLUB_ID + "@migrated.alpenflight.local",
                "Migrated",
                "Admin");

        assertThat(body).containsEntry("firstName", "Migrated");
        assertThat(body).containsEntry("lastName", "Admin");
    }

    @Test
    void bodyStillCarriesUsernameEmailClubAttributeAndUpdatePassword() {
        String username = "migrated-admin+" + CLUB_ID + "@migrated.alpenflight.local";
        Map<String, Object> body = KeycloakDeploymentDirectoryAdapter.clubAdminUserBody(
                CLUB_ID, username, username, "Migrated", "Admin");

        assertThat(body).containsEntry("username", username);
        assertThat(body).containsEntry("email", username);
        assertThat(body).containsEntry("enabled", true);
        assertThat(body).containsEntry("requiredActions", List.of("UPDATE_PASSWORD"));
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) body.get("attributes");
        assertThat(attributes).containsEntry("clubId", List.of(CLUB_ID.toString()));
    }
}
