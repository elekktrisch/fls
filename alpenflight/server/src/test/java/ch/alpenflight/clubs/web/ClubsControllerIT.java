package ch.alpenflight.clubs.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Full-stack HTTP integration test for the Clubs CRUD slice. Asserts the
 * API surface, the {@code @PreAuthorize} happy-path under a sysadmin
 * principal, and the soft-delete filtering invariant.
 *
 * <p>Imports {@link JwtTestFixture} so the production filter chain runs
 * against synthesised RSA-signed tokens — every request carries a
 * {@code Bearer <sysadmin-jwt>} header so the role-gate predicate
 * {@code hasRole('SYSTEM_ADMINISTRATOR')} on the Clubs methods passes.
 * Role-matrix evidence (clubadmin / flight-operator + own-club / other-club
 * SpEL gates) lives in {@code ClubsAuthorizationTest}; this IT covers the
 * happy-path behavior of the underlying service + persistence stack.
 *
 * <p>Per-test isolation relies on time-stamped unique slugs / clubKeys —
 * the V5 seed row stays untouched.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class ClubsControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String sysadminToken;

    @org.junit.jupiter.api.BeforeEach
    void mintSysadminToken() {
        sysadminToken = jwts.mint(c -> c
                .claim("clubId", "019e30c3-2c00-7001-8000-000000000001")
                .claim("realm_access", Map.of("roles", List.of("SYSTEM_ADMINISTRATOR"))));
    }

    @Test
    void listClubs_returns_200_with_seeded_row() {
        ResponseEntity<String> res = get("/api/v1/clubs");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull().contains("seed-club-1");
    }

    @Test
    void createClub_valid_returns_201_with_location_and_body() {
        String slug = "create-" + suffix();
        ResponseEntity<String> res = post("/api/v1/clubs",
                createPayload("Mountain Soaring", slug, "MTN" + shortSuffix()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res);
        assertThat(body.get("name").asText()).isEqualTo("Mountain Soaring");
        assertThat(body.get("slug").asText()).isEqualTo(slug);
        assertThat(body.get("countryId").asText()).isEqualTo(SEED_COUNTRY_ID);
        assertThat(body.get("clubStateId").asText()).isEqualTo(SEED_CLUB_STATE_ID);
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/clubs/" + body.get("id").asText());
    }

    @Test
    void createClub_unknownCountryId_returns_400_with_field_diagnostic() {
        Map<String, Object> payload = createPayload(
                "Bad Country", "bad-country-" + suffix(), "BCY" + shortSuffix());
        payload.put("countryId", "00000000-0000-0000-0000-000000000000");
        ResponseEntity<String> res = post("/api/v1/clubs", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("countryId");
    }

    @Test
    void createClub_unknownClubStateId_returns_400_with_field_diagnostic() {
        Map<String, Object> payload = createPayload(
                "Bad State", "bad-state-" + suffix(), "BST" + shortSuffix());
        payload.put("clubStateId", "00000000-0000-0000-0000-000000000000");
        ResponseEntity<String> res = post("/api/v1/clubs", payload);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("clubStateId");
    }

    @Test
    void createClub_blankName_returns_400() {
        ResponseEntity<String> res = post("/api/v1/clubs",
                createPayload("", "blank-" + suffix(), "BNK" + shortSuffix()));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createClub_duplicateSlug_returns_409() {
        String slug = "dup-" + suffix();
        ResponseEntity<String> first = post("/api/v1/clubs",
                createPayload("Alps Gliding", slug, "ALP" + shortSuffix()));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = post("/api/v1/clubs",
                createPayload("Alps Gliding 2", slug, "ALP" + shortSuffix()));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createClub_duplicateClubKey_returns_409_labeled_on_clubKey_field() {
        // J-26 T-07: only ux_club_key can trip here (slugs are unique per
        // call) — before the DIVE discrimination this was mislabeled as a
        // bare slug 409 with no field diagnostic.
        String clubKey = "K" + shortSuffix();
        ResponseEntity<String> first = post("/api/v1/clubs",
                createPayload("Key Owner", "key-a-" + suffix(), clubKey));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = post("/api/v1/clubs",
                createPayload("Key Dup", "key-b-" + suffix(), clubKey));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody())
                .as("the 409 must carry the problem-detail field=clubKey discriminator")
                .isNotNull()
                .contains("\"field\":\"clubKey\"");
    }

    @Test
    void updateClub_existing_returns_200_with_updated_body() {
        String slug = "orig-" + suffix();
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("Original Name", slug, "ORG" + shortSuffix()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> res = put("/api/v1/clubs/" + id,
                updatePayload("Renamed Club", slug, true));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.get("name").asText()).isEqualTo("Renamed Club");
        assertThat(body.get("publicRegistrationEnabled").asBoolean()).isTrue();
    }

    @Test
    void updateClub_roundTrips_registration_operator_emails_canonicalized_and_optional() {
        String slug = "operator-" + suffix();
        String id = readJson(post("/api/v1/clubs",
                createPayload("Operator Club", slug, "OPR" + shortSuffix()))).get("id").asText();

        Map<String, Object> payload = updatePayload("Operator Club", slug, true);
        payload.put("discoveryFlightOperatorEmail", " Schnupper@club.example; ops@club.example ");
        payload.put("scenicFlightOperatorEmail", "mitflug@club.example");
        JsonNode updated = readJson(put("/api/v1/clubs/" + id, payload));
        assertThat(updated.get("discoveryFlightOperatorEmail").asText())
                .isEqualTo("schnupper@club.example,ops@club.example");
        assertThat(updated.get("scenicFlightOperatorEmail").asText())
                .isEqualTo("mitflug@club.example");

        // Blank clears the opt-in rather than failing — an unset organiser
        // address must never block a registration.
        Map<String, Object> cleared = updatePayload("Operator Club", slug, true);
        cleared.put("discoveryFlightOperatorEmail", "");
        JsonNode afterClear = readJson(put("/api/v1/clubs/" + id, cleared));
        assertThat(afterClear.hasNonNull("discoveryFlightOperatorEmail")).isFalse();
        assertThat(afterClear.hasNonNull("scenicFlightOperatorEmail")).isFalse();
        assertThat(afterClear.hasNonNull("discoveryFlightTypeId")).isFalse();
    }

    @Test
    void updateClub_malformed_operator_email_returns_400() {
        String slug = "badmail-" + suffix();
        String id = readJson(post("/api/v1/clubs",
                createPayload("Bad Mail Club", slug, "BML" + shortSuffix()))).get("id").asText();

        Map<String, Object> payload = updatePayload("Bad Mail Club", slug, false);
        payload.put("discoveryFlightOperatorEmail", "ops@club.example,not-an-email");
        ResponseEntity<String> res = put("/api/v1/clubs/" + id, payload);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("discoveryFlightOperatorEmail");
    }

    @Test
    void updateClub_unknown_discoveryFlightTypeId_returns_400_not_500() {
        String slug = "badftype-" + suffix();
        String id = readJson(post("/api/v1/clubs",
                createPayload("Bad FlightType Club", slug, "BFT" + shortSuffix()))).get("id").asText();

        Map<String, Object> payload = updatePayload("Bad FlightType Club", slug, false);
        payload.put("discoveryFlightTypeId", "ft-00000000-0000-0000-0000-000000000000");
        ResponseEntity<String> res = put("/api/v1/clubs/" + id, payload);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("discoveryFlightTypeId");
    }

    @Test
    void updateClub_roundTrips_the_homebase_and_clears_it_on_omission() {
        String slug = "homebase-" + suffix();
        String clubId = readJson(post("/api/v1/clubs",
                createPayload("Homebase Club", slug, "HMB" + shortSuffix()))).get("id").asText();
        String homebaseId = createLocation(clubId, "IT_CTL_Homebase");

        Map<String, Object> payload = updatePayload("Homebase Club", slug, false);
        payload.put("homebaseId", homebaseId);
        assertThat(readJson(put("/api/v1/clubs/" + clubId, payload)).get("homebaseId").asText())
                .isEqualTo(homebaseId);
        assertThat(readJson(get("/api/v1/clubs/" + clubId)).get("homebaseId").asText())
                .isEqualTo(homebaseId);

        // Full-replace: the omitted key clears the homebase, which is a valid
        // state — a club without one still accepts discovery registrations.
        JsonNode afterClear = readJson(
                put("/api/v1/clubs/" + clubId, updatePayload("Homebase Club", slug, false)));
        assertThat(afterClear.hasNonNull("homebaseId")).isFalse();
        assertThat(readJson(get("/api/v1/clubs/" + clubId)).hasNonNull("homebaseId")).isFalse();
    }

    @Test
    void updateClub_clubAdmin_cannot_point_its_homebase_at_another_clubs_location() {
        String slug = "ownhome-" + suffix();
        String clubId = readJson(post("/api/v1/clubs",
                createPayload("Own Home Club", slug, "OWN" + shortSuffix()))).get("id").asText();
        String ownLocationId = createLocation(clubId, "IT_CTL_Own");

        String neighbourSlug = "neighbour-" + suffix();
        String neighbourId = readJson(post("/api/v1/clubs",
                createPayload("Neighbour Club", neighbourSlug, "NBR" + shortSuffix())))
                .get("id").asText();
        String neighbourLocationId = createLocation(neighbourId, "IT_CTL_Neighbour");

        String adminToken = clubAdminToken(clubId);
        Map<String, Object> payload = updatePayload("Own Home Club", slug, false);
        payload.put("homebaseId", ownLocationId);
        assertThat(putAs(adminToken, "/api/v1/clubs/" + clubId, payload).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        payload.put("homebaseId", neighbourLocationId);
        ResponseEntity<String> refused = putAs(adminToken, "/api/v1/clubs/" + clubId, payload);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("homebaseId");

        // The refusal is not merely cosmetic: the stored value never moved.
        assertThat(readJson(get("/api/v1/clubs/" + clubId)).get("homebaseId").asText())
                .isEqualTo(ownLocationId);
    }

    @Test
    void updateClub_sysadmin_cannot_point_a_clubs_homebase_at_its_own_clubs_location() {
        // The sysadmin's own tenant is the seed club, so a homebase check run on
        // the CALLER's tenant would accept this and store a cross-tenant FK.
        String seedLocationId = createLocation(SEED_CLUB_PATH, "IT_CTL_SeedClubOwned");

        String slug = "sysadminhome-" + suffix();
        String clubId = readJson(post("/api/v1/clubs",
                createPayload("Sysadmin Home Club", slug, "SAH" + shortSuffix()))).get("id").asText();

        Map<String, Object> payload = updatePayload("Sysadmin Home Club", slug, false);
        payload.put("homebaseId", seedLocationId);
        ResponseEntity<String> refused = put("/api/v1/clubs/" + clubId, payload);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("homebaseId");
    }

    @Test
    void updateClub_unknownId_returns_404() {
        // Valid ClubId external form but no Club with that UUID exists.
        ClubId ghost = ClubId.of(new java.util.UUID(0L, 0L));
        ResponseEntity<String> res = put(
                "/api/v1/clubs/" + ghost,
                updatePayload("x", "ghost-" + suffix(), false));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getClub_malformed_id_returns_400() {
        // Not a clb--prefixed ClubId external form → conversion failure.
        ResponseEntity<String> res = get("/api/v1/clubs/00000000-0000-0000-0000-000000000000");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteClub_existing_returns_204_then_404_on_get() {
        String slug = "doomed-" + suffix();
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("Doomed Club", slug, "DMD" + shortSuffix()));
        String id = readJson(created).get("id").asText();

        ResponseEntity<Void> del = rest.exchange(authed(
                        RequestEntity.delete(URI.create("/api/v1/clubs/" + id))).build(),
                Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> after = get("/api/v1/clubs/" + id);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteClub_softDeletes_rather_than_physical_remove() {
        String slug = "soft-" + suffix();
        ResponseEntity<String> created = post("/api/v1/clubs",
                createPayload("Soft Deleted", slug, "SFT" + shortSuffix()));
        String externalId = readJson(created).get("id").asText();
        java.util.UUID rawId = ClubId.parse(externalId).value();
        rest.exchange(authed(
                        RequestEntity.delete(URI.create("/api/v1/clubs/" + externalId))).build(),
                Void.class);

        // Row must still exist in the DB with deleted_on stamped — soft, not hard.
        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_club WHERE id = ?::uuid AND deleted_on IS NOT NULL",
                Integer.class, rawId.toString());
        assertThat(rowCount)
                .as("Soft-delete must leave the row in place with deleted_on stamped")
                .isEqualTo(1);

        // List endpoint excludes soft-deleted rows.
        ResponseEntity<String> list = get("/api/v1/clubs");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).doesNotContain(slug);
    }

    // ----- helpers -----

    /**
     * Creates a Location inside {@code clubExternalId} through the production
     * endpoint. Locations are {@code @TenantId}-scoped with no club id on the
     * path, so the owning club is decided by the principal the request carries.
     */
    private String createLocation(String clubExternalId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locationName", name + "_" + shortSuffix());
        body.put("countryId", SEED_COUNTRY_ID);
        body.put("locationTypeId", anyLocationTypeId());
        body.put("isInboundRouteRequired", false);
        body.put("isOutboundRouteRequired", false);
        body.put("isFastEntryRecord", false);
        ResponseEntity<String> created = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/locations"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken(clubExternalId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                String.class);
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        return readJson(created).get("id").asText();
    }

    private String anyLocationTypeId() {
        return readJson(get("/api/v1/location-types")).get(0).get("id").asText();
    }

    private String clubAdminToken(String clubExternalId) {
        return jwts.mint(c -> c
                .claim("clubId", ClubId.parse(clubExternalId).value().toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private ResponseEntity<String> putAs(String token, String path, Map<String, Object> body) {
        return rest.exchange(
                RequestEntity.put(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                String.class);
    }

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
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken);
        return builder;
    }

    private RequestEntity.HeadersBuilder<?> authed(RequestEntity.HeadersBuilder<?> builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + sysadminToken);
    }

    private static final String SEED_COUNTRY_ID = ClubsTestFixtures.SEED_COUNTRY_ID;
    private static final String SEED_CLUB_STATE_ID = ClubsTestFixtures.SEED_CLUB_STATE_ID;
    private static final String SEED_CLUB_PATH = "clb-019e30c3-2c00-7001-8000-000000000001";

    private static Map<String, Object> createPayload(String name, String slug, String clubKey) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("slug", slug);
        n.put("clubKey", clubKey);
        n.put("publicRegistrationEnabled", false);
        n.put("countryId", SEED_COUNTRY_ID);
        n.put("clubStateId", SEED_CLUB_STATE_ID);
        return n;
    }

    private static Map<String, Object> updatePayload(String name, String slug, boolean publicReg) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("slug", slug);
        n.put("publicRegistrationEnabled", publicReg);
        n.put("countryId", SEED_COUNTRY_ID);
        n.put("clubStateId", SEED_CLUB_STATE_ID);
        return n;
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response body: " + res.getBody(), e);
        }
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String shortSuffix() {
        String s = Long.toString(System.nanoTime(), 36).toUpperCase(java.util.Locale.ROOT);
        return s.length() > 6 ? s.substring(s.length() - 6) : s;
    }
}
