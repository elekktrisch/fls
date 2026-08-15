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
