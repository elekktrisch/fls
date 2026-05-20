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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * Role-gate + tenant-isolation matrix for the Locations REST surface, slim
 * per CONVENTIONS.md §"Test pyramid". Each test proves one cross-layer
 * property; matrix coverage is parameterised. Domain-level rules
 * (ICAO regex, blank name) belong in {@code LocationDomainTest}; per-tenant
 * data isolation lives in {@code LocationsTenantIsolationIT}.
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
                // Writer roster — opens to CLUB_ADMIN (own club via @TenantId) +
                // SYSTEM_ADMIN (any club).
                Arguments.of("SYSTEM_ADMINISTRATOR", 201),
                Arguments.of("CLUB_ADMINISTRATOR", 201),
                // Read-only roster — 403 on every write verb (rebuked at the
                // @PreAuthorize gate, not by tenancy).
                Arguments.of("FLIGHT_OPERATOR", 403),
                Arguments.of("OFFICE_USER", 403));
    }

    @Test
    void club_admin_full_crud_own_club() throws Exception {
        String icao = LocationsControllerIT.uniqueIcao();
        String createdId = createUnderClub(CLUB_A, "ROLE_CLUB_ADMINISTRATOR", icao);
        // Read
        mvc.perform(get("/api/v1/locations/" + createdId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isOk());
        // Update
        mvc.perform(put("/api/v1/locations/" + createdId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(LocationsControllerIT.updatePayload("Renamed", icao))))
                .andExpect(status().isOk());
        // Delete
        mvc.perform(delete("/api/v1/locations/" + createdId)
                        .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_A)))
                .andExpect(status().isNoContent());
    }

    @Test
    void club_admin_cross_tenant_sees_404_not_403() throws Exception {
        // CLUB_A creates a row; CLUB_B's admin cannot see or mutate it —
        // Hibernate's @TenantId filter makes the row invisible under B's
        // scope, so the service throws LocationNotFoundException → 404.
        // 404 (not 403) is structural: the row simply doesn't exist for B.
        String externalId = createUnderClub(CLUB_A, "ROLE_SYSTEM_ADMINISTRATOR",
                LocationsControllerIT.uniqueIcao());
        for (var perform : java.util.List.<Runnable>of(
                () -> safe(() -> mvc.perform(get("/api/v1/locations/" + externalId)
                                .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B)))
                        .andExpect(status().isNotFound())),
                () -> safe(() -> mvc.perform(put("/api/v1/locations/" + externalId)
                                .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(toJson(LocationsControllerIT.updatePayload(
                                        "Cross-tenant hijack", LocationsControllerIT.uniqueIcao()))))
                        .andExpect(status().isNotFound())),
                () -> safe(() -> mvc.perform(delete("/api/v1/locations/" + externalId)
                                .with(role("ROLE_CLUB_ADMINISTRATOR", CLUB_B)))
                        .andExpect(status().isNotFound())))) {
            perform.run();
        }
    }

    @Test
    void lists_for_two_clubs_are_disjoint_and_same_icao_coexists() throws Exception {
        String aIcao = LocationsControllerIT.uniqueIcao();
        String bIcao = LocationsControllerIT.uniqueIcao();
        createUnderClub(CLUB_A, "ROLE_CLUB_ADMINISTRATOR", aIcao);
        createUnderClub(CLUB_B, "ROLE_CLUB_ADMINISTRATOR", bIcao);
        // Same ICAO across clubs coexists at the HTTP layer (proves the
        // per-club partial UNIQUE replaced the global one).
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

    // ----- helpers -----

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

    @SuppressWarnings("unused")
    private static JsonNode parse(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise payload", e);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static void safe(CheckedRunnable r) {
        try {
            r.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
