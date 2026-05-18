package ch.alpenflight.clubs;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Role-gate evidence for the Clubs surface. Runs under the default profile
 * (no {@code mock-auth} — so {@link ch.alpenflight.platform.security.SecurityConfig}
 * is the active chain) and uses {@code SecurityMockMvcRequestPostProcessors.jwt()}
 * to plant arbitrary authorities into the SecurityContext per request. Proves
 * that {@code @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")} actually
 * gates — the "mock is real auth shape, not a bypass" invariant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class ClubsAuthorizationTest {

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

    @Test
    void list_with_clubAdmin_role_returns_403() throws Exception {
        mvc.perform(get("/api/v1/clubs").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_CLUB_ADMINISTRATOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_with_sysadmin_role_returns_200() throws Exception {
        mvc.perform(get("/api/v1/clubs").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_SYSTEM_ADMINISTRATOR"))))
                .andExpect(status().isOk());
    }

    @Test
    void list_anonymous_returns_401() throws Exception {
        mvc.perform(get("/api/v1/clubs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_anonymous_returns_bearer_challenge_header() throws Exception {
        mvc.perform(get("/api/v1/clubs"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("WWW-Authenticate",
                                org.hamcrest.Matchers.startsWith("Bearer")));
    }
}
