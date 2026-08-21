package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class MigratedLocationIcaoStaysEditableIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";

    private static final AtomicInteger LEGACY_ICAO_COUNTER = new AtomicInteger(0);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String clubAdminToken;

    @BeforeEach
    void mintClubAdminToken() {
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", CLUB_ID)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void savingAMigratedLocationWithoutTouchingItsLegacyIcao_keepsTheStoredValue() {
        String legacyIcao = nextLegacyIcaoThatDoesNotMatchThePattern();
        String id = insertMigratedLocationBypassingTheAggregate("Migrated Untouched", legacyIcao);

        ResponseEntity<String> res = put("/api/v1/locations/" + id,
                updatePayload("Migrated Untouched (renamed)", legacyIcao));

        assertThat(res.getStatusCode())
                .as("a migrated Location must stay editable when the operator does not change its "
                        + "legacy ICAO — response body: " + res.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.get("locationName").asText()).isEqualTo("Migrated Untouched (renamed)");
        assertThat(body.get("icaoCode").asText())
                .as("the stored legacy ICAO survives the save unchanged")
                .isEqualTo(legacyIcao);
        assertThat(readStoredIcao(id))
                .as("the database row keeps the legacy value the migration wrote")
                .isEqualTo(legacyIcao);
    }

    @Test
    void changingAMigratedLocationsIcaoToANonConformingValue_isRejected() {
        String legacyIcao = nextLegacyIcaoThatDoesNotMatchThePattern();
        String id = insertMigratedLocationBypassingTheAggregate("Migrated Bad Change", legacyIcao);

        ResponseEntity<String> res = put("/api/v1/locations/" + id,
                updatePayload("Migrated Bad Change", "abcd"));

        assertThat(res.getStatusCode())
                .as("a CHANGED ICAO must match the pattern, even on a migrated row")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readStoredIcao(id))
                .as("the rejected change leaves the stored legacy value in place")
                .isEqualTo(legacyIcao);
    }

    @Test
    void changingAMigratedLocationsIcaoToAConformingValue_isAccepted() {
        String legacyIcao = nextLegacyIcaoThatDoesNotMatchThePattern();
        String id = insertMigratedLocationBypassingTheAggregate("Migrated Good Change", legacyIcao);
        String conformingIcao = LocationsControllerIT.uniqueIcao();

        ResponseEntity<String> res = put("/api/v1/locations/" + id,
                updatePayload("Migrated Good Change", conformingIcao));

        assertThat(res.getStatusCode())
                .as("body: " + res.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("icaoCode").asText()).isEqualTo(conformingIcao);
        assertThat(readStoredIcao(id)).isEqualTo(conformingIcao);
    }

    @Test
    void creatingALocationWithANonConformingIcao_isRejected() {
        ResponseEntity<String> res = post("/api/v1/locations",
                LocationsControllerIT.createPayload(
                        "New Non Conforming " + LocationsControllerIT.suffix(),
                        nextLegacyIcaoThatDoesNotMatchThePattern()));

        assertThat(res.getStatusCode())
                .as("a NEW Location still requires a conforming ICAO — the retention rule covers "
                        + "an unchanged stored value only")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String insertMigratedLocationBypassingTheAggregate(String locationName,
                                                               String legacyIcaoCode) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO t_location (
                            id, club_id, location_name, country_id, location_type_id, icao_code,
                            is_inbound_route_required, is_outbound_route_required,
                            is_fast_entry_record)
                        VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, ?, false, false, false)
                        """,
                id.toString(),
                CLUB_ID,
                locationName + " " + LocationsControllerIT.suffix(),
                LocationsTestFixtures.SEED_COUNTRY_ID,
                LocationsTestFixtures.SEED_LOCATION_TYPE_GRASS_RUNWAY,
                legacyIcaoCode);
        return "loc-" + id;
    }

    private String readStoredIcao(String externalId) {
        return jdbc.queryForObject(
                "SELECT icao_code FROM t_location WHERE id = ?::uuid",
                String.class, externalId.replaceFirst("^loc-", ""));
    }

    private static String nextLegacyIcaoThatDoesNotMatchThePattern() {
        return "J0C" + LEGACY_ICAO_COUNTER.incrementAndGet();
    }

    private static Map<String, Object> updatePayload(String locationName, String icaoCode) {
        return LocationsControllerIT.updatePayload(locationName, icaoCode);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return rest.exchange(authed(
                        RequestEntity.post(URI.create(path))
                                .contentType(MediaType.APPLICATION_JSON))
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String path, Map<String, Object> body) {
        return rest.exchange(authed(
                        RequestEntity.put(URI.create(path))
                                .contentType(MediaType.APPLICATION_JSON))
                        .body(body),
                String.class);
    }

    private <T extends RequestEntity.BodyBuilder> T authed(T builder) {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken);
        return builder;
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}
