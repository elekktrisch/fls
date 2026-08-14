package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
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

@AutoConfigureMockMvc
class LocationsAuthorizationIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CLUB_A = "019e30c3-2c00-7001-8000-00000010ca01";
    private static final String CLUB_B = "019e30c3-2c00-7001-8000-00000010ca02";

    private static final String NAME_PREFIX = "IT_LA_";
    private static final String KEY_PREFIX = "IT_A_";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedTwoClubs() {
        cleanupPreviousRun();
        seedClub(CLUB_A, "alpha");
        seedClub(CLUB_B, "bravo");
    }

    @Test
    void anonymous_returns_401_on_read_and_write() throws Exception {
        mvc.perform(get("/api/v1/locations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                "Anon", LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} writing under own club returns {1}")
    @MethodSource("ownClubWriteMatrix")
    void own_club_write_status(String authority, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_" + authority, CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.createPayload(
                                authority + " " + LocationsControllerIT.suffix(),
                                LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().is(expectedStatus));
    }

    static Stream<Arguments> ownClubWriteMatrix() {
        return Stream.of(
                Arguments.of("SYSTEM_ADMINISTRATOR", 403),
                Arguments.of("CLUB_ADMINISTRATOR", 201),
                Arguments.of("FLIGHT_OPERATOR", 403),
                Arguments.of("OFFICE_USER", 403));
    }

    @Test
    void club_admin_full_crud_own_club() throws Exception {
        String icao = LocationsControllerIT.uniqueIcao();
        String createdId = createUnderClub(CLUB_A, "ROLE_CLUB_ADMINISTRATOR", icao);
        mvc.perform(get("/api/v1/locations/" + createdId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/locations/" + createdId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.updatePayload("Renamed", icao))))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/locations/" + createdId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isNoContent());
    }

    @Test
    void club_admin_cross_tenant_sees_404_not_403() throws Exception {
        String externalId = createUnderClub(CLUB_A, "ROLE_CLUB_ADMINISTRATOR",
                LocationsControllerIT.uniqueIcao());
        mvc.perform(get("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.updatePayload(
                                "Cross-tenant hijack", LocationsControllerIT.uniqueIcao()))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/locations/" + externalId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B)))
                .andExpect(status().isNotFound());
    }

    @Test
    void body_with_stray_clubId_is_rejected_400_by_jackson() throws Exception {
        Map<String, Object> body = LocationsControllerIT.createPayload(
                "No mass-assign " + LocationsControllerIT.suffix(),
                LocationsControllerIT.uniqueIcao());
        body.put("clubId", CLUB_B);
        mvc.perform(post("/api/v1/locations")
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lists_for_two_clubs_are_disjoint_and_same_icao_coexists() throws Exception {
        String aIcao = LocationsControllerIT.uniqueIcao();
        String bIcao = LocationsControllerIT.uniqueIcao();
        createUnderClub(CLUB_A, "ROLE_CLUB_ADMINISTRATOR", aIcao);
        createUnderClub(CLUB_B, "ROLE_CLUB_ADMINISTRATOR", bIcao);
        createUnderClub(CLUB_A, "ROLE_CLUB_ADMINISTRATOR", "SH99");
        createUnderClub(CLUB_B, "ROLE_CLUB_ADMINISTRATOR", "SH99");

        String aList = mvc.perform(get("/api/v1/locations")
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bList = mvc.perform(get("/api/v1/locations")
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(aList).contains(aIcao).doesNotContain(bIcao);
        assertThat(bList).contains(bIcao).doesNotContain(aIcao);
    }


    private static RequestPostProcessor role(String authority, String clubId) {
        return jwt()
                .jwt(t -> t.claim("clubId", clubId))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    private String createUnderClub(String clubId, String authority, String icao) throws Exception {
        Map<String, Object> body = LocationsControllerIT.createPayload(
                "Authz fixture " + LocationsControllerIT.suffix(), icao);
        String responseBody = mvc.perform(post("/api/v1/locations")
                        .with(role(authority, clubId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(responseBody).get("id").asText();
    }

    private void cleanupPreviousRun() {
        jdbc.update("DELETE FROM t_inoutbound_point WHERE location_id IN ("
                        + "  SELECT id FROM t_location WHERE club_id IN (?::uuid, ?::uuid))",
                CLUB_A, CLUB_B);
        jdbc.update("DELETE FROM t_location WHERE club_id IN (?::uuid, ?::uuid)",
                CLUB_A, CLUB_B);
        jdbc.update("DELETE FROM t_club WHERE id IN (?::uuid, ?::uuid)", CLUB_A, CLUB_B);
    }

    private void seedClub(String id, String slug) {
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
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
