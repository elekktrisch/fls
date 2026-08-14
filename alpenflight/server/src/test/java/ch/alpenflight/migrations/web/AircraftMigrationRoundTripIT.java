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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
class AircraftMigrationRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final int LEGACY_AIRCRAFT_TYPE_GLIDER = 1;
    private static final UUID SEED_AIRCRAFT_TYPE_GLIDER =
            UUID.fromString("019e2e15-2c00-7af9-8000-000000002af9");
    private static final int LEGACY_AIRCRAFT_STATE_OK = 1;
    private static final UUID SEED_AIRCRAFT_STATE_OK =
            UUID.fromString("019e2e15-2c00-7ee0-8000-000000002ee0");
    private static final int LEGACY_LOCATION_TYPE_GRASS = 2;
    private static final int LEGACY_UNIT_FEET = 2;
    private static final int LEGACY_COUNTER_UNIT_DECIMAL_HOURS = 2;
    private static final UUID SEED_COUNTER_UNIT_HOURS_DECIMAL =
            UUID.fromString("019e2e15-2c00-7b58-8000-000000001b58");

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
    private UUID legacyPersonId;

    @BeforeEach
    void seedActor() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        legacyCountryId = UUID.randomUUID();
        legacyPersonId = UUID.randomUUID();
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "ACM-" + tag;
        testClubSlug = "acm-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "acm-it-" + userSub, "Aircraft Migrate IT",
                "acm-" + userSub + "@example.com",
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
        jdbc.update("DELETE FROM t_aircraft_operating_counter WHERE aircraft_id IN "
                + "(SELECT id FROM t_aircraft WHERE managing_club_id IN (" + clubsByOwner + "))",
                userSub.toString());
        jdbc.update("DELETE FROM t_aircraft_aircraft_state WHERE aircraft_id IN "
                + "(SELECT id FROM t_aircraft WHERE managing_club_id IN (" + clubsByOwner + "))",
                userSub.toString());
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id IN (" + clubsByOwner + ")",
                userSub.toString());
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
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_member_state WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_club WHERE deployment_id IN "
                + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)", userSub.toString());
        jdbc.update("DELETE FROM t_deployment WHERE owner_keycloak_sub = ?::uuid", userSub.toString());
        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", userId.toString());
        jdbc.update("DELETE FROM t_person WHERE id = ?::uuid", legacyPersonId.toString());
    }

    @Test
    void aircraft_round_trips_resolving_club_type_person_homebase_and_nesting_children()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        String key = testClubKey;
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "ACM Club", testClubSlug, key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        UUID legacyAircraftId = UUID.randomUUID();
        UUID legacyHomebaseLocationId = UUID.randomUUID();
        UUID legacyOperatingCounterId = UUID.randomUUID();

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.PERSON, fullPortPolicy(),
                EntityType.LOCATION, fullPortPolicy(),
                EntityType.AIRCRAFT, fullPortPolicy(),
                EntityType.AIRCRAFT_AIRCRAFT_STATE, fullPortPolicy(),
                EntityType.AIRCRAFT_OPERATING_COUNTER, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        UUID homebaseReplicaId =
                Coercions.deriveFanOutId(legacyHomebaseLocationId, legacyClubId);

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("CLUB.ndjson",
                clubNdjson(legacyClubId, key, "ACM Club Legacy", "Addr"));
        tarEntries.put("legacy_id_map/PERSON.pgcopy",
                pgcopyMap(legacyPersonId, legacyPersonId));
        tarEntries.put("PERSON.ndjson",
                personNdjson(legacyPersonId, legacyCountryId, "Owner", "Aircraft"));
        tarEntries.put("LOCATION.ndjson",
                locationNdjson(legacyHomebaseLocationId, legacyClubId, legacyCountryId, "LSZH"));
        tarEntries.put("legacy_id_map/LOCATION.pgcopy", pgcopyMapFanOut(
                new FanOutMapRow(legacyHomebaseLocationId, legacyClubId, homebaseReplicaId)));
        tarEntries.put("AIRCRAFT.ndjson", aircraftNdjson(
                legacyAircraftId, legacyClubId, legacyPersonId, legacyHomebaseLocationId, "HB-3000"));
        tarEntries.put("legacy_id_map/AIRCRAFT.pgcopy",
                pgcopyMap(legacyAircraftId, legacyAircraftId));
        tarEntries.put("AIRCRAFT_AIRCRAFT_STATE.ndjson",
                aircraftStateNdjson(legacyAircraftId, "Annual inspection passed"));
        tarEntries.put("AIRCRAFT_OPERATING_COUNTER.ndjson",
                operatingCounterNdjson(legacyOperatingCounterId, legacyAircraftId, 360000L));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Aircraft Migrate IT Deployment",
                List.of(club), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("Aircraft migrate round-trip ingest failed; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        UUID newClub = clubIdsByKey(deploymentId).get(key);

        Map<String, Object> aircraft = jdbc.queryForMap(
                "SELECT id, managing_club_id, aircraft_type_id, aircraft_owner_person_id, "
                        + "homebase_id, immatriculation, flight_operating_counter_unit_type_id "
                        + "FROM t_aircraft WHERE id = ?::uuid",
                legacyAircraftId.toString());
        assertThat(UUID.fromString(aircraft.get("id").toString()))
                .as("AIRCRAFT is non-fan-out: legacy_guid preserved as id")
                .isEqualTo(legacyAircraftId);
        assertThat(UUID.fromString(aircraft.get("managing_club_id").toString()))
                .as("managing_club_id resolved to the real migrated club")
                .isEqualTo(newClub);
        assertThat(UUID.fromString(aircraft.get("aircraft_type_id").toString()))
                .as("aircraft_type_id resolved to the real V3 t_aircraft_type seed PK, "
                        + "not the synthetic new UUID(0, legacyIntId)")
                .isEqualTo(SEED_AIRCRAFT_TYPE_GLIDER);
        assertThat(UUID.fromString(aircraft.get("aircraft_owner_person_id").toString()))
                .as("aircraft_owner_person_id resolved to the migrated t_person")
                .isEqualTo(legacyPersonId);
        assertThat(UUID.fromString(aircraft.get("homebase_id").toString()))
                .as("homebase_id resolved to the migrating club's Location replica")
                .isEqualTo(homebaseReplicaId);
        assertThat(aircraft.get("immatriculation"))
                .as("immatriculation preserved verbatim")
                .isEqualTo("HB-3000");
        assertThat(UUID.fromString(aircraft.get("flight_operating_counter_unit_type_id").toString()))
                .as("flight_operating_counter_unit_type_id resolved to the real V25-seeded "
                        + "t_counter_unit_type HOURS_DECIMAL PK, not the synthetic "
                        + "new UUID(0, legacyIntId)")
                .isEqualTo(SEED_COUNTER_UNIT_HOURS_DECIMAL);

        Map<String, Object> stateRow = jdbc.queryForMap(
                "SELECT aircraft_id, aircraft_state_id, remarks FROM t_aircraft_aircraft_state "
                        + "WHERE aircraft_id = ?::uuid", legacyAircraftId.toString());
        assertThat(UUID.fromString(stateRow.get("aircraft_id").toString()))
                .as("state-history child nests under the migrated aircraft")
                .isEqualTo(legacyAircraftId);
        assertThat(UUID.fromString(stateRow.get("aircraft_state_id").toString()))
                .as("aircraft_state_id resolved to the real V3 t_aircraft_state seed PK")
                .isEqualTo(SEED_AIRCRAFT_STATE_OK);
        assertThat(stateRow.get("remarks")).isEqualTo("Annual inspection passed");

        Map<String, Object> counterRow = jdbc.queryForMap(
                "SELECT id, aircraft_id, flight_operating_counter_in_seconds "
                        + "FROM t_aircraft_operating_counter WHERE aircraft_id = ?::uuid",
                legacyAircraftId.toString());
        assertThat(UUID.fromString(counterRow.get("id").toString()))
                .as("operating-counter is non-fan-out: legacy_guid preserved as id")
                .isEqualTo(legacyOperatingCounterId);
        assertThat(UUID.fromString(counterRow.get("aircraft_id").toString()))
                .as("operating-counter child nests under the migrated aircraft")
                .isEqualTo(legacyAircraftId);
        assertThat(((Number) counterRow.get("flight_operating_counter_in_seconds")).longValue())
                .as("operating-counter reading carried verbatim")
                .isEqualTo(360000L);
    }

    private byte[] aircraftNdjson(UUID legacyAircraftId, UUID legacyClubId, UUID ownerPersonId,
                                  UUID homebaseLocationId, String immatriculation)
            throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyAircraftId.toString());
        row.put("managing_club_id", legacyClubId.toString());
        row.put("owner_club_id", legacyClubId.toString());
        row.put("aircraft_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_AIRCRAFT_TYPE_GLIDER));
        row.put("manufacturer_name", "Schleicher");
        row.put("aircraft_model", "ASK 21");
        row.put("immatriculation", immatriculation);
        row.put("competition_sign", "21");
        row.putNull("flarm_id");
        row.putNull("aircraft_serial_number");
        row.putNull("year_of_manufacture");
        row.putNull("noise_class");
        row.putNull("noise_level");
        row.putNull("mtom");
        row.put("nr_of_seats", 2);
        row.put("aircraft_owner_person_id", ownerPersonId.toString());
        row.put("flight_operating_counter_unit_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_COUNTER_UNIT_DECIMAL_HOURS));
        row.putNull("engine_operating_counter_unit_type_id");
        row.put("homebase_id", homebaseLocationId.toString());
        row.putNull("spot_link");
        row.put("is_towing_or_winch_required", false);
        row.put("is_towing_start_allowed", true);
        row.put("is_winch_start_allowed", true);
        row.put("is_towing_aircraft", false);
        row.put("is_fast_entry_record", false);
        row.putNull("comment");
        row.putNull("daec_index");
        String createdInstant = Instant.parse("2022-04-01T08:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private byte[] aircraftStateNdjson(UUID legacyAircraftId, String remarks) throws IOException {
        var row = JSON.createObjectNode();
        row.put("aircraft_id", legacyAircraftId.toString());
        row.put("aircraft_state_id",
                Coercions.legacyIntIdToUuidString(LEGACY_AIRCRAFT_STATE_OK));
        String validFrom = Instant.parse("2022-04-01T08:00:00Z").toString();
        row.put("valid_from", validFrom);
        row.putNull("valid_to");
        row.putNull("noticed_by_person_id");
        row.put("remarks", remarks);
        String createdInstant = Instant.parse("2022-04-01T08:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private byte[] operatingCounterNdjson(UUID legacyCounterId, UUID legacyAircraftId,
                                          long flightSeconds) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyCounterId.toString());
        row.put("aircraft_id", legacyAircraftId.toString());
        String atDateTime = Instant.parse("2024-05-01T10:00:00Z").toString();
        row.put("at_date_time", atDateTime);
        row.putNull("total_towed_glider_starts");
        row.putNull("total_winch_launch_starts");
        row.putNull("total_self_starts");
        row.put("flight_operating_counter_in_seconds", flightSeconds);
        row.putNull("engine_operating_counter_in_seconds");
        row.putNull("next_maintenance_at_flight_operating_counter_in_seconds");
        row.putNull("next_maintenance_at_engine_operating_counter_in_seconds");
        String createdInstant = Instant.parse("2024-05-01T10:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.put("created_by_user_id", actorUserId.toString());
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

    private byte[] personNdjson(UUID legacyPersonId, UUID legacyCountryGuid,
                                String lastname, String firstname) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyPersonId.toString());
        row.put("lastname", lastname);
        row.put("firstname", firstname);
        row.putNull("midname");
        row.putNull("company_name");
        row.putNull("address_line1");
        row.putNull("address_line2");
        row.putNull("zip");
        row.putNull("city");
        row.putNull("region");
        row.put("country_id", legacyCountryGuid.toString());
        row.putNull("private_phone");
        row.putNull("mobile_phone");
        row.putNull("business_phone");
        row.putNull("fax_number");
        row.putNull("email_private");
        row.putNull("email_business");
        row.put("prefer_mail_to_business_mail", false);
        row.putNull("birthday");
        row.put("has_motor_pilot_licence", false);
        row.put("has_tow_pilot_licence", false);
        row.put("has_glider_instructor_licence", false);
        row.put("has_glider_pilot_licence", true);
        row.put("has_glider_trainee_licence", false);
        row.put("has_glider_pax_licence", false);
        row.put("has_tmg_licence", false);
        row.put("has_winch_operator_licence", false);
        row.put("has_motor_instructor_licence", false);
        row.put("has_part_m_licence", false);
        row.putNull("licence_number");
        row.putNull("medical_class1_expire_date");
        row.putNull("medical_class2_expire_date");
        row.putNull("medical_lapl_expire_date");
        row.putNull("glider_instructor_licence_expire_date");
        row.putNull("motor_instructor_licence_expire_date");
        row.putNull("part_m_licence_expire_date");
        row.put("has_glider_towing_start_permission", false);
        row.put("has_glider_self_start_permission", false);
        row.put("has_glider_winch_start_permission", false);
        row.putNull("spot_link");
        row.put("receive_owned_aircraft_statistic_reports", false);
        row.put("enable_address", false);
        row.put("is_fast_entry_record", false);
        String createdInstant = Instant.parse("2019-09-01T00:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.putNull("created_by_user_id");
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

    private static byte[] ndjsonLine(ObjectNode row) throws IOException {
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
