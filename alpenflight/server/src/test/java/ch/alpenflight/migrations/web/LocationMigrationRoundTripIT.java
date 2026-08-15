package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.MockKeycloakDirectoryConfig;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
@Import({JwtTestFixture.class, MockKeycloakDirectoryConfig.class})
@Tag("slow")
class LocationMigrationRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final int LEGACY_LOCATION_TYPE_GRASS = 2;
    private static final UUID SEED_LOCATION_TYPE_GRASS =
            UUID.fromString("019e2e15-2c00-72c9-8000-0000000032c9");
    private static final int LEGACY_UNIT_FEET = 2;
    private static final UUID SEED_ELEVATION_UNIT_FEET =
            UUID.fromString("019e2e15-2c00-7771-8000-000000001771");
    private static final UUID SEED_LENGTH_UNIT_FEET =
            UUID.fromString("019e2e15-2c00-7389-8000-000000001389");

    private static final UUID LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC = new UUID(0L, 1L);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MigrationBundleCipher cipher;

    private UUID userId;
    private UUID userSub;
    private String verifiedToken;
    private String testClubKey;
    private String testClubSlug;
    private UUID legacyCountryId;
    private UUID actorUserId;

    @BeforeEach
    void seedMigrationPrincipalUserSoTheJitFirstLoginFilterResolvesIt() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        legacyCountryId = UUID.randomUUID();
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "LOC-" + tag;
        testClubSlug = "loc-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "loc-it-" + userSub, "Location IT",
                "loc-" + userSub + "@example.com",
                SEED_LANGUAGE_DE.toString(), userSub.toString());
        verifiedToken = jwts.mint(c -> c
                .subject(userSub.toString())
                .claim("email_verified", true));
    }

    @AfterEach
    void cleanup() {
        String clubsByOwner =
                "SELECT id FROM t_club WHERE deployment_id IN "
                        + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)";
        jdbc.update("DELETE FROM t_inoutbound_point WHERE location_id IN "
                + "(SELECT id FROM t_location WHERE club_id IN (" + clubsByOwner + "))",
                userSub.toString());
        jdbc.update("DELETE FROM t_location WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_user WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_migration_run WHERE upload_id IN "
                + "(SELECT id FROM t_migration_upload WHERE user_id = ?::uuid)", userId.toString());
        jdbc.update("DELETE FROM t_migration_upload WHERE user_id = ?::uuid", userId.toString());
        deletePerClubMasterdataSeededByProvisioning(clubsByOwner);
        jdbc.update("DELETE FROM t_club WHERE deployment_id IN "
                + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)", userSub.toString());
        jdbc.update("DELETE FROM t_deployment WHERE owner_keycloak_sub = ?::uuid", userSub.toString());
        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", userId.toString());
    }

    private void deletePerClubMasterdataSeededByProvisioning(String clubsByOwner) {
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_member_state WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
    }

    @Test
    void shared_location_fans_out_per_club_resolves_reference_fks_and_nests_child() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubIdA = UUID.randomUUID();
        UUID legacyClubIdB = UUID.randomUUID();
        String keyA = testClubKey + "A";
        String keyB = testClubKey + "B";
        BundleManifest.ClubDeclaration clubA = new BundleManifest.ClubDeclaration(
                legacyClubIdA, "Loc Club A", testClubSlug + "-a", keyA, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        BundleManifest.ClubDeclaration clubB = new BundleManifest.ClubDeclaration(
                legacyClubIdB, "Loc Club B", testClubSlug + "-b", keyB, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        UUID sharedLocationId = UUID.randomUUID();
        UUID childIopIdA = UUID.randomUUID();
        UUID childIopIdB = UUID.randomUUID();

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.LOCATION, fullPortPolicy(),
                EntityType.INOUTBOUND_POINT, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("CLUB.ndjson", concat(
                clubNdjson(legacyClubIdA, keyA, "Loc Club A Legacy", "Addr A"),
                clubNdjson(legacyClubIdB, keyB, "Loc Club B Legacy", "Addr B")));
        tarEntries.put("LOCATION.ndjson", concat(
                locationNdjson(sharedLocationId, legacyClubIdA, legacyCountryId, "LSZH"),
                locationNdjson(sharedLocationId, legacyClubIdB, legacyCountryId, "LSZH")));
        tarEntries.put("legacy_id_map/LOCATION.pgcopy", pgcopyMapFanOut(
                new FanOutMapRow(sharedLocationId, legacyClubIdA,
                        Coercions.deriveFanOutId(sharedLocationId, legacyClubIdA)),
                new FanOutMapRow(sharedLocationId, legacyClubIdB,
                        Coercions.deriveFanOutId(sharedLocationId, legacyClubIdB))));
        tarEntries.put("INOUTBOUND_POINT.ndjson", concat(
                inoutboundPointNdjson(childIopIdA, sharedLocationId, legacyClubIdA),
                inoutboundPointNdjson(childIopIdB, sharedLocationId, legacyClubIdB)));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Location Migrate IT Deployment",
                List.of(clubA, clubB), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("Location migrate round-trip ingest failed (the composite LOCATION "
                        + "id-map must be drained BEFORE INOUTBOUND_POINT.ndjson, else the "
                        + "child FK resolve fails closed with BUNDLE_CROSS_TENANT_FK_LEAK); "
                        + "body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        Map<String, UUID> clubIdByKey = clubIdsByKey(deploymentId);
        UUID newClubA = clubIdByKey.get(keyA);
        UUID newClubB = clubIdByKey.get(keyB);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, club_id, location_type_id, elevation_unit_type_id, "
                        + "runway_length_unit_type_id FROM t_location "
                        + "WHERE club_id IN (?::uuid, ?::uuid) ORDER BY club_id",
                newClubA.toString(), newClubB.toString());
        assertThat(rows)
                .as("shared legacy Location referenced by 2 clubs must fan out to 2 t_location rows")
                .hasSize(2);
        assertThat(rows.stream().map(r -> r.get("club_id").toString()).toList())
                .as("each fanned-out row carries its own club_id (@TenantId)")
                .containsExactlyInAnyOrder(newClubA.toString(), newClubB.toString());
        assertThat(rows.stream().map(r -> r.get("id").toString()).distinct().count())
                .as("the two replicas must have DISTINCT new ids (fan-out keying "
                        + "(legacy_guid, club_id) -> new_id)")
                .isEqualTo(2L);

        for (Map<String, Object> r : rows) {
            assertThat(UUID.fromString(r.get("location_type_id").toString()))
                    .as("location_type_id must resolve to the real V3 seed PK, not the synthetic")
                    .isEqualTo(SEED_LOCATION_TYPE_GRASS);
            assertThat(UUID.fromString(r.get("elevation_unit_type_id").toString()))
                    .as("elevation_unit_type_id must resolve to the real V22 seed PK")
                    .isEqualTo(SEED_ELEVATION_UNIT_FEET);
            assertThat(UUID.fromString(r.get("runway_length_unit_type_id").toString()))
                    .as("runway_length_unit_type_id must resolve to the real V22 seed PK")
                    .isEqualTo(SEED_LENGTH_UNIT_FEET);
        }

        Map<String, UUID> replicaIdByClub = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            replicaIdByClub.put(r.get("club_id").toString(),
                    UUID.fromString(r.get("id").toString()));
        }
        UUID replicaInClubA = replicaIdByClub.get(newClubA.toString());
        UUID replicaInClubB = replicaIdByClub.get(newClubB.toString());

        List<Map<String, Object>> iops = jdbc.queryForList(
                "SELECT iop.id, iop.location_id, loc.club_id "
                        + "FROM t_inoutbound_point iop "
                        + "JOIN t_location loc ON loc.id = iop.location_id "
                        + "WHERE loc.club_id IN (?::uuid, ?::uuid)",
                newClubA.toString(), newClubB.toString());
        assertThat(iops)
                .as("each fan-out club's child InOutboundPoint must attach to a "
                        + "fanned-out parent Location")
                .hasSize(2);

        Map<String, UUID> iopLocationIdByClub = new LinkedHashMap<>();
        for (Map<String, Object> iop : iops) {
            iopLocationIdByClub.put(iop.get("club_id").toString(),
                    UUID.fromString(iop.get("location_id").toString()));
        }
        assertThat(iopLocationIdByClub.get(newClubA.toString()))
                .as("club-aware FK: the club-A child must resolve to club A's OWN "
                        + "Location replica id, not club B's (composite (legacy_guid, "
                        + "club_id) lookup keyed on the child's own club)")
                .isEqualTo(replicaInClubA);
        assertThat(iopLocationIdByClub.get(newClubB.toString()))
                .as("club-aware FK: the club-B child must resolve to club B's OWN "
                        + "Location replica id, not club A's")
                .isEqualTo(replicaInClubB);
    }

    private byte[] clubNdjson(UUID legacyClubId, String clubKey, String clubname, String address)
            throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyClubId.toString());
        row.put("clubname", clubname);
        row.put("club_key", clubKey);
        row.put("address", address);
        row.putNull("zip");
        row.putNull("city");
        row.put("country_id", legacyCountryId.toString());
        row.putNull("phone");
        row.putNull("fax_number");
        row.putNull("email");
        row.putNull("web_page");
        row.putNull("contact");
        row.put("club_state_id", LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC.toString());
        row.putNull("send_aircraft_statistic_report_to");
        row.putNull("send_planning_day_info_mail_to");
        row.putNull("send_delivery_mail_export_to");
        row.putNull("send_trial_flight_registration_operator_email");
        row.putNull("send_passenger_flight_registration_operator_email");
        row.putNull("reply_to_email_address");
        row.put("run_delivery_creation_job", false);
        row.put("run_delivery_mail_export_job", false);
        row.putNull("last_person_synchronisation_on");
        row.putNull("last_delivery_synchronisation_on");
        row.putNull("last_article_synchronisation_on");
        row.put("is_club_member_number_readonly", false);
        String createdInstant = Instant.parse("2020-06-15T00:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.putNull("created_by_user_id");
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private byte[] locationNdjson(UUID legacyLocationId, UUID legacyClubId,
                                  UUID countryId, String icao) throws IOException {
        var row = JSON.createObjectNode();
        row.put("id", Coercions.deriveFanOutId(legacyLocationId, legacyClubId).toString());
        row.put("legacy_guid", legacyLocationId.toString());
        row.put("club_id", legacyClubId.toString());
        row.put("location_name", "Zurich");
        row.put("location_short_name", "ZRH");
        row.put("country_id", countryId.toString());
        row.put("location_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_LOCATION_TYPE_GRASS));
        row.put("icao_code", icao);
        row.put("latitude", "47.46");
        row.put("longitude", "8.55");
        row.put("elevation", 1416);
        row.put("elevation_unit_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_UNIT_FEET));
        row.put("runway_direction", "14/32");
        row.put("runway_length", 12139);
        row.put("runway_length_unit_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_UNIT_FEET));
        row.put("airport_frequency", "118.100");
        row.put("description", "Zurich Airport");
        row.put("sort_indicator", 10);
        row.put("is_inbound_route_required", false);
        row.put("is_outbound_route_required", true);
        row.put("is_fast_entry_record", false);
        String createdInstant = Instant.parse("2024-01-01T12:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private byte[] inoutboundPointNdjson(UUID legacyIopId, UUID legacyLocationId,
                                         UUID legacyClubId) throws IOException {
        var row = JSON.createObjectNode();
        row.put("id", Coercions.deriveFanOutId(legacyIopId, legacyClubId).toString());
        row.put("legacy_guid", legacyIopId.toString());
        row.put("location_id", legacyLocationId.toString());
        row.put("club_id", legacyClubId.toString());
        row.put("point_name", "07N");
        row.putNull("point_type");
        row.put("direction", "INBOUND");
        row.putNull("description");
        String createdInstant = Instant.parse("2024-01-01T12:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private Map<String, UUID> clubIdsByKey(UUID deploymentId) {
        Map<String, UUID> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, club_key FROM t_club WHERE deployment_id = ?::uuid",
                deploymentId.toString())) {
            byKey.put(r.get("club_key").toString(),
                    UUID.fromString(r.get("id").toString()));
        }
        return byKey;
    }

    private static byte[] ndjsonLine(com.fasterxml.jackson.databind.node.ObjectNode row)
            throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator gen = JSON.getFactory().createGenerator(sink)) {
            JSON.writeTree(gen, row);
        }
        sink.write('\n');
        return sink.toByteArray();
    }

    private static byte[] pgcopyMap(UUID legacyGuid, UUID newUuid) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            writer.write(legacyGuid, newUuid);
        }
        return sink.toByteArray();
    }

    private record FanOutMapRow(UUID legacyGuid, UUID legacyClubId, UUID newUuid) { }

    private static byte[] pgcopyMapFanOut(FanOutMapRow... mapRows) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            for (FanOutMapRow mapRow : mapRows) {
                writer.write(mapRow.legacyGuid(), mapRow.legacyClubId(), mapRow.newUuid());
            }
        }
        return sink.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private JsonNode mintHandshake() throws Exception {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/handshake"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(""),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(res.getBody());
    }

    private ResponseEntity<String> postBundle(UUID uploadId, byte[] body, String token) {
        return rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/" + uploadId + "/bundle"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(body),
                String.class);
    }

    private static EntityPolicy fullPortPolicy() {
        return new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                java.util.Set.of(),
                java.util.List.of());
    }

    private static EntityPolicy systemGlobalPolicy() {
        return new EntityPolicy(
                EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE,
                EntityPolicy.TombstonePolicy.SKIP_DELETED,
                java.util.Set.of(),
                java.util.List.of());
    }

    private static byte[] decodePem(String pem) {
        String body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
