package ch.alpenflight.tenancy.provisioning.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Adapter-boundary unit test for the club-admin user-create request body
 * (J-1 T-06). Asserts the representation the adapter POSTs to
 * {@code /admin/realms/{realm}/users} carries a non-blank firstName +
 * lastName so Keycloak's declarative user-profile does NOT fire the
 * dynamically-triggered {@code VERIFY_PROFILE} required action on the
 * migrated admin's first login.
 *
 * <p>Why an adapter-boundary assertion (not a real Keycloak round-trip):
 * the local test path has no realm. We assert the wire representation the
 * adapter builds — the same surface the (now removed) e2e
 * {@code makeMigratedAdminLoginable} name fixup patched post-hoc. With the
 * names set at provision time, the migrated admin is loginable in one shot
 * with no profile interstitial.
 */
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
