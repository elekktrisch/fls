package ch.alpenflight.tenancy.sandbox.infra;

import ch.alpenflight.platform.keycloak.KeycloakAdminProperties;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatTokenIssuer;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatTokenNotIssuedException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
@EnableConfigurationProperties(DemoSeatDirectGrantProperties.class)
class KeycloakDemoSeatDirectGrant implements DemoSeatTokenIssuer {

    static final String PROFILE_A_PRODUCTION_DEPLOYMENT_ACTIVATES = "prod";

    static final String REFUSAL_WHEN_PRODUCTION_STILL_CARRIES_THE_COMMITTED_DEV_CREDENTIAL =
            "the demo front door is closed: this deployment runs the production profile and still "
                    + "carries the demo seat credential that the committed dev realm publishes. A "
                    + "production realm must hold its own demo seat principals and its own "
                    + "confidential demo-seat client. Supply "
                    + "ALPENFLIGHT_DEMO_SEAT_CLIENT_SECRET and ALPENFLIGHT_DEMO_SEAT_PASSWORD.";

    private final RestClient http = RestClient.create();
    private final KeycloakAdminProperties realm;
    private final DemoSeatDirectGrantProperties credentials;
    private final boolean theCredentialIsTheCommittedDevOneOnAProductionDeployment;

    KeycloakDemoSeatDirectGrant(KeycloakAdminProperties realm,
                                DemoSeatDirectGrantProperties credentials,
                                Environment environment) {
        this.realm = realm;
        this.credentials = credentials;
        this.theCredentialIsTheCommittedDevOneOnAProductionDeployment =
                environment.matchesProfiles(PROFILE_A_PRODUCTION_DEPLOYMENT_ACTIVATES)
                        && credentials.carriesACredentialThatTheCommittedDevRealmPublishes();
    }

    @Override
    public IssuedAccessToken issueAccessTokenForSeatPrincipal(String keycloakUsername) {
        if (theCredentialIsTheCommittedDevOneOnAProductionDeployment) {
            throw new DemoSeatTokenNotIssuedException(
                    REFUSAL_WHEN_PRODUCTION_STILL_CARRIES_THE_COMMITTED_DEV_CREDENTIAL);
        }
        @Nullable DirectGrantResponse granted = postTheDirectGrant(keycloakUsername);
        if (granted == null || granted.accessToken == null || granted.accessToken.isBlank()) {
            throw new DemoSeatTokenNotIssuedException(
                    "Keycloak returned no access_token for demo seat principal " + keycloakUsername);
        }
        return new IssuedAccessToken(granted.accessToken, granted.expiresIn);
    }

    private @Nullable DirectGrantResponse postTheDirectGrant(String keycloakUsername) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", credentials.clientId());
        form.add("client_secret", credentials.clientSecret());
        form.add("username", keycloakUsername);
        form.add("password", credentials.seatPassword());
        try {
            return http.post()
                    .uri(realm.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(DirectGrantResponse.class);
        } catch (HttpStatusCodeException refused) {
            throw new DemoSeatTokenNotIssuedException(
                    "Keycloak refused the demo seat direct grant for " + keycloakUsername
                            + " (status " + refused.getStatusCode().value() + ")", refused);
        } catch (ResourceAccessException unreachable) {
            throw new DemoSeatTokenNotIssuedException(
                    "Keycloak is unreachable for the demo seat direct grant", unreachable);
        }
    }

    record DirectGrantResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {}
}
