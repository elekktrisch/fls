package ch.alpenflight.platform.security;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Default {@link SecurityFilterChain} — active whenever the
 * {@code mock-auth} profile is NOT on. The real OAuth2 resource server
 * (S-020) replaces this with a {@code jwtAuthenticationConverter}-equipped
 * chain; until then, this baseline permits the unauthenticated public
 * surface (OpenAPI / actuator / hello smoke endpoint) and refuses everything
 * under {@code /api/v1/**} — Spring Security's default response is 401, so
 * any new authenticated endpoint added before S-020 is safe-by-default.
 *
 * <p>{@link EnableMethodSecurity} turns on {@code @PreAuthorize} processing,
 * which is what the {@code mock-auth} profile relies on to exercise role
 * predicates against the mocked principal.
 */
@Configuration
@EnableMethodSecurity
@Profile("!mock-auth")
public class SecurityConfig {

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
                                "/api/v1/hello",
                                "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // Anonymous requests get 401, not the Spring-Security-7
                // default 403. Aligns with REST conventions + matches what
                // S-020's BearerTokenAuthenticationEntryPoint will produce.
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
