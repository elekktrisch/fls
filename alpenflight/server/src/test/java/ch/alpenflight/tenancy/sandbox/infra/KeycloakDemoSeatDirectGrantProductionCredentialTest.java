package ch.alpenflight.tenancy.sandbox.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.platform.keycloak.KeycloakAdminProperties;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatTokenNotIssuedException;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class KeycloakDemoSeatDirectGrantProductionCredentialTest {

    private static final KeycloakAdminProperties A_REALM_NO_PROCESS_LISTENS_ON =
            new KeycloakAdminProperties(
                    "http://127.0.0.1:1", "alpenflight", "alpenflight-backend-admin", "secret", 30);

    private static final String A_SEAT_PRINCIPAL = "demo1";

    private static final Environment THE_PRODUCTION_PROFILE_IS_ACTIVE =
            new MockEnvironment().withProperty("spring.profiles.active",
                    KeycloakDemoSeatDirectGrant.PROFILE_A_PRODUCTION_DEPLOYMENT_ACTIVATES);
    private static final Environment A_DEVELOPMENT_PROFILE_IS_ACTIVE =
            new MockEnvironment().withProperty("spring.profiles.active", "dev");

    private static final DemoSeatDirectGrantProperties THE_COMMITTED_DEV_CREDENTIAL =
            new DemoSeatDirectGrantProperties(
                    "alpenflight-demo-seat",
                    DemoSeatDirectGrantProperties.CLIENT_SECRET_THE_COMMITTED_DEV_REALM_CARRIES,
                    DemoSeatDirectGrantProperties.SEAT_PASSWORD_THE_COMMITTED_DEV_REALM_CARRIES);

    private static final DemoSeatDirectGrantProperties A_ROTATED_SECRET_WITH_THE_DEV_PASSWORD =
            new DemoSeatDirectGrantProperties(
                    "alpenflight-demo-seat",
                    "a-rotated-client-secret",
                    DemoSeatDirectGrantProperties.SEAT_PASSWORD_THE_COMMITTED_DEV_REALM_CARRIES);

    private static final DemoSeatDirectGrantProperties A_CREDENTIAL_A_PRODUCTION_REALM_SUPPLIES =
            new DemoSeatDirectGrantProperties(
                    "alpenflight-demo-seat", "a-rotated-client-secret", "a-rotated-seat-password");

    @Test
    void production_with_the_committed_dev_credential_issues_no_token() {
        assertThatThrownBy(() -> grantWith(THE_COMMITTED_DEV_CREDENTIAL,
                THE_PRODUCTION_PROFILE_IS_ACTIVE))
                .isInstanceOf(DemoSeatTokenNotIssuedException.class)
                .hasMessage(KeycloakDemoSeatDirectGrant
                        .REFUSAL_WHEN_PRODUCTION_STILL_CARRIES_THE_COMMITTED_DEV_CREDENTIAL);
    }

    @Test
    void production_that_rotated_only_the_client_secret_still_issues_no_token() {
        assertThatThrownBy(() -> grantWith(A_ROTATED_SECRET_WITH_THE_DEV_PASSWORD,
                THE_PRODUCTION_PROFILE_IS_ACTIVE))
                .isInstanceOf(DemoSeatTokenNotIssuedException.class)
                .hasMessage(KeycloakDemoSeatDirectGrant
                        .REFUSAL_WHEN_PRODUCTION_STILL_CARRIES_THE_COMMITTED_DEV_CREDENTIAL);
    }

    @Test
    void production_with_its_own_credential_reaches_the_realm_instead_of_refusing_early() {
        assertThatThrownBy(() -> grantWith(A_CREDENTIAL_A_PRODUCTION_REALM_SUPPLIES,
                THE_PRODUCTION_PROFILE_IS_ACTIVE))
                .isInstanceOf(DemoSeatTokenNotIssuedException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void a_development_deployment_keeps_the_committed_dev_credential() {
        assertThatThrownBy(() -> grantWith(THE_COMMITTED_DEV_CREDENTIAL,
                A_DEVELOPMENT_PROFILE_IS_ACTIVE))
                .isInstanceOf(DemoSeatTokenNotIssuedException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void the_readable_reason_names_no_identity_provider_internals() {
        assertThat(new DemoSeatTokenNotIssuedException("kc said 401").readableReason())
                .doesNotContain("401")
                .doesNotContain("Keycloak");
    }

    private static void grantWith(DemoSeatDirectGrantProperties credentials,
                                  Environment environment) {
        new KeycloakDemoSeatDirectGrant(A_REALM_NO_PROCESS_LISTENS_ON, credentials, environment)
                .issueAccessTokenForSeatPrincipal(A_SEAT_PRINCIPAL);
    }
}
