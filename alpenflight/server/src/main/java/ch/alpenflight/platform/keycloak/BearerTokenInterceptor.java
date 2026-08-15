package ch.alpenflight.platform.keycloak;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

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
