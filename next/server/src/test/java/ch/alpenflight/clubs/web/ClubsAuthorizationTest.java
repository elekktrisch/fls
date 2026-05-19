package ch.alpenflight.clubs.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Role-gate matrix evidence for the Clubs surface — the canonical reference
 * for "every controller maps its three legacy roles correctly". Runs under
 * the default profile (so {@link ch.alpenflight.platform.security.SecurityConfig}
 * is active) and uses {@code SecurityMockMvcRequestPostProcessors.jwt()} to
 * plant arbitrary authorities + claims per request — exercising the same
 * {@code @PreAuthorize} predicates the production chain enforces.
 *
 * <p>The "mock is real auth shape, not a bypass" invariant is locked here:
 * predicates run against minted {@code JwtAuthenticationToken}s carrying
 * realistic {@code clubId} claims, so the SpEL own-club / other-club
 * branches are real assertions, not stubbed shortcuts.
 *
 * <p>Per-role coverage:
 *
 * <ul>
 *   <li>anonymous → 401 on every method (chain-level — single representative).</li>
 *   <li>SYSTEM_ADMINISTRATOR → access to every method.</li>
 *   <li>CLUB_ADMINISTRATOR → list / create / delete denied; read + update
 *       allowed only for the principal's own club (SpEL gate).</li>
 *   <li>FLIGHT_OPERATOR → read-only viewer: list + read-own-club allowed;
 *       other-club read denied; all mutations denied.</li>
 * </ul>
 *
 * <p>Companion live-chain test {@link SecurityFilterChainIT} covers
 * {@code JwtDecoder} / issuer / signature validation against synthesised RSA
 * tokens; that's the only place a misconfigured decoder surfaces, since this
 * matrix uses Spring Security's test post-processor that bypasses the
 * decoder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class ClubsAuthorizationTest {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;

    // V5 seed row + a different (non-existent-in-db) tenant UUID. The other-
    // club tests don't need a real row to exist because the SpEL clause
    // (#id.value().toString() == principal.claims['clubId']) returns false
    // before any DB lookup, producing a 403 — no second seed needed.
    private static final String SEED_CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";
    private static final String SEED_CLUB_PATH = "clb-" + SEED_CLUB_ID;
    private static final String OTHER_CLUB_ID = "019e30c3-2c00-7001-8000-000000000999";

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

    // ----- Anonymous -----

    @Test
    void list_anonymous_returns_401() throws Exception {
        mvc.perform(get("/api/v1/clubs")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_anonymous_returns_bearer_challenge_header() throws Exception {
        mvc.perform(get("/api/v1/clubs"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("WWW-Authenticate",
                                org.hamcrest.Matchers.startsWith("Bearer")));
    }

    // ----- SYSTEM_ADMINISTRATOR -----

    @Test
    void list_sysadmin_returns_200() throws Exception {
        mvc.perform(get("/api/v1/clubs").with(sysadmin()))
                .andExpect(status().isOk());
    }

    @Test
    void get_sysadmin_returns_200() throws Exception {
        mvc.perform(get("/api/v1/clubs/" + SEED_CLUB_PATH).with(sysadmin()))
                .andExpect(status().isOk());
    }

    @Test
    void post_sysadmin_returns_201() throws Exception {
        String slug = "sysadmin-" + unique();
        String body = createPayload("Sysadmin Club", slug, "SY" + shortUnique());
        mvc.perform(post("/api/v1/clubs")
                        .with(sysadmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void put_sysadmin_returns_200_on_seed() throws Exception {
        String body = updatePayload("Seed Club", "seed-club-1", false);
        mvc.perform(put("/api/v1/clubs/" + SEED_CLUB_PATH)
                        .with(sysadmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void delete_sysadmin_returns_204_on_throwaway_club() throws Exception {
        // Create then delete in the same test to avoid touching the canonical seed row.
        String slug = "del-sys-" + unique();
        String createdPath = createAsSysadmin("To Delete", slug, "DS" + shortUnique());
        mvc.perform(delete("/api/v1/clubs/" + createdPath).with(sysadmin()))
                .andExpect(status().isNoContent());
    }

    // ----- CLUB_ADMINISTRATOR (own club) -----

    @Test
    void list_clubadmin_returns_403() throws Exception {
        mvc.perform(get("/api/v1/clubs").with(clubadmin(SEED_CLUB_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_clubadmin_own_club_returns_200() throws Exception {
        mvc.perform(get("/api/v1/clubs/" + SEED_CLUB_PATH).with(clubadmin(SEED_CLUB_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void put_clubadmin_own_club_returns_200() throws Exception {
        String body = updatePayload("Seed Club", "seed-club-1", false);
        mvc.perform(put("/api/v1/clubs/" + SEED_CLUB_PATH)
                        .with(clubadmin(SEED_CLUB_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void post_clubadmin_returns_403() throws Exception {
        String body = createPayload("Club Admin Try", "ca-" + unique(), "CA" + shortUnique());
        mvc.perform(post("/api/v1/clubs")
                        .with(clubadmin(SEED_CLUB_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_clubadmin_own_club_returns_403() throws Exception {
        mvc.perform(delete("/api/v1/clubs/" + SEED_CLUB_PATH).with(clubadmin(SEED_CLUB_ID)))
                .andExpect(status().isForbidden());
    }

    // ----- CLUB_ADMINISTRATOR (other club) — SpEL gate -----

    @Test
    void get_clubadmin_other_club_returns_403() throws Exception {
        mvc.perform(get("/api/v1/clubs/" + SEED_CLUB_PATH).with(clubadmin(OTHER_CLUB_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void put_clubadmin_other_club_returns_403() throws Exception {
        String body = updatePayload("Hijack Attempt", "seed-club-1", false);
        mvc.perform(put("/api/v1/clubs/" + SEED_CLUB_PATH)
                        .with(clubadmin(OTHER_CLUB_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ----- FLIGHT_OPERATOR (own club, read-only) -----

    @Test
    void list_flightoperator_returns_200() throws Exception {
        mvc.perform(get("/api/v1/clubs").with(flightOperator(SEED_CLUB_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void get_flightoperator_own_club_returns_200() throws Exception {
        mvc.perform(get("/api/v1/clubs/" + SEED_CLUB_PATH).with(flightOperator(SEED_CLUB_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void get_flightoperator_other_club_returns_403() throws Exception {
        mvc.perform(get("/api/v1/clubs/" + SEED_CLUB_PATH).with(flightOperator(OTHER_CLUB_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void post_flightoperator_returns_403() throws Exception {
        String body = createPayload("Op Try", "op-" + unique(), "OP" + shortUnique());
        mvc.perform(post("/api/v1/clubs")
                        .with(flightOperator(SEED_CLUB_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void put_flightoperator_own_club_returns_403() throws Exception {
        String body = updatePayload("Seed Club", "seed-club-1", false);
        mvc.perform(put("/api/v1/clubs/" + SEED_CLUB_PATH)
                        .with(flightOperator(SEED_CLUB_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_flightoperator_own_club_returns_403() throws Exception {
        mvc.perform(delete("/api/v1/clubs/" + SEED_CLUB_PATH).with(flightOperator(SEED_CLUB_ID)))
                .andExpect(status().isForbidden());
    }

    // ----- SPA mock-auth header rejection (post-rip) -----

    @Test
    void list_with_legacy_mock_auth_header_returns_401() throws Exception {
        // The SPA mock-auth interceptor still stamps `Bearer mock-sysadmin`
        // under the ng `mock-auth` configuration; once the backend mock
        // chain is gone, that header reaches the real resource server and
        // gets rejected as an invalid JWT. The SPA seam stays alive as a
        // Playwright-CI convenience (specs stub the backend); accidental
        // hits against the live backend fail loudly with 401.
        mvc.perform(get("/api/v1/clubs").header("Authorization", "Bearer mock-sysadmin"))
                .andExpect(status().isUnauthorized());
    }

    // ----- helpers -----

    private static RequestPostProcessor sysadmin() {
        return jwt()
                .jwt(t -> t.claim("clubId", SEED_CLUB_ID))
                .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMINISTRATOR"));
    }

    private static RequestPostProcessor clubadmin(String clubId) {
        return jwt()
                .jwt(t -> t.claim("clubId", clubId))
                .authorities(new SimpleGrantedAuthority("ROLE_CLUB_ADMINISTRATOR"));
    }

    private static RequestPostProcessor flightOperator(String clubId) {
        return jwt()
                .jwt(t -> t.claim("clubId", clubId))
                .authorities(new SimpleGrantedAuthority("ROLE_FLIGHT_OPERATOR"));
    }

    private String createAsSysadmin(String name, String slug, String clubKey) throws Exception {
        String body = createPayload(name, slug, clubKey);
        String responseBody = mvc.perform(post("/api/v1/clubs")
                        .with(sysadmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int idx = responseBody.indexOf("\"id\":\"");
        if (idx < 0) {
            throw new IllegalStateException("No id field in response: " + responseBody);
        }
        int start = idx + 6;
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
    }

    private static String createPayload(String name, String slug, String clubKey) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"slug\":\"" + slug + "\","
                + "\"clubKey\":\"" + clubKey + "\","
                + "\"publicRegistrationEnabled\":false"
                + "}";
    }

    private static String updatePayload(String name, String slug, boolean publicReg) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"slug\":\"" + slug + "\","
                + "\"publicRegistrationEnabled\":" + publicReg
                + "}";
    }

    private static String unique() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String shortUnique() {
        String s = Long.toString(System.nanoTime(), 36).toUpperCase(java.util.Locale.ROOT);
        return s.length() > 4 ? s.substring(s.length() - 4) : s;
    }
}
