package ch.alpenflight.platform.hello;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Postgres-backed companion to {@link HelloControllerIT}'s slice test. Boots
 * the whole stack on top of the shared test container (via
 * {@link PostgresIntegrationTest}) and signs a real Bearer token via
 * {@link JwtTestFixture} so the production security chain — not mock-auth —
 * resolves the principal end-to-end. Proves the S-015 base class works for
 * a controller-level integration test against the real DB + the real auth
 * filter, not just slice-style tests.
 */
@AutoConfigureMockMvc
@Import(JwtTestFixture.class)
class HelloEndpointPostgresIT extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTestFixture jwt;

    @Test
    void hello_endpoint_returns_200_with_expected_body_when_authenticated() throws Exception {
        String token = jwt.mint(b -> {});
        mockMvc.perform(get("/api/v1/hello")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello AlpenFlight"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void hello_endpoint_returns_401_when_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/hello"))
                .andExpect(status().isUnauthorized());
    }
}
