package ch.alpenflight.platform.security;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Production {@link SecurityFilterChain} — active whenever the
 * {@code mock-auth} profile is NOT on. Wires Spring Security 7 as an OAuth2
 * resource server validating JWT bearer tokens against the issuer's JWKS
 * (per ADR 0007). CSRF is disabled because the chain is stateless and
 * Bearer-bound; re-enable only if a future cookie-bound flow is introduced.
 *
 * <p>{@link JwtDecoderConfig} owns the decoder bean and is profile-agnostic
 * so its presence suppresses Spring Boot's resource-server auto-config under
 * every profile — the {@code mock-auth} chain doesn't consume the decoder
 * but its declaration prevents auto-config from firing an OIDC discovery
 * call against an unreachable issuer.
 *
 * <p>{@link EnableMethodSecurity} turns on {@code @PreAuthorize}, which the
 * {@code mock-auth} chain also relies on against a hand-crafted principal.
 */
@Configuration
@EnableMethodSecurity
@Profile("!mock-auth")
public class SecurityConfig {

    private final ClubAwareJwtAuthenticationConverter jwtAuthenticationConverter;

    public SecurityConfig(ClubAwareJwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
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
                .oauth2ResourceServer(o -> o.jwt(j -> j
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
