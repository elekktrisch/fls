package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Role-gate + tenant-isolation matrix for Locations. After S-049b Locations
 * are TENANT_SCOPED via {@code @TenantId}: writes open to {@code CLUB_ADMIN}
 * (own club only) + {@code SYSTEM_ADMIN} (any club, by claim or runAs);
 * reads are tenant-filtered.
 *
 * <p>Coverage:
 * <ul>
 *   <li>anonymous → 401 on read + write.</li>
 *   <li>CLUB_ADMIN → 200/201/204 on own-club CRUD; 404 (row invisible) on
 *       another club's Location id.</li>
 *   <li>SYSTEM_ADMIN → 200/201/204 on whichever club its {@code clubId} claim
 *       points at.</li>
 *   <li>FLIGHT_OPERATOR / OFFICE_USER → 200 on read, 403 on every write
 *       (not in the writer roster).</li>
 *   <li>Cross-tenant isolation: CLUB_A's list and CLUB_B's list are disjoint.</li>
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

    private static final String NAME_PREFIX = "IT_LA_";
    private static final String KEY_PREFIX = "IT_A_";

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
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedTwoClubs() {
        cleanupPreviousRun();
        seedClub(CLUB_A, "alpha");
        seedClub(CLUB_B, "bravo");
    }

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

    // ----- SYSTEM_ADMINISTRATOR (write-allowed under whichever club its claim asserts) -----

    @Test
    void post_sysadmin_under_clubA_returns_201() throws Exception {
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Sysadmin " + LocationsControllerIT.suffix(),
                                LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isCreated());
    }

    @Test
    void delete_sysadmin_returns_204() throws Exception {
        String externalId = createUnder(CLUB_A);
        mvc.perform(delete("/api/v1/locations/" + externalId)
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isNoContent());
    }

    // ----- CLUB_ADMINISTRATOR — full own-club CRUD allowed -----

    @Test
    void list_clubadmin_returns_200() throws Exception {
        mvc.perform(get("/api/v1/locations").with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isOk());
    }

    @Test
    void post_clubadmin_own_club_returns_201() throws Exception {
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Club admin own " + LocationsControllerIT.suffix(),
                                LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isCreated());
    }

    @Test
    void put_clubadmin_own_club_returns_200() throws Exception {
        String icao = LocationsControllerIT.uniqueIcao();
        String externalId = createUnder(CLUB_A, icao);
        mvc.perform(put("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.updatePayload(
                                "Renamed by club admin", icao))))
                .andExpect(status().isOk());
    }

    @Test
    void delete_clubadmin_own_club_returns_204() throws Exception {
        String externalId = createUnder(CLUB_A);
        mvc.perform(delete("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isNoContent());
    }

    @Test
    void put_clubadmin_other_club_returns_404() throws Exception {
        // CLUB_A creates a row; CLUB_B's admin tries to update it. The row is
        // invisible under CLUB_B's tenant scope, so the service throws
        // LocationNotFoundException → controller maps to 404. NOT 403 — the
        // tenant filter is structural, the row simply does not exist for B.
        String externalId = createUnder(CLUB_A);
        mvc.perform(put("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.updatePayload(
                                "Cross-tenant hijack", LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_clubadmin_other_club_returns_404() throws Exception {
        String externalId = createUnder(CLUB_A);
        mvc.perform(delete("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B)))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_clubadmin_other_club_returns_404() throws Exception {
        String externalId = createUnder(CLUB_A);
        mvc.perform(get("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B)))
                .andExpect(status().isNotFound());
    }

    // ----- FLIGHT_OPERATOR (read OK, write FORBIDDEN — not in the writer roster) -----

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

    // ----- OFFICE_USER (read OK, write FORBIDDEN — not in the writer roster) -----

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

    // ----- Cross-tenant isolation -----

    @Test
    void clubA_and_clubB_lists_are_disjoint() throws Exception {
        String aIcao = LocationsControllerIT.uniqueIcao();
        String bIcao = LocationsControllerIT.uniqueIcao();
        createUnder(CLUB_A, aIcao);
        createUnder(CLUB_B, bIcao);

        String aList = mvc.perform(get("/api/v1/locations")
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bList = mvc.perform(get("/api/v1/locations")
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(aList).as("A's list must contain A's ICAO").contains(aIcao);
        assertThat(aList).as("A's list must NOT contain B's ICAO").doesNotContain(bIcao);
        assertThat(bList).as("B's list must contain B's ICAO").contains(bIcao);
        assertThat(bList).as("B's list must NOT contain A's ICAO").doesNotContain(aIcao);
    }

    @Test
    void same_icao_in_two_different_clubs_succeeds_at_http_layer() throws Exception {
        String icao = LocationsControllerIT.uniqueIcao();
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Shared ICAO A", icao))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Shared ICAO B", icao))))
                .andExpect(status().isCreated());
    }

    // ----- helpers -----

    private static RequestPostProcessor role(String authority, String clubId) {
        return jwt()
                .jwt(t -> t.claim("clubId", clubId))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    private String createUnder(String clubId) throws Exception {
        return createUnder(clubId, LocationsControllerIT.uniqueIcao());
    }

    private String createUnder(String clubId, String icao) throws Exception {
        Map<String, Object> body = LocationsControllerIT.createPayload(
                "Authz fixture " + LocationsControllerIT.suffix(), icao);
        String responseBody = mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", clubId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(responseBody).get("id").asText();
    }

    private void cleanupPreviousRun() {
        jdbc.update("DELETE FROM inoutbound_point WHERE location_id IN ("
                        + "  SELECT id FROM location WHERE club_id IN (?::uuid, ?::uuid))",
                CLUB_A, CLUB_B);
        jdbc.update("DELETE FROM location WHERE club_id IN (?::uuid, ?::uuid)",
                CLUB_A, CLUB_B);
        jdbc.update("DELETE FROM club WHERE id IN (?::uuid, ?::uuid)", CLUB_A, CLUB_B);
    }

    private void seedClub(String id, String slug) {
        UUID countryId = jdbc.queryForObject("SELECT id FROM country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                """,
                id,
                NAME_PREFIX + slug,
                KEY_PREFIX + slug.charAt(0),
                countryId.toString(),
                clubStateId.toString(),
                NAME_PREFIX + slug);
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise payload", e);
        }
    }
}
