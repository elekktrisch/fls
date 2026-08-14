package ch.alpenflight.platform.security;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * Production {@link SecurityFilterChain}. Wires Spring Security 7 as an
 * OAuth2 resource server validating JWT bearer tokens against the issuer's
 * JWKS (per ADR 0007). CSRF is disabled because the chain is stateless and
 * Bearer-bound; re-enable only if a future cookie-bound flow is introduced.
 *
 * <p>{@link JwtDecoderConfig} owns the decoder bean so its presence
 * suppresses Spring Boot's resource-server auto-config — the bean
 * declaration prevents auto-config from firing an OIDC discovery call
 * against an unreachable issuer during context startup.
 *
 * <p>{@link EnableMethodSecurity} turns on {@code @PreAuthorize}; the
 * canonical role-gate matrix lives on {@code ClubsController} (S-026).
 *
 * <p>Everything not listed as {@code permitAll} is {@code authenticated}. The
 * {@code /api/v1/public/**} entries are the application's only anonymous API
 * surface (S-025); the club they act on comes from the URL, and
 * {@code ch.alpenflight.publicregistration} validates it against the
 * {@code public_registration_enabled} allowlist before any tenant scope opens.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final ClubAwareJwtAuthenticationConverter jwtAuthenticationConverter;
    private final LoggingBearerTokenAuthenticationEntryPoint authenticationEntryPoint;
    private final JitUserMaterializer jitUserMaterializer;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SecurityConfig(ClubAwareJwtAuthenticationConverter jwtAuthenticationConverter,
            LoggingBearerTokenAuthenticationEntryPoint authenticationEntryPoint,
            JitUserMaterializer jitUserMaterializer,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.jitUserMaterializer = jitUserMaterializer;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Bean
    SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        JitUserMaterializationFilter jitFilter =
                new JitUserMaterializationFilter(jitUserMaterializer, objectMapper, meterRegistry);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(
                                // springdoc roots — both the base path and the
                                // dotted suffixes (yaml/json) must be enumerated;
                                // `/v3/api-docs/**` matches only deeper paths.
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/swagger-ui",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/error")
                        .permitAll()
                        // Anonymous public-registration surface (S-025). Reads under
                        // the /public/ segment are open wholesale; writes are NOT —
                        // each anonymous write is enumerated, so an unrelated POST /
                        // PUT / PATCH / DELETE landing under the same prefix stays
                        // authenticated instead of inheriting anonymous access.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/public/clubs/*/discovery-flight-registrations",
                                "/api/v1/public/clubs/*/scenic-flight-registrations")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .addFilterAfter(jitFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }
}
