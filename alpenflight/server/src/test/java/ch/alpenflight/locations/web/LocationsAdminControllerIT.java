package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SYSTEM_ADMINISTRATOR cross-tenant Locations management. Authz matrix
 * parameterised per CONVENTIONS.md §"Test pyramid"; the cross-tenant write
 * behaviour gets one happy-path IT proving the {@code Tenants.runAs}
 * wrapper drives Hibernate's {@code @TenantId} to the target club.
 */
@AutoConfigureMockMvc
class LocationsAdminControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Use the V5 seed club as A so the existing reference-data fixtures resolve;
    // CLUB_B is seeded fresh per test.
    private static final String CLUB_A = "019e30c3-2c00-7001-8000-000000000001";
    private static final String CLUB_B = "019e30c3-2c00-7001-8000-0000000000d2";

    private static final String NAME_PREFIX = "IT_LAC_";
    private static final String KEY_PREFIX = "IT_LC_";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedClubB() {
        cleanupPreviousRun();
        seedClub(CLUB_B, "delta");
    }

    @Test
    void sysadmin_can_create_in_target_club_even_with_other_clubId_claim() throws Exception {
        // JWT clubId = CLUB_A (seed). Path target = CLUB_B. The runAs wrapper
        // must elevate so the Hibernate insert lands with club_id = B.
        String icao = LocationsControllerIT.uniqueIcao();
        String created = mvc.perform(post("/api/v1/admin/locations/" + clubExternal(CLUB_B))
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Cross-tenant create " + LocationsControllerIT.suffix(), icao))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID rawId = ch.alpenflight.platform.id.LocationId.parse(
                MAPPER.readTree(created).get("id").asText()).value();
        UUID persistedClub = jdbc.queryForObject(
                "SELECT club_id FROM location WHERE id = ?::uuid", UUID.class, rawId.toString());
        assertThat(persistedClub.toString())
                .as("Tenants.runAs(B) must persist with club_id = B, not A")
                .isEqualTo(CLUB_B);
    }

    @Test
    void sysadmin_listLocations_returns_only_target_clubs_rows() throws Exception {
        // Seed one Location in each club via the admin surface (so we exercise it
        // from both directions).
        String aIcao = LocationsControllerIT.uniqueIcao();
        String bIcao = LocationsControllerIT.uniqueIcao();
        createUnder(CLUB_A, aIcao);
        createUnder(CLUB_B, bIcao);

        String listB = mvc.perform(get("/api/v1/admin/locations/" + clubExternal(CLUB_B))
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listB)
                .as("admin list for CLUB_B includes B's ICAO but not A's")
                .contains(bIcao)
                .doesNotContain(aIcao);
    }

    @Test
    void sysadmin_update_and_delete_in_target_club() throws Exception {
        String icao = LocationsControllerIT.uniqueIcao();
        String externalLocationId = createUnder(CLUB_B, icao);
        // Update
        mvc.perform(put("/api/v1/admin/locations/" + clubExternal(CLUB_B) + "/" + externalLocationId)
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.updatePayload("Cross-tenant rename", icao))))
                .andExpect(status().isOk());
        // Delete
        mvc.perform(delete("/api/v1/admin/locations/" + clubExternal(CLUB_B) + "/" + externalLocationId)
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest(name = "{0} cannot use the admin surface (403)")
    @MethodSource("forbiddenRoles")
    void non_sysadmin_roles_are_forbidden(String authority) throws Exception {
        mvc.perform(get("/api/v1/admin/locations/" + clubExternal(CLUB_B))
                        .with(role("ROLE_" + authority, CLUB_B)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/locations/" + clubExternal(CLUB_B))
                        .with(role("ROLE_" + authority, CLUB_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Forbidden", LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isForbidden());
    }

    static Stream<Arguments> forbiddenRoles() {
        return Stream.of(
                Arguments.of("CLUB_ADMINISTRATOR"),
                Arguments.of("FLIGHT_OPERATOR"),
                Arguments.of("OFFICE_USER"));
    }

    @Test
    void anonymous_returns_401() throws Exception {
        mvc.perform(get("/api/v1/admin/locations/" + clubExternal(CLUB_B)))
                .andExpect(status().isUnauthorized());
    }

    // ----- helpers -----

    private static String clubExternal(String clubUuid) {
        return ClubId.of(UUID.fromString(clubUuid)).toString();
    }

    private static RequestPostProcessor role(String authority, String clubId) {
        return jwt()
                .jwt(t -> t.claim("clubId", clubId))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    private String createUnder(String clubUuid, String icao) throws Exception {
        Map<String, Object> body = LocationsControllerIT.createPayload(
                "Admin fixture " + LocationsControllerIT.suffix(), icao);
        String responseBody = mvc.perform(post("/api/v1/admin/locations/" + clubExternal(clubUuid))
                        .with(role("ROLE_SYSTEM_ADMINISTRATOR", clubUuid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(responseBody).get("id").asText();
    }

    private void cleanupPreviousRun() {
        jdbc.update("DELETE FROM inoutbound_point WHERE location_id IN ("
                        + "  SELECT id FROM location WHERE club_id = ?::uuid)", CLUB_B);
        jdbc.update("DELETE FROM location WHERE club_id = ?::uuid", CLUB_B);
        jdbc.update("DELETE FROM club WHERE id = ?::uuid", CLUB_B);
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
