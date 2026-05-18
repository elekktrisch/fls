package ch.alpenflight.auth;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import ch.alpenflight.platform.security.ClubAwareJwtAuthenticationConverter;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * S-048 walking-skeleton: profile-gated ({@code mock-auth}) security chain
 * that injects a fixed SYSTEM_ADMINISTRATOR principal on every request. The
 * {@code @PreAuthorize} predicates downstream are real — only the principal
 * source is mocked.
 *
 * <h2>RIP-OUT PLAN (deferred to S-026 — role enforcement)</h2>
 *
 * S-020 originally targeted S-022 as the rip-out point; S-022's refine
 * (PR #63) deferred the rip-out to S-026 so the walking-skeleton operator-
 * demo + dev auth-free path stays usable until real role gates land. When
 * S-026 ships:
 *
 * <ol>
 *   <li>Delete the whole {@code ch.alpenflight.auth} package (3 files).</li>
 *   <li>Delete {@code application-mock-auth.yml}.</li>
 *   <li>Delete the SPA-side {@code mock-auth.interceptor.ts} +
 *       {@code mock-auth.bootstrap.ts} + {@code core/auth/README.md}.</li>
 *   <li>Drop {@code SPRING_PROFILES_ACTIVE=mock-auth} from any compose /
 *       run config.</li>
 *   <li>{@link ClubAwareJwtAuthenticationConverter} stays — S-020 wires it
 *       against the real {@code JwtDecoder} via
 *       {@code oauth2ResourceServer().jwt().jwtAuthenticationConverter(...)}.</li>
 *   <li>{@link ch.alpenflight.clubs.ClubsController} {@code @PreAuthorize}
 *       expressions DO NOT change. The auth seam is the principal source,
 *       not the predicate.</li>
 * </ol>
 */
@Configuration
@EnableMethodSecurity
@Profile("mock-auth")
public class MockSecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MockSecurityConfig.class);

    private final Environment environment;

    public MockSecurityConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Fail-fast guard: the {@code mock-auth} profile MUST NEVER be co-active
     * with {@code prod}. Throwing during context init kills the application
     * before any HTTP listener opens — the operator sees a loud startup
     * failure rather than a silently-unauthenticated production server.
     */
    @PostConstruct
    void forbidInProd() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains("prod")) {
            throw new IllegalStateException(
                    "FATAL: spring profile `mock-auth` (S-048 dev-only) must NEVER co-activate "
                            + "with `prod`. Active profiles: " + active
                            + ". See ch.alpenflight.auth.MockSecurityConfig.");
        }
        LOG.warn("================================================================");
        LOG.warn("  DEV-ONLY MOCK AUTH ACTIVE — DO NOT RUN IN PRODUCTION (S-048)");
        LOG.warn("  Every request is authenticated as SYSTEM_ADMINISTRATOR.");
        LOG.warn("================================================================");
    }

    @Bean
    SecurityFilterChain mockFilterChain(HttpSecurity http, MockAuthenticationFilter mockFilter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/swagger-ui",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/api/v1/hello",
                                // Spring dispatches 4xx to /error for the
                                // default JSON error renderer — denying it
                                // anonymous turns a 400 into a 403 and
                                // hides the real status from the client.
                                "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(mockFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    MockAuthenticationFilter mockAuthenticationFilter(ClubAwareJwtAuthenticationConverter converter) {
        return new MockAuthenticationFilter(converter);
    }

    /**
     * Prevent Spring Boot's servlet-filter auto-registration from picking up
     * {@link MockAuthenticationFilter} as a standalone filter. Without this,
     * the filter runs OUTSIDE the security chain first (auth context gets
     * cleared by {@code SecurityContextHolderFilter} before {@code @PreAuthorize}
     * sees it) and {@link org.springframework.web.filter.OncePerRequestFilter}'s
     * "already applied" guard then skips the in-chain invocation — every
     * authenticated POST / PUT / DELETE 403's.
     */
    @Bean
    FilterRegistrationBean<MockAuthenticationFilter> mockAuthenticationFilterRegistration(
            MockAuthenticationFilter filter) {
        FilterRegistrationBean<MockAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
