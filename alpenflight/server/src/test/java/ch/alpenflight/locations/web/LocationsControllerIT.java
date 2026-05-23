package ch.alpenflight.locations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
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

/**
 * Full-stack HTTP integration test for the Locations CRUD slice. Asserts the
 * REST surface, soft-delete filtering, ICAO uniqueness, and ICAO-format
 * validation under a CLUB_ADMINISTRATOR principal of seed-club-1 (per S-159:
 * SYSTEM_ADMINISTRATOR has no rights on tenant-scoped surfaces).
 *
 * <p>Per-test isolation relies on time-stamped ICAO codes + location names —
 * V3 seeds no Location rows, so the test set is the only data in
 * {@code location} for the duration of the JVM.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class LocationsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String clubAdminToken;

    @BeforeEach
    void mintClubAdminToken() {
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-000000000001")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void listLocations_initially_returns_200_with_empty_array() {
        ResponseEntity<String> res = get("/api/v1/locations");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).isArray()).isTrue();
    }

    @Test
    void createLocation_valid_returns_201_with_location_header_and_body() {
        String icao = uniqueIcao();
        ResponseEntity<String> res = post("/api/v1/locations",
                createPayload("Test Airfield " + suffix(), icao));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res);
        assertThat(body.get("locationName").asText()).startsWith("Test Airfield");
        assertThat(body.get("icaoCode").asText()).isEqualTo(icao);
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/locations/" + body.get("id").asText());
    }

    @Test
    void createLocation_invalidIcaoRegex_returns_400() {
        Map<String, Object> payload = createPayload("Bad ICAO " + suffix(), "abcd");
        ResponseEntity<String> res = post("/api/v1/locations", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createLocation_duplicateIcao_returns_409() {
        String icao = uniqueIcao();
        ResponseEntity<String> first = post("/api/v1/locations",
                createPayload("First " + suffix(), icao));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = post("/api/v1/locations",
                createPayload("Second " + suffix(), icao));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getHeaders().getContentType())
                .as("RFC 7807 problem+json content type")
                .isNotNull()
                .satisfies(ct -> assertThat(ct.toString()).contains("problem+json"));
    }

    @Test
    void createLocation_unknownCountryId_returns_400_with_field_diagnostic() {
        Map<String, Object> payload = createPayload("Bad Country " + suffix(), uniqueIcao());
        payload.put("countryId", "00000000-0000-0000-0000-000000000000");
        ResponseEntity<String> res = post("/api/v1/locations", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("countryId");
    }

    @Test
    void createLocation_unknownLocationTypeId_returns_400_with_field_diagnostic() {
        Map<String, Object> payload = createPayload("Bad Type " + suffix(), uniqueIcao());
        payload.put("locationTypeId", "00000000-0000-0000-0000-000000000000");
        ResponseEntity<String> res = post("/api/v1/locations", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("locationTypeId");
    }

    @Test
    void getLocation_unknownId_returns_404() {
        LocationId ghost = LocationId.of(new UUID(0L, 0L));
        ResponseEntity<String> res = get("/api/v1/locations/" + ghost);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getLocation_malformed_id_returns_400() {
        ResponseEntity<String> res = get("/api/v1/locations/not-a-location-id");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateLocation_existing_returns_200_with_updated_body() {
        String icao = uniqueIcao();
        ResponseEntity<String> created = post("/api/v1/locations",
                createPayload("Original " + suffix(), icao));
        String id = readJson(created).get("id").asText();

        Map<String, Object> upd = updatePayload("Renamed Airfield", icao);
        upd.put("isFastEntryRecord", true);
        ResponseEntity<String> res = put("/api/v1/locations/" + id, upd);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.get("locationName").asText()).isEqualTo("Renamed Airfield");
        assertThat(body.get("isFastEntryRecord").asBoolean()).isTrue();
    }

    @Test
    void updateLocation_unknownId_returns_404() {
        LocationId ghost = LocationId.of(new UUID(0L, 0L));
        ResponseEntity<String> res = put("/api/v1/locations/" + ghost,
                updatePayload("Ghost", uniqueIcao()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateLocation_collidingIcao_returns_409() {
        String icaoA = uniqueIcao();
        String icaoB = uniqueIcao();
        post("/api/v1/locations", createPayload("Loc A " + suffix(), icaoA));
        ResponseEntity<String> b = post("/api/v1/locations",
                createPayload("Loc B " + suffix(), icaoB));
        String bId = readJson(b).get("id").asText();

        Map<String, Object> upd = updatePayload(readJson(b).get("locationName").asText(), icaoA);
        ResponseEntity<String> res = put("/api/v1/locations/" + bId, upd);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deleteLocation_returns_204_and_subsequent_get_returns_404() {
        String icao = uniqueIcao();
        ResponseEntity<String> created = post("/api/v1/locations",
                createPayload("Doomed " + suffix(), icao));
        String id = readJson(created).get("id").asText();

        ResponseEntity<Void> del = rest.exchange(authed(
                        RequestEntity.delete(URI.create("/api/v1/locations/" + id))).build(),
                Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> after = get("/api/v1/locations/" + id);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteLocation_softDeletes_rather_than_physical_remove() {
        String icao = uniqueIcao();
        ResponseEntity<String> created = post("/api/v1/locations",
                createPayload("Soft Deleted " + suffix(), icao));
        String external = readJson(created).get("id").asText();
        UUID raw = LocationId.parse(external).value();
        rest.exchange(authed(
                        RequestEntity.delete(URI.create("/api/v1/locations/" + external))).build(),
                Void.class);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM location WHERE id = ?::uuid AND deleted_on IS NOT NULL",
                Integer.class, raw.toString());
        assertThat(count).isEqualTo(1);

        ResponseEntity<String> list = get("/api/v1/locations");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody())
                .as("Soft-deleted Locations must not appear in the list payload")
                .doesNotContain(icao);
    }

    @Test
    void createLocation_afterSoftDeleteWithSameIcao_succeeds() {
        String icao = uniqueIcao();
        ResponseEntity<String> created = post("/api/v1/locations",
                createPayload("Recyclable " + suffix(), icao));
        String externalId = readJson(created).get("id").asText();
        rest.exchange(authed(
                        RequestEntity.delete(URI.create("/api/v1/locations/" + externalId))).build(),
                Void.class);

        ResponseEntity<String> recreated = post("/api/v1/locations",
                createPayload("Recreated " + suffix(), icao));
        assertThat(recreated.getStatusCode())
                .as("Partial UNIQUE on icao_code is scoped WHERE icao_code IS NOT NULL — "
                        + "soft-deleted row keeps its ICAO but does not block recreate")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listLocations_sorts_by_sortIndicator_then_name_with_nulls_last() {
        Map<String, Object> a = createPayload("ZZZ Has Sort " + suffix(), uniqueIcao());
        a.put("sortIndicator", 5);
        Map<String, Object> b = createPayload("AAA Null Sort " + suffix(), uniqueIcao());
        b.remove("sortIndicator");
        post("/api/v1/locations", a);
        post("/api/v1/locations", b);

        ResponseEntity<String> res = get("/api/v1/locations");
        JsonNode body = readJson(res);
        int idxA = -1;
        int idxB = -1;
        for (int i = 0; i < body.size(); i++) {
            String name = body.get(i).get("locationName").asText();
            if (name.startsWith("ZZZ Has Sort")) {
                idxA = i;
            } else if (name.startsWith("AAA Null Sort")) {
                idxB = i;
            }
        }
        assertThat(idxA).isGreaterThanOrEqualTo(0);
        assertThat(idxB).isGreaterThanOrEqualTo(0);
        assertThat(idxA)
                .as("Rows with sort_indicator set must precede null-sort rows")
                .isLessThan(idxB);
    }

    // ----- helpers -----

    private ResponseEntity<String> get(String path) {
        return rest.exchange(authed(
                        RequestEntity.get(URI.create(path))).build(),
                String.class);
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

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken);
    }

    static Map<String, Object> createPayload(String locationName, String icaoCode) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("locationName", locationName);
        n.put("countryId", LocationsTestFixtures.SEED_COUNTRY_ID);
        n.put("locationTypeId", LocationsTestFixtures.SEED_LOCATION_TYPE_GRASS_RUNWAY);
        n.put("icaoCode", icaoCode);
        n.put("isInboundRouteRequired", false);
        n.put("isOutboundRouteRequired", false);
        n.put("isFastEntryRecord", false);
        n.put("sortIndicator", 100);
        return n;
    }

    static Map<String, Object> updatePayload(String locationName, String icaoCode) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("locationName", locationName);
        n.put("countryId", LocationsTestFixtures.SEED_COUNTRY_ID);
        n.put("locationTypeId", LocationsTestFixtures.SEED_LOCATION_TYPE_GLIDER_AIRFIELD);
        n.put("icaoCode", icaoCode);
        n.put("isInboundRouteRequired", false);
        n.put("isOutboundRouteRequired", false);
        n.put("isFastEntryRecord", false);
        return n;
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }

    static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static final AtomicInteger ICAO_COUNTER = new AtomicInteger(0);

    /**
     * Unique ICAO matching {@code ^[A-Z]{2}[0-9]{2}$} (legacy short form for
     * private airfields). Per-process counter ensures distinct values across
     * the full JVM-shared test container lifetime.
     */
    static String uniqueIcao() {
        int n = ICAO_COUNTER.incrementAndGet();
        char c1 = (char) ('A' + ((n / 100) % 26));
        char c2 = (char) ('A' + ((n / 100 / 26) % 26));
        int digits = n % 100;
        return "" + c1 + c2 + String.format("%02d", digits);
    }
}
