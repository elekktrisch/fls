package ch.alpenflight.users.infra.keycloak;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Debug-log every outbound call to the Keycloak admin REST with the
 * {@code Authorization} header redacted. The admin bearer token is
 * sensitive; any sidecar log forwarder would otherwise surface it.
 */
final class RedactingRestClientInterceptor implements ClientHttpRequestInterceptor {

    static final RedactingRestClientInterceptor INSTANCE = new RedactingRestClientInterceptor();

    private static final Logger LOG = LoggerFactory.getLogger(RedactingRestClientInterceptor.class);

    private RedactingRestClientInterceptor() {}

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (LOG.isDebugEnabled()) {
            String authShape = request.getHeaders().get(HttpHeaders.AUTHORIZATION) != null
                    ? "Bearer <redacted>"
                    : "(absent)";
            LOG.debug("kc-admin {} {} Authorization={}",
                    request.getMethod(), request.getURI(), authShape);
        }
        return execution.execute(request, body);
    }
}
