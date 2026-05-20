package ch.alpenflight.locations.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
 * Role-gate matrix evidence for the Locations surface. Locations are
 * reference data (cross-tenant) — reads open to {@code isAuthenticated()},
 * writes gated SYSTEM_ADMINISTRATOR-only.
 *
 * <p>Coverage:
 * <ul>
 *   <li>anonymous → 401 on read + write (chain-level).</li>
 *   <li>SYSTEM_ADMINISTRATOR → 200/201/204 on read + write.</li>
 *   <li>CLUB_ADMINISTRATOR / FLIGHT_OPERATOR / OFFICE_USER → 200 on read,
 *       403 on every write (no own-club SpEL gate — reference data is
 *       cross-tenant; no `clubId` claim is dereferenced).</li>
 *   <li>Cross-tenant read identity — two clubId-claimed tokens see the
 *       same row, proving no {@code @TenantId} filter is applied.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class LocationsAuthorizationIT {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CLUB_A = "019e30c3-2c00-7001-8000-0000000000a1";
    private static final String CLUB_B = "019e30c3-2c00-7001-8000-0000000000a2";

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
        mvc.perform(get("/api/v1/locations")).andExpect(status().isUnauthorized());
    }

    @Test
    void post_anonymous_returns_401() throws Exception {
        mvc.perform(post("/api/v1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Anon " + LocationsControllerIT.suffix(),
                                LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isUnauthorized());
    }

    // ----- SYSTEM_ADMINISTRATOR (write-allowed) -----

    @Test
    void post_sysadmin_returns_201() throws Exception {
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Sysadmin " + LocationsControllerIT.suffix(),
                                LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isCreated());
    }

    @Test
    void list_sysadmin_returns_200() throws Exception {
        mvc.perform(get("/api/v1/locations").with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isOk());
    }

    @Test
    void delete_sysadmin_returns_204() throws Exception {
        String externalId = createAsSysadmin();
        mvc.perform(delete("/api/v1/locations/" + externalId)
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isNoContent());
    }

    @Test
    void put_sysadmin_returns_200() throws Exception {
        String icao = LocationsControllerIT.uniqueIcao();
        String externalId = createAsSysadminWithIcao(icao);
        mvc.perform(put("/api/v1/locations/" + externalId)
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.updatePayload(
                                "Renamed by sysadmin", icao))))
                .andExpect(status().isOk());
    }

    // ----- CLUB_ADMINISTRATOR (read OK, write FORBIDDEN) -----

    @Test
    void list_clubadmin_returns_200() throws Exception {
        mvc.perform(get("/api/v1/locations").with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isOk());
    }

    @Test
    void post_clubadmin_returns_403() throws Exception {
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Club admin try " + LocationsControllerIT.suffix(),
                                LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void put_clubadmin_returns_403() throws Exception {
        String icao = LocationsControllerIT.uniqueIcao();
        String externalId = createAsSysadminWithIcao(icao);
        mvc.perform(put("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.updatePayload(
                                "Hijack attempt", icao))))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_clubadmin_returns_403() throws Exception {
        String externalId = createAsSysadmin();
        mvc.perform(delete("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isForbidden());
    }

    // ----- FLIGHT_OPERATOR (read OK, write FORBIDDEN) -----

    @Test
    void list_flightoperator_returns_200() throws Exception {
        mvc.perform(get("/api/v1/locations").with(role("ROLE_FLIGHT_OPERATOR", CLUB_A)))
                .andExpect(status().isOk());
    }

    @Test
    void post_flightoperator_returns_403() throws Exception {
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_FLIGHT_OPERATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Flight op try " + LocationsControllerIT.suffix(),
                                LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isForbidden());
    }

    // ----- OFFICE_USER (read OK, write FORBIDDEN) -----

    @Test
    void list_office_user_returns_200() throws Exception {
        mvc.perform(get("/api/v1/locations").with(role("ROLE_OFFICE_USER", CLUB_A)))
                .andExpect(status().isOk());
    }

    @Test
    void post_office_user_returns_403() throws Exception {
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_OFFICE_USER", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Office try " + LocationsControllerIT.suffix(),
                                LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_office_user_returns_403() throws Exception {
        String externalId = createAsSysadmin();
        mvc.perform(delete("/api/v1/locations/" + externalId)
                        .with(role("ROLE_OFFICE_USER", CLUB_A)))
                .andExpect(status().isForbidden());
    }

    // ----- Cross-tenant read identity -----

    @Test
    void get_clubA_and_clubB_see_the_same_row() throws Exception {
        String externalId = createAsSysadmin();
        String pathA = mvc.perform(get("/api/v1/locations/" + externalId)
                        .with(role("ROLE_FLIGHT_OPERATOR", CLUB_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pathB = mvc.perform(get("/api/v1/locations/" + externalId)
                        .with(role("ROLE_FLIGHT_OPERATOR", CLUB_B)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode a = MAPPER.readTree(pathA);
        JsonNode b = MAPPER.readTree(pathB);
        org.assertj.core.api.Assertions.assertThat(a.get("id").asText())
                .as("Reference-data reads must surface the IDENTICAL row across tenant claims "
                        + "— proves Location is not @TenantId-filtered")
                .isEqualTo(b.get("id").asText());
    }

    // ----- helpers -----

    private static RequestPostProcessor role(String authority, String clubId) {
        return jwt()
                .jwt(t -> t.claim("clubId", clubId))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    private String createAsSysadmin() throws Exception {
        return createAsSysadminWithIcao(LocationsControllerIT.uniqueIcao());
    }

    private String createAsSysadminWithIcao(String icao) throws Exception {
        Map<String, Object> body = LocationsControllerIT.createPayload(
                "Authz fixture " + LocationsControllerIT.suffix(), icao);
        String responseBody = mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = MAPPER.readTree(responseBody);
        return node.get("id").asText();
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise payload", e);
        }
    }
}
