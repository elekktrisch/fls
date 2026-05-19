package ch.alpenflight.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Wraps Spring Security's {@link BearerTokenAuthenticationEntryPoint} (which
 * is {@code final} in Spring Security 7 — composition only) with a single
 * INFO log on every rejected request. Without it the resource server's 401s
 * are silent, leaving S-027 (audit log) and any downstream alerting blind to
 * "wrong-issuer / expired / malformed JWT" spikes.
 *
 * <p>The log payload is the rejecting exception class only — no token bytes,
 * no claims map, no principal — so the line stays PII-free. The
 * {@code WWW-Authenticate} response header still carries the standard
 * {@code error="invalid_token"} machine-readable hint emitted by the delegate.
 */
@Component
class LoggingBearerTokenAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingBearerTokenAuthenticationEntryPoint.class);

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) {
        LOG.info("bearer-token authentication rejected: {} on {}",
                authException.getClass().getSimpleName(), request.getRequestURI());
        delegate.commence(request, response, authException);
    }
}
