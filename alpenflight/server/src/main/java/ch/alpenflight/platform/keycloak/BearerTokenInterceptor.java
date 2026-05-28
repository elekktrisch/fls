package ch.alpenflight.platform.keycloak;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Attaches the cached service-account bearer token to every outbound call.
 * The token is fetched lazily and rotated by
 * {@link KeycloakAdminTokenSupplier}; this interceptor just reads the
 * current value.
 *
 * <p>Public so adapters in sibling modules (e.g. S-138 tenancy.provisioning)
 * can build their own admin {@link org.springframework.web.client.RestClient}
 * instances against the same token supplier without re-implementing the
 * bearer-injection logic.
 */
public final class BearerTokenInterceptor implements ClientHttpRequestInterceptor {

    private final KeycloakAdminTokenSupplier tokens;

    public BearerTokenInterceptor(KeycloakAdminTokenSupplier tokens) {
        this.tokens = tokens;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().setBearerAuth(tokens.currentToken());
        return execution.execute(request, body);
    }
}
