package ch.alpenflight.users.infra.keycloak;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Caches the service-account access token for {@code alpenflight-backend-admin}.
 *
 * <p>The token is fetched lazily on first use and rotated when the cached
 * value is within {@link KeycloakAdminProperties#refreshSkewSeconds()} of
 * expiry. A {@link ReentrantLock} serialises concurrent rotations so a burst
 * of admin calls produces one token-endpoint hit, not N.
 */
@Component
public class KeycloakAdminTokenSupplier {

    private final RestClient http;
    private final KeycloakAdminProperties props;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private @Nullable String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    public KeycloakAdminTokenSupplier(RestClient.Builder builder,
                                      KeycloakAdminProperties props,
                                      Clock clock) {
        this.http = builder.build();
        this.props = props;
        this.clock = clock;
    }

    public String currentToken() {
        Instant now = clock.instant();
        String snapshot = cachedToken;
        if (snapshot != null && now.isBefore(expiresAt.minusSeconds(props.refreshSkewSeconds()))) {
            return snapshot;
        }
        lock.lock();
        try {
            // Re-check after acquiring — another caller may have rotated.
            snapshot = cachedToken;
            Instant after = clock.instant();
            if (snapshot != null && after.isBefore(expiresAt.minusSeconds(props.refreshSkewSeconds()))) {
                return snapshot;
            }
            TokenResponse fresh = fetchToken();
            this.cachedToken = fresh.accessToken;
            this.expiresAt = clock.instant().plus(Duration.ofSeconds(fresh.expiresIn));
            return fresh.accessToken;
        } finally {
            lock.unlock();
        }
    }

    private TokenResponse fetchToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        try {
            TokenResponse body = http.post()
                    .uri(props.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (body == null || body.accessToken == null || body.accessToken.isBlank()) {
                throw new KeycloakAdminException("Keycloak token endpoint returned empty access_token");
            }
            return body;
        } catch (HttpStatusCodeException e) {
            // Status only; never the response body — it may carry the
            // service-account-client error grant context.
            throw new KeycloakAdminException(
                    "Keycloak token endpoint refused client-credentials grant (status "
                            + e.getStatusCode().value() + ")", e);
        }
    }

    public record TokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") long expiresIn) {}
}
