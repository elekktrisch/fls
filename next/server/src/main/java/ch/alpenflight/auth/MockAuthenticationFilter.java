package ch.alpenflight.auth;

import ch.alpenflight.platform.security.ClubAwareJwtAuthenticationConverter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * S-048 walking-skeleton authentication filter. Builds a hand-crafted
 * {@link Jwt} matching the shape Keycloak emits and routes it through the
 * production-grade {@link ClubAwareJwtAuthenticationConverter} so the
 * authority shape is exercised under the mock. When S-020 lands, this
 * filter is replaced by Spring Security's {@code BearerTokenAuthenticationFilter}
 * against a real {@code JwtDecoder} — same converter, same authorities, same
 * predicates.
 *
 * <p>DELETE WITH PARENT PACKAGE when S-026 ships (see {@link MockSecurityConfig}
 * Javadoc for the rip-out plan; original S-020 deferral to S-022 was itself
 * deferred to S-026 in S-022's refine).
 */
public class MockAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(MockAuthenticationFilter.class);

    private static final Jwt MOCK_JWT = Jwt.withTokenValue("mock-sysadmin")
            .header("alg", "none")
            .subject("mock-sysadmin")
            .claim("clubId", "019e30c3-2c00-7001-8000-000000000001")
            .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR")))
            .issuedAt(Instant.EPOCH)
            .expiresAt(Instant.MAX)
            .build();

    private final ClubAwareJwtAuthenticationConverter converter;

    public MockAuthenticationFilter(ClubAwareJwtAuthenticationConverter converter) {
        this.converter = converter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        AbstractAuthenticationToken token = converter.convert(MOCK_JWT);
        if (token != null) {
            token.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(token);
        } else {
            LOG.warn("[mock-auth] converter returned null token");
        }
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
