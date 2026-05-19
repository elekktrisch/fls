package ch.alpenflight.platform.security;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

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
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final ClubAwareJwtAuthenticationConverter jwtAuthenticationConverter;
    private final LoggingBearerTokenAuthenticationEntryPoint authenticationEntryPoint;

    public SecurityConfig(ClubAwareJwtAuthenticationConverter jwtAuthenticationConverter,
            LoggingBearerTokenAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
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
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
