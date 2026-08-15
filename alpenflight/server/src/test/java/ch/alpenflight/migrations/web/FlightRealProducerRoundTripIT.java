package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migration.tool.BundleWriter;
import ch.alpenflight.migration.tool.EntityStreamResult;
import ch.alpenflight.migration.tool.LegacyJdbcReader;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class FlightRealProducerRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final int LEGACY_AIRCRAFT_TYPE_GLIDER = 1;
    private static final int LEGACY_AIRFRAME_TYPE_ANY_SEED_RESOLVES = LEGACY_AIRCRAFT_TYPE_GLIDER;
    private static final int LEGACY_FLIGHT_AIRCRAFT_TYPE_GLIDER = 1;
    private static final int LEGACY_FLIGHT_AIRCRAFT_TYPE_TOW = 2;
    private static final String NOT_TOWED_EMPTY_GUID_COLLAPSED_BY_PRODUCER = null;
    private static final LegacyJdbcReader NO_LEGACY_JDBC_READER = null;
    private static final int LEGACY_LOCATION_TYPE_GRASS = 2;
    private static final int LEGACY_UNIT_FEET = 2;

    private static final int LEGACY_START_TYPE_AEROTOW = 1;
    private static final UUID SEED_START_TYPE_AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");
    private static final int LEGACY_PROCESS_STATE_VALID = 30;
    private static final int LEGACY_PROCESS_STATE_LOCKED = 40;
    private static final UUID SEED_PROCESS_STATE_LOCKED =
            UUID.fromString("019e2e15-2c00-7a9b-8000-000000003a9b");
    private static final int LEGACY_FCBT_PILOT_PAYS_ALL = 1;
    private static final int LEGACY_CREW_TYPE_PILOT = 1;
    private static final UUID SEED_CREW_TYPE_PILOT =
            UUID.fromString("019e2e15-2c00-76b0-8000-0000000036b0");

    private static final UUID LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC = new UUID(0L, 1L);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MigrationBundleCipher cipher;

    @TempDir Path workDir;

    private UUID userId;
    private UUID userSub;
    private String verifiedToken;
    private String testClubKey;
    private String testClubSlug;
    private UUID legacyCountryId;
    private UUID actorUserId;
    private UUID legacyPilotPersonId;
    private String gliderImmat;
    private String towImmat;

    @BeforeEach
    void seedActor() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        legacyCountryId = UUID.randomUUID();
        legacyPilotPersonId = UUID.randomUUID();
        String perRunNamespaceForGloballyUniqueKeysAndImmats = userSub.toString().substring(0, 5);
        testClubKey = "FRP-" + perRunNamespaceForGloballyUniqueKeysAndImmats;
        testClubSlug = "frp-" + perRunNamespaceForGloballyUniqueKeysAndImmats;
        gliderImmat = ("HB-G" + perRunNamespaceForGloballyUniqueKeysAndImmats).toUpperCase(Locale.ROOT);
        towImmat = ("HB-T" + perRunNamespaceForGloballyUniqueKeysAndImmats).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "frp-it-" + userSub, "Flight Real-Producer IT",
                "frp-" + userSub + "@example.com",
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
        jdbc.update("DELETE FROM t_flight_crew WHERE flight_id IN "
                + "(SELECT id FROM t_flight WHERE operating_club_id IN (" + clubsByOwner + "))",
                userSub.toString());
        jdbc.update("DELETE FROM t_flight WHERE operating_club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_aircraft WHERE managing_club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id IN (" + clubsByOwner + ")",
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
        jdbc.update("DELETE FROM t_member_state WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_club WHERE deployment_id IN "
                + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)", userSub.toString());
        jdbc.update("DELETE FROM t_deployment WHERE owner_keycloak_sub = ?::uuid", userSub.toString());
        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", userId.toString());
        jdbc.update("DELETE FROM t_person WHERE id = ?::uuid", legacyPilotPersonId.toString());
    }

    @Test
    void real_producer_orders_pgcopy_before_ndjson_so_flight_tow_and_crew_resolve()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        String key = testClubKey;
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "FRP Club", testClubSlug, key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        UUID legacyGliderAircraftId = UUID.randomUUID();
        UUID legacyTowAircraftId = UUID.randomUUID();
        UUID legacyLocationId = UUID.randomUUID();
        UUID legacyFlightTypeId = UUID.randomUUID();
        UUID legacyGliderFlightId = UUID.randomUUID();
        UUID legacyTowFlightId = UUID.randomUUID();
        UUID legacyCrewId = UUID.randomUUID();
        UUID locationReplicaId = Coercions.deriveFanOutId(legacyLocationId, legacyClubId);

        Map<EntityType, EntityPolicy> entityPolicies = new LinkedHashMap<>();
        entityPolicies.put(EntityType.COUNTRY, systemGlobalPolicy());
        entityPolicies.put(EntityType.CLUB_STATE, systemGlobalPolicy());
        entityPolicies.put(EntityType.START_TYPE, systemGlobalPolicy());
        entityPolicies.put(EntityType.CLUB, fullPortPolicy());
        entityPolicies.put(EntityType.PERSON, fullPortPolicy());
        entityPolicies.put(EntityType.LOCATION, fullPortPolicy());
        entityPolicies.put(EntityType.FLIGHT_TYPE, fullPortPolicy());
        entityPolicies.put(EntityType.AIRCRAFT, fullPortPolicy());
        entityPolicies.put(EntityType.FLIGHT, fullPortPolicy());
        entityPolicies.put(EntityType.FLIGHT_CREW, fullPortPolicy());

        EntityStreamResult clubStream = ndjsonStream(EntityType.CLUB,
                clubNdjson(legacyClubId, key, "FRP Club Legacy", "Addr"), 1);
        EntityStreamResult personStream = ndjsonStream(EntityType.PERSON,
                personNdjson(legacyPilotPersonId, legacyCountryId, "Pilot", "Glider"), 1);
        EntityStreamResult locationStream = ndjsonStream(EntityType.LOCATION,
                locationNdjson(legacyLocationId, legacyClubId, legacyCountryId, "LSZH"), 1);
        EntityStreamResult flightTypeStream = ndjsonStream(EntityType.FLIGHT_TYPE,
                flightTypeNdjson(legacyFlightTypeId, legacyClubId, "Schulung"), 1);
        EntityStreamResult aircraftStream = ndjsonStream(EntityType.AIRCRAFT,
                concat(
                        aircraftNdjson(legacyGliderAircraftId, legacyClubId, gliderImmat, false),
                        aircraftNdjson(legacyTowAircraftId, legacyClubId, towImmat, true)), 2);
        EntityStreamResult flightStream = ndjsonStream(EntityType.FLIGHT, concat(
                flightNdjson(legacyGliderFlightId, legacyClubId, legacyGliderAircraftId,
                        legacyLocationId, legacyFlightTypeId, legacyTowFlightId.toString(),
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_GLIDER,
                        LEGACY_PROCESS_STATE_LOCKED, "2024-06-01"),
                flightNdjson(legacyTowFlightId, legacyClubId, legacyTowAircraftId,
                        legacyLocationId, legacyFlightTypeId,
                        NOT_TOWED_EMPTY_GUID_COLLAPSED_BY_PRODUCER,
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_TOW,
                        LEGACY_PROCESS_STATE_VALID, "2024-06-01")), 2);
        EntityStreamResult crewStream = ndjsonStream(EntityType.FLIGHT_CREW,
                flightCrewNdjson(legacyCrewId, legacyGliderFlightId, legacyPilotPersonId,
                        LEGACY_CREW_TYPE_PILOT), 1);

        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                "Flight Real-Producer IT Deployment",
                List.of(club),
                null,
                entityPolicies,
                unmappedReasonFor(entityPolicies));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);

        Path producerTarGz = workDir.resolve("flight-real-producer-bundle.tar.gz");
        BundleWriter realProducer = new BundleWriter(NO_LEGACY_JDBC_READER, workDir, false);
        realProducer.assembleTarGz(manifestBytes,
                List.of(clubStream, personStream, locationStream, flightTypeStream,
                        aircraftStream, flightStream, crewStream),
                producerTarGz);

        Map<String, byte[]> systemGlobalMaps = new LinkedHashMap<>();
        systemGlobalMaps.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        systemGlobalMaps.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        systemGlobalMaps.put("legacy_id_map/START_TYPE.pgcopy",
                pgcopyMap(new UUID(0L, LEGACY_START_TYPE_AEROTOW), SEED_START_TYPE_AEROTOW));
        byte[] tarGzPlaintext = spliceAfterManifest(
                Files.readAllBytes(producerTarGz), systemGlobalMaps);

        byte[] bundle = MigrationBundleTestFactory.encryptTarGzPlaintext(
                cipher, uploadId, publicKeyDer, tarGzPlaintext);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("real-producer FLIGHT bundle ingest failed (would be 500 "
                        + "BUNDLE_CROSS_TENANT_FK_LEAK if assembleTarGz regressed to "
                        + "NDJSON-before-pgcopy and the tow self-FK / crew FK could not "
                        + "resolve); body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        UUID newClub = clubIdsByKey(deploymentId).get(key);

        Map<String, Object> glider = jdbc.queryForMap(
                "SELECT operating_club_id, aircraft_id, start_location_id, tow_flight_id, "
                        + "process_state_id, locked_at FROM t_flight WHERE id = ?::uuid",
                legacyGliderFlightId.toString());
        assertThat(UUID.fromString(glider.get("operating_club_id").toString()))
                .as("operating_club_id resolved to the migrated club through the real producer")
                .isEqualTo(newClub);
        assertThat(UUID.fromString(glider.get("aircraft_id").toString()))
                .isEqualTo(legacyGliderAircraftId);
        assertThat(UUID.fromString(glider.get("start_location_id").toString()))
                .as("start_location_id resolved to the operating club's Location replica")
                .isEqualTo(locationReplicaId);
        assertThat(UUID.fromString(glider.get("tow_flight_id").toString()))
                .as("tow self-FK resolved through the real producer ordering "
                        + "(glider emitted BEFORE its tow — the S-141 two-pass UPDATE "
                        + "links them after both rows exist)")
                .isEqualTo(legacyTowFlightId);
        Map<String, Object> linkedTow = jdbc.queryForMap(
                "SELECT id, flight_aircraft_type_id FROM t_flight WHERE id = ?::uuid",
                glider.get("tow_flight_id").toString());
        assertThat(UUID.fromString(linkedTow.get("id").toString()))
                .as("the glider's tow_flight_id resolves to a real migrated tow row")
                .isEqualTo(legacyTowFlightId);
        assertThat(((Number) linkedTow.get("flight_aircraft_type_id")).shortValue())
                .as("the linked row is a TOW flight (flight_aircraft_type_id=2)")
                .isEqualTo((short) LEGACY_FLIGHT_AIRCRAFT_TYPE_TOW);
        assertThat(UUID.fromString(glider.get("process_state_id").toString()))
                .isEqualTo(SEED_PROCESS_STATE_LOCKED);
        assertThat(glider.get("locked_at"))
                .as("locked_at set for the migrated Locked flight through the real producer")
                .isNotNull();

        Map<String, Object> crew = jdbc.queryForMap(
                "SELECT person_id, flight_crew_type_id FROM t_flight_crew "
                        + "WHERE flight_id = ?::uuid", legacyGliderFlightId.toString());
        assertThat(UUID.fromString(crew.get("person_id").toString()))
                .as("crew person_id resolved cross-tenant through the real producer")
                .isEqualTo(legacyPilotPersonId);
        assertThat(UUID.fromString(crew.get("flight_crew_type_id").toString()))
                .as("flight_crew_type_id resolved to the real V3 seed PK")
                .isEqualTo(SEED_CREW_TYPE_PILOT);
    }


    private byte[] flightNdjson(UUID legacyFlightId, UUID legacyClubId, UUID aircraftId,
                                UUID locationId, UUID flightTypeId, String towFlightId,
                                int flightAircraftType,
                                int legacyProcessStateId, String flightDate)
            throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyFlightId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        row.put("aircraft_id", aircraftId.toString());
        row.put("flight_date", flightDate);
        row.put("start_date_time", Instant.parse(flightDate + "T08:00:00Z").toString());
        row.put("ldg_date_time", Instant.parse(flightDate + "T10:00:00Z").toString());
        row.putNull("block_start_date_time");
        row.putNull("block_end_date_time");
        row.put("start_location_id", locationId.toString());
        row.put("ldg_location_id", locationId.toString());
        row.put("start_runway", "14");
        row.put("ldg_runway", "32");
        row.putNull("outbound_route");
        row.putNull("inbound_route");
        row.put("flight_type_id", flightTypeId.toString());
        row.put("is_solo_flight", false);
        row.put("start_type_id", new UUID(0L, LEGACY_START_TYPE_AEROTOW).toString());
        if (towFlightId == null) {
            row.putNull("tow_flight_id");
        } else {
            row.put("tow_flight_id", towFlightId);
        }
        row.put("nr_of_ldgs", 1);
        row.put("nr_of_ldgs_on_start_location", 1);
        row.put("no_start_time_information", false);
        row.put("no_ldg_time_information", false);
        row.putNull("flight_plan_opened_on");
        row.put("process_state_id", new UUID(0L, legacyProcessStateId).toString());
        row.put("flight_aircraft_type_id", flightAircraftType);
        row.putNull("engine_start_operating_counter_in_seconds");
        row.putNull("engine_end_operating_counter_in_seconds");
        row.putNull("comment");
        row.putNull("incident_comment");
        row.putNull("validation_errors");
        row.putNull("coupon_number");
        row.put("flight_cost_balance_type_id", new UUID(0L, LEGACY_FCBT_PILOT_PAYS_ALL).toString());
        row.putNull("delivery_created_on");
        row.putNull("validated_on");
        row.putNull("nr_of_passengers");
        row.putNull("start_position");
        row.putNull("flight_report_sent_on");
        row.put("created_on", Instant.parse(flightDate + "T06:00:00Z").toString());
        row.put("created_by_user_id", actorUserId.toString());
        String modifiedOn = Instant.parse(flightDate + "T12:00:00Z").toString();
        row.put("modified_on", modifiedOn);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        if (legacyProcessStateId >= LEGACY_PROCESS_STATE_LOCKED) {
            row.put("locked_at", modifiedOn);
        } else {
            row.putNull("locked_at");
        }
        return ndjsonLine(row);
    }

    private byte[] flightCrewNdjson(UUID legacyCrewId, UUID legacyFlightId,
                                    UUID personId, int legacyCrewType) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyCrewId.toString());
        row.put("flight_id", legacyFlightId.toString());
        row.put("person_id", personId.toString());
        row.put("flight_crew_type_id", new UUID(0L, legacyCrewType).toString());
        row.putNull("begin_flight_datetime");
        row.putNull("end_flight_datetime");
        row.putNull("begin_instruction_datetime");
        row.putNull("end_instruction_datetime");
        row.put("nr_of_ldgs", 1);
        row.put("nr_of_starts", 1);
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private byte[] flightTypeNdjson(UUID legacyFlightTypeId, UUID legacyClubId, String name)
            throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyFlightTypeId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        row.put("flight_type_name", name);
        row.put("flight_code", "SCH");
        row.put("instructor_required", false);
        row.put("observer_pilot_or_instructor_required", false);
        row.put("is_check_flight", false);
        row.put("is_passenger_flight", false);
        row.put("is_solo_flight", false);
        row.put("is_for_glider_flights", true);
        row.put("is_for_tow_flights", false);
        row.put("is_for_motor_flights", false);
        row.put("is_flight_cost_balance_selectable", true);
        row.put("is_coupon_number_required", false);
        row.put("is_for_aircraft_reservation_type", false);
        row.putNull("min_nr_of_aircraft_seats_required");
        String createdInstant = Instant.parse("2020-06-15T00:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private byte[] aircraftNdjson(UUID legacyAircraftId, UUID legacyClubId,
                                  String immatriculation, boolean towing) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyAircraftId.toString());
        row.put("managing_club_id", legacyClubId.toString());
        row.put("owner_club_id", legacyClubId.toString());
        row.put("aircraft_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_AIRFRAME_TYPE_ANY_SEED_RESOLVES));
        row.put("manufacturer_name", "Schleicher");
        row.put("aircraft_model", "ASK 21");
        row.put("immatriculation", immatriculation);
        row.putNull("competition_sign");
        row.putNull("flarm_id");
        row.putNull("aircraft_serial_number");
        row.putNull("year_of_manufacture");
        row.putNull("noise_class");
        row.putNull("noise_level");
        row.putNull("mtom");
        row.put("nr_of_seats", 2);
        row.putNull("aircraft_owner_person_id");
        row.putNull("flight_operating_counter_unit_type_id");
        row.putNull("engine_operating_counter_unit_type_id");
        row.putNull("homebase_id");
        row.putNull("spot_link");
        row.put("is_towing_or_winch_required", false);
        row.put("is_towing_start_allowed", true);
        row.put("is_winch_start_allowed", true);
        row.put("is_towing_aircraft", towing);
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

    private EntityStreamResult ndjsonStream(EntityType type, byte[] payload, long rows)
            throws IOException {
        Path file = Files.createTempFile(workDir, type.name() + "-", ".ndjson");
        Files.write(file, payload);
        return new EntityStreamResult(type, file, rows, "sha-not-asserted");
    }

    private static byte[] spliceAfterManifest(byte[] tarGz, Map<String, byte[]> afterManifest)
            throws IOException {
        Map<String, byte[]> original = new LinkedHashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GZIPInputStream(new java.io.ByteArrayInputStream(tarGz)))) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                original.put(e.getName(), tar.readAllBytes());
            }
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(sink);
             TarArchiveOutputStream out = new TarArchiveOutputStream(gzip)) {
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> e : original.entrySet()) {
                writeTar(out, e.getKey(), e.getValue());
                if (e.getKey().equals("manifest.json")) {
                    for (Map.Entry<String, byte[]> sg : afterManifest.entrySet()) {
                        writeTar(out, sg.getKey(), sg.getValue());
                    }
                }
            }
            out.finish();
        }
        return sink.toByteArray();
    }

    private static void writeTar(TarArchiveOutputStream out, String name, byte[] body)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(body.length);
        out.putArchiveEntry(entry);
        out.write(body);
        out.closeArchiveEntry();
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

    private static Map<EntityType, String> unmappedReasonFor(
            Map<EntityType, EntityPolicy> entityPolicies) {
        Map<EntityType, String> unmapped = new EnumMap<>(EntityType.class);
        for (EntityType type : EntityType.values()) {
            if (!entityPolicies.containsKey(type)) {
                unmapped.put(type, "TEST_OMITTED");
            }
        }
        return unmapped;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            sink.write(part, 0, part.length);
        }
        return sink.toByteArray();
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
