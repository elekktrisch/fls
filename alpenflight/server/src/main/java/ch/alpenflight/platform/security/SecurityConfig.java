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

    private static final String[] ANONYMOUS_DOC_AND_PROBE_PATHS_WILDCARD_MISSES_BASE_AND_DOTTED_SUFFIX = {
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/error"};

    private static final String ANONYMOUS_PUBLIC_READS_ANY_GET_UNDER_THE_PUBLIC_SEGMENT =
            "/api/v1/public/**";

    private static final String[] ANONYMOUS_PUBLIC_WRITES_ENUMERATED_SO_A_NEW_ONE_STAYS_AUTHENTICATED = {
            "/api/v1/public/clubs/*/discovery-flight-registrations",
            "/api/v1/public/clubs/*/scenic-flight-registrations"};

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
                                ANONYMOUS_DOC_AND_PROBE_PATHS_WILDCARD_MISSES_BASE_AND_DOTTED_SUFFIX)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET,
                                ANONYMOUS_PUBLIC_READS_ANY_GET_UNDER_THE_PUBLIC_SEGMENT)
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                ANONYMOUS_PUBLIC_WRITES_ENUMERATED_SO_A_NEW_ONE_STAYS_AUTHENTICATED)
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
