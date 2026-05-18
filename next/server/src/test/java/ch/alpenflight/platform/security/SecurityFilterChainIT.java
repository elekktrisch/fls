package ch.alpenflight.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-chain resource-server verification — exercises the production
 * {@link org.springframework.security.web.SecurityFilterChain} against
 * synthesised tokens minted by {@link JwtTestFixture}. The fixture replaces
 * the production {@code JwtDecoder} bean with one that validates against a
 * test RSA key + the {@code http://test-issuer} issuer; everything else
 * (filter chain, validator chain, authentication converter) is identical
 * to the live config.
 *
 * <p>The {@code @WithMockUser}-style {@code .with(jwt())} shortcut used in
 * {@code ClubsAuthorizationTest} bypasses the {@code JwtDecoder} entirely;
 * this IT is the only place a misconfigured {@code JwtIssuerValidator} or a
 * weakened decoder would surface.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwtTestFixture.class)
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class SecurityFilterChainIT {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::jdbcUrl);
        r.add("spring.datasource.username", POSTGRES::username);
        r.add("spring.datasource.password", POSTGRES::password);
        r.add("spring.flyway.url", POSTGRES::jdbcUrl);
        r.add("spring.flyway.user", POSTGRES::username);
        r.add("spring.flyway.password", POSTGRES::password);
    }

    @Autowired MockMvc mvc;
    @Autowired JwtTestFixture jwts;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired ApplicationContext ctx;

    // S-021 ripped out /api/v1/hello as a permitAll smoke endpoint. The
    // resource-server chain is now exercised against /api/v1/clubs — token
    // validation (signature / issuer / expiry / alg) happens BEFORE
    // authority enforcement, so the 401 paths below are independent of
    // whether the token carries SYSTEM_ADMINISTRATOR. The "valid token →
    // 200" smoke is covered by clubAware_converter_maps_realm_roles_to_authorities
    // below, which mints a token with the required role.
    @Test
    void protected_endpoint_anonymous_returns_401_with_bearer_challenge() throws Exception {
        mvc.perform(get("/api/v1/clubs"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.startsWith("Bearer")));
    }

    @Test
    void expired_token_returns_401() throws Exception {
        String token = jwts.mint(c -> c
                .issueTime(Date.from(Instant.now().minusSeconds(120)))
                .expirationTime(Date.from(Instant.now().minusSeconds(60))));
        mvc.perform(get("/api/v1/clubs").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrong_issuer_token_returns_401() throws Exception {
        String token = jwts.mint(c -> c.issuer("http://other-issuer"));
        mvc.perform(get("/api/v1/clubs").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void alg_none_token_returns_401() throws Exception {
        String token = jwts.mintWithoutSignature(c -> { });
        mvc.perform(get("/api/v1/clubs").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clubAware_converter_maps_realm_roles_to_authorities() throws Exception {
        String token = jwts.mint(c -> c
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));
        mvc.perform(get("/api/v1/clubs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void public_paths_remain_anonymous() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void jwt_decoder_bean_is_present_and_rejects_invalid_token() {
        BearerTokenAuthenticationToken bogus = new BearerTokenAuthenticationToken("not-a-jwt");
        assertThat(bogus).isNotNull();
        assertThat(ctx.getBeansOfType(JwtDecoder.class)).isNotEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jwtDecoder.decode("not-a-jwt"))
                .isInstanceOfAny(JwtValidationException.class,
                        org.springframework.security.oauth2.jwt.BadJwtException.class);
    }
}
