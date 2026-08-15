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
import java.util.Locale;
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
class FlightMigrationRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final int LEGACY_AIRCRAFT_TYPE_GLIDER = 1;
    private static final int LEGACY_AIRCRAFT_TYPE_TOW = 2;
    private static final int LEGACY_AIRCRAFT_TYPE_MOTOR = 4;
    private static final int LEGACY_AIRFRAME_TYPE_ANY_SEED_RESOLVES = LEGACY_AIRCRAFT_TYPE_GLIDER;
    private static final int LEGACY_FLIGHT_AIRCRAFT_TYPE_GLIDER = 1;
    private static final int LEGACY_FLIGHT_AIRCRAFT_TYPE_TOW = 2;
    private static final int LEGACY_FLIGHT_AIRCRAFT_TYPE_MOTOR = 4;
    private static final String NOT_TOWED_EMPTY_GUID_COLLAPSED_BY_PRODUCER = null;
    private static final int LEGACY_LOCATION_TYPE_GRASS = 2;
    private static final int LEGACY_UNIT_FEET = 2;

    private static final int LEGACY_START_TYPE_AEROTOW = 1;
    private static final UUID SEED_START_TYPE_AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");

    private static final int LEGACY_PROCESS_STATE_VALID = 30;
    private static final UUID SEED_PROCESS_STATE_VALID =
            UUID.fromString("019e2e15-2c00-7a9a-8000-000000003a9a");
    private static final int LEGACY_PROCESS_STATE_LOCKED = 40;
    private static final UUID SEED_PROCESS_STATE_LOCKED =
            UUID.fromString("019e2e15-2c00-7a9b-8000-000000003a9b");

    private static final int LEGACY_FCBT_PILOT_PAYS_ALL = 1;
    private static final UUID SEED_FCBT_PILOT_PAYS_ALL =
            UUID.fromString("019e2e15-2c00-7268-8000-000000004268");

    private static final int LEGACY_CREW_TYPE_PILOT = 1;
    private static final UUID SEED_CREW_TYPE_PILOT =
            UUID.fromString("019e2e15-2c00-76b0-8000-0000000036b0");
    private static final int LEGACY_CREW_TYPE_CO_PILOT = 2;
    private static final UUID SEED_CREW_TYPE_CO_PILOT =
            UUID.fromString("019e2e15-2c00-76b1-8000-0000000036b1");

    private static final int LEGACY_AIR_STATE_NEW = 0;
    private static final int LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN = 5;
    private static final int LEGACY_AIR_STATE_LANDED = 20;

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
    private UUID legacyPilotPersonId;
    private UUID legacyCoPilotPersonId;
    private String gliderImmat;
    private String towImmat;
    private String motorImmat;

    @BeforeEach
    void seedActor() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        legacyCountryId = UUID.randomUUID();
        legacyPilotPersonId = UUID.randomUUID();
        legacyCoPilotPersonId = UUID.randomUUID();
        String perRunNamespaceForGloballyUniqueKeysAndImmats = userSub.toString().substring(0, 5);
        testClubKey = "FLM-" + perRunNamespaceForGloballyUniqueKeysAndImmats;
        testClubSlug = "flm-" + perRunNamespaceForGloballyUniqueKeysAndImmats;
        gliderImmat = ("HB-G" + perRunNamespaceForGloballyUniqueKeysAndImmats).toUpperCase(Locale.ROOT);
        towImmat = ("HB-T" + perRunNamespaceForGloballyUniqueKeysAndImmats).toUpperCase(Locale.ROOT);
        motorImmat = ("HB-M" + perRunNamespaceForGloballyUniqueKeysAndImmats).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "flm-it-" + userSub, "Flight Migrate IT",
                "flm-" + userSub + "@example.com",
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
        jdbc.update("DELETE FROM t_person WHERE id = ?::uuid", legacyCoPilotPersonId.toString());
    }

    @Test
    void flight_round_trips_tow_pair_crew_lossy_airstate_and_locked_at() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        String key = testClubKey;
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "FLM Club", testClubSlug, key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        UUID legacyGliderAircraftId = UUID.randomUUID();
        UUID legacyTowAircraftId = UUID.randomUUID();
        UUID legacyMotorAircraftId = UUID.randomUUID();
        UUID legacyLocationId = UUID.randomUUID();
        UUID legacyFlightTypeId = UUID.randomUUID();

        UUID legacyGliderFlightId = UUID.randomUUID();
        UUID legacyTowFlightId = UUID.randomUUID();
        UUID legacyMotorFlightId = UUID.randomUUID();
        UUID legacyGliderPilotCrewId = UUID.randomUUID();
        UUID legacyGliderCoPilotCrewId = UUID.randomUUID();

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

        UUID locationReplicaId = Coercions.deriveFanOutId(legacyLocationId, legacyClubId);

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("legacy_id_map/START_TYPE.pgcopy",
                pgcopyMap(new UUID(0L, LEGACY_START_TYPE_AEROTOW), SEED_START_TYPE_AEROTOW));
        tarEntries.put("CLUB.ndjson",
                clubNdjson(legacyClubId, key, "FLM Club Legacy", "Addr"));
        tarEntries.put("legacy_id_map/PERSON.pgcopy", pgcopyMap2(
                new MapRow(legacyPilotPersonId, legacyPilotPersonId),
                new MapRow(legacyCoPilotPersonId, legacyCoPilotPersonId)));
        tarEntries.put("PERSON.ndjson", concat(
                personNdjson(legacyPilotPersonId, legacyCountryId, "Pilot", "Glider"),
                personNdjson(legacyCoPilotPersonId, legacyCountryId, "CoPilot", "Glider")));
        tarEntries.put("LOCATION.ndjson",
                locationNdjson(legacyLocationId, legacyClubId, legacyCountryId, "LSZH"));
        tarEntries.put("legacy_id_map/LOCATION.pgcopy", pgcopyMapFanOut(
                new FanOutMapRow(legacyLocationId, legacyClubId, locationReplicaId)));
        tarEntries.put("FLIGHT_TYPE.ndjson",
                flightTypeNdjson(legacyFlightTypeId, legacyClubId, "Schulung"));
        tarEntries.put("legacy_id_map/FLIGHT_TYPE.pgcopy",
                pgcopyMap(legacyFlightTypeId, legacyFlightTypeId));
        tarEntries.put("AIRCRAFT.ndjson", concat(
                aircraftNdjson(legacyGliderAircraftId, legacyClubId, gliderImmat,
                        LEGACY_AIRCRAFT_TYPE_GLIDER),
                aircraftNdjson(legacyTowAircraftId, legacyClubId, towImmat,
                        LEGACY_AIRCRAFT_TYPE_TOW),
                aircraftNdjson(legacyMotorAircraftId, legacyClubId, motorImmat,
                        LEGACY_AIRCRAFT_TYPE_MOTOR)));
        tarEntries.put("legacy_id_map/AIRCRAFT.pgcopy", pgcopyMap2(
                new MapRow(legacyGliderAircraftId, legacyGliderAircraftId),
                new MapRow(legacyTowAircraftId, legacyTowAircraftId),
                new MapRow(legacyMotorAircraftId, legacyMotorAircraftId)));
        tarEntries.put("FLIGHT.ndjson", concat(
                flightNdjson(legacyGliderFlightId, legacyClubId, legacyGliderAircraftId,
                        legacyLocationId, legacyFlightTypeId, legacyTowFlightId.toString(),
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_GLIDER, LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN,
                        LEGACY_PROCESS_STATE_LOCKED, "2024-06-01"),
                flightNdjson(legacyTowFlightId, legacyClubId, legacyTowAircraftId,
                        legacyLocationId, legacyFlightTypeId,
                        NOT_TOWED_EMPTY_GUID_COLLAPSED_BY_PRODUCER,
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_TOW, LEGACY_AIR_STATE_LANDED,
                        LEGACY_PROCESS_STATE_VALID, "2024-06-01"),
                flightNdjson(legacyMotorFlightId, legacyClubId, legacyMotorAircraftId,
                        legacyLocationId, legacyFlightTypeId,
                        NOT_TOWED_EMPTY_GUID_COLLAPSED_BY_PRODUCER,
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_MOTOR, LEGACY_AIR_STATE_NEW,
                        LEGACY_PROCESS_STATE_VALID, "2024-06-02")));
        tarEntries.put("legacy_id_map/FLIGHT.pgcopy", pgcopyMap2(
                new MapRow(legacyTowFlightId, legacyTowFlightId),
                new MapRow(legacyGliderFlightId, legacyGliderFlightId),
                new MapRow(legacyMotorFlightId, legacyMotorFlightId)));
        tarEntries.put("FLIGHT_CREW.ndjson", concat(
                flightCrewNdjson(legacyGliderPilotCrewId, legacyGliderFlightId,
                        legacyPilotPersonId, LEGACY_CREW_TYPE_PILOT),
                flightCrewNdjson(legacyGliderCoPilotCrewId, legacyGliderFlightId,
                        legacyCoPilotPersonId, LEGACY_CREW_TYPE_CO_PILOT)));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Flight Migrate IT Deployment",
                List.of(club), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("Flight migrate round-trip ingest failed; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        UUID newClub = clubIdsByKey(deploymentId).get(key);

        Map<String, Object> glider = jdbc.queryForMap(
                "SELECT id, operating_club_id, aircraft_id, start_location_id, "
                        + "ldg_location_id, flight_type_id, start_type_id, tow_flight_id, "
                        + "process_state_id, flight_cost_balance_type_id, "
                        + "flight_plan_opened_on, flight_date, created_on, locked_at, "
                        + "flight_aircraft_type_id "
                        + "FROM t_flight WHERE id = ?::uuid", legacyGliderFlightId.toString());
        assertThat(UUID.fromString(glider.get("id").toString()))
                .as("FLIGHT is non-fan-out: legacy_guid preserved as id")
                .isEqualTo(legacyGliderFlightId);
        assertThat(UUID.fromString(glider.get("operating_club_id").toString()))
                .as("operating_club_id resolved from legacy OwnerId to the migrated club")
                .isEqualTo(newClub);
        assertThat(UUID.fromString(glider.get("aircraft_id").toString()))
                .as("aircraft_id resolved cross-tenant to the migrated glider")
                .isEqualTo(legacyGliderAircraftId);
        assertThat(UUID.fromString(glider.get("start_location_id").toString()))
                .as("start_location_id resolved to the operating club's Location replica")
                .isEqualTo(locationReplicaId);
        assertThat(UUID.fromString(glider.get("ldg_location_id").toString()))
                .as("ldg_location_id resolved to the operating club's Location replica")
                .isEqualTo(locationReplicaId);
        assertThat(UUID.fromString(glider.get("flight_type_id").toString()))
                .as("flight_type_id resolved to the migrated per-club FlightType")
                .isEqualTo(legacyFlightTypeId);
        assertThat(UUID.fromString(glider.get("start_type_id").toString()))
                .as("start_type_id resolved to the real V2 t_start_type AEROTOW seed PK")
                .isEqualTo(SEED_START_TYPE_AEROTOW);
        assertThat(UUID.fromString(glider.get("tow_flight_id").toString()))
                .as("tow self-FK resolved to the migrated tow flight row via the "
                        + "S-141 two-pass UPDATE (glider INSERTed before its tow)")
                .isEqualTo(legacyTowFlightId);
        Map<String, Object> linkedTow = jdbc.queryForMap(
                "SELECT id, flight_aircraft_type_id FROM t_flight WHERE id = ?::uuid",
                glider.get("tow_flight_id").toString());
        assertThat(((Number) linkedTow.get("flight_aircraft_type_id")).shortValue())
                .as("the glider's tow_flight_id resolves to a real migrated TOW flight")
                .isEqualTo((short) LEGACY_FLIGHT_AIRCRAFT_TYPE_TOW);
        assertThat(UUID.fromString(glider.get("process_state_id").toString()))
                .as("process_state_id resolved to the real V3 LOCKED seed PK")
                .isEqualTo(SEED_PROCESS_STATE_LOCKED);
        assertThat(UUID.fromString(glider.get("flight_cost_balance_type_id").toString()))
                .as("flight_cost_balance_type_id resolved to the real V3 seed PK")
                .isEqualTo(SEED_FCBT_PILOT_PAYS_ALL);
        assertThat(glider.get("flight_plan_opened_on"))
                .as("FlightPlanOpen(5): the ModifiedOn timestamp survives V13 (S-060)")
                .isNotNull();
        assertThat(glider.get("flight_date").toString())
                .as("flight_date carried")
                .isEqualTo("2024-06-01");
        assertThat(glider.get("created_on")).as("created_on carried").isNotNull();
        assertThat(glider.get("locked_at"))
                .as("locked_at set for a migrated Locked(40) flight (= ModifiedOn proxy)")
                .isNotNull();

        List<Map<String, Object>> crew = jdbc.queryForList(
                "SELECT person_id, flight_crew_type_id FROM t_flight_crew "
                        + "WHERE flight_id = ?::uuid ORDER BY flight_crew_type_id",
                legacyGliderFlightId.toString());
        assertThat(crew).as("both crew rows nest under the glider flight").hasSize(2);
        assertThat(UUID.fromString(crew.get(0).get("person_id").toString()))
                .as("pilot person_id resolved cross-tenant to the migrated Person")
                .isEqualTo(legacyPilotPersonId);
        assertThat(UUID.fromString(crew.get(0).get("flight_crew_type_id").toString()))
                .as("PILOT_OR_STUDENT crew type -> the real V3 seed PK")
                .isEqualTo(SEED_CREW_TYPE_PILOT);
        assertThat(UUID.fromString(crew.get(1).get("person_id").toString()))
                .isEqualTo(legacyCoPilotPersonId);
        assertThat(UUID.fromString(crew.get(1).get("flight_crew_type_id").toString()))
                .as("CO_PILOT crew type -> the real V3 seed PK")
                .isEqualTo(SEED_CREW_TYPE_CO_PILOT);

        Map<String, Object> tow = jdbc.queryForMap(
                "SELECT tow_flight_id, process_state_id, flight_plan_opened_on, locked_at "
                        + "FROM t_flight WHERE id = ?::uuid", legacyTowFlightId.toString());
        assertThat(tow.get("tow_flight_id")).as("tow flight is not itself towed").isNull();
        assertThat(UUID.fromString(tow.get("process_state_id").toString()))
                .isEqualTo(SEED_PROCESS_STATE_VALID);
        assertThat(tow.get("flight_plan_opened_on"))
                .as("LANDED(20) air-state is NOT migrated — only FlightPlanOpen(5) survives")
                .isNull();
        assertThat(tow.get("locked_at"))
                .as("a still-Valid flight has no locked_at (bill gate never fires on import)")
                .isNull();

        Map<String, Object> motor = jdbc.queryForMap(
                "SELECT tow_flight_id, flight_aircraft_type_id FROM t_flight WHERE id = ?::uuid",
                legacyMotorFlightId.toString());
        assertThat(motor.get("tow_flight_id"))
                .as("a non-towed flight lands with tow_flight_id null (the producer already "
                        + "collapsed the legacy empty-guid 00000000-… sentinel)")
                .isNull();
        assertThat(((Number) motor.get("flight_aircraft_type_id")).shortValue())
                .as("MOTOR sparse-enum (4) passed through verbatim")
                .isEqualTo((short) LEGACY_FLIGHT_AIRCRAFT_TYPE_MOTOR);

        Integer reportRows = jdbc.queryForObject(
                "SELECT count(*) FROM t_flight_report_row WHERE operating_club_id = ?::uuid",
                Integer.class, newClub.toString());
        assertThat(reportRows)
                .as("rebuild-after-ingest projects one report row per migrated flight")
                .isEqualTo(3);
        Map<String, Object> gliderReportRow = jdbc.queryForMap(
                "SELECT immatriculation, pilot_name, second_crew_name, flight_code, "
                        + "start_location_name, tow_flight_id, tow_immatriculation "
                        + "FROM t_flight_report_row WHERE flight_id = ?::uuid",
                legacyGliderFlightId.toString());
        assertThat(gliderReportRow.get("immatriculation")).isEqualTo(gliderImmat);
        assertThat(gliderReportRow.get("pilot_name"))
                .as("decoration picked the migrated PILOT crew person ('Lastname Firstname')")
                .isEqualTo("Pilot Glider");
        assertThat(gliderReportRow.get("second_crew_name"))
                .as("CO_PILOT decorates as second crew")
                .isEqualTo("CoPilot Glider");
        assertThat(gliderReportRow.get("flight_code"))
                .as("flight_code copied from the migrated per-club FlightType")
                .isEqualTo("SCH");
        assertThat(gliderReportRow.get("start_location_name"))
                .as("the operating club's fan-out Location replica decorates by name")
                .isEqualTo("Zurich");
        assertThat(UUID.fromString(gliderReportRow.get("tow_flight_id").toString()))
                .as("tow block present: the S-141 two-pass tow link reached the read model")
                .isEqualTo(legacyTowFlightId);
        assertThat(gliderReportRow.get("tow_immatriculation")).isEqualTo(towImmat);
    }


    private byte[] flightNdjson(UUID legacyFlightId, UUID legacyClubId, UUID aircraftId,
                                UUID locationId, UUID flightTypeId, String towFlightId,
                                int flightAircraftType, int legacyAirStateId,
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
        if (legacyAirStateId == LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN) {
            row.put("flight_plan_opened_on", Instant.parse(flightDate + "T07:30:00Z").toString());
        } else {
            row.putNull("flight_plan_opened_on");
        }
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
                                  String immatriculation,
                                  int legacyAirframeTypeDecidingIsTowingAircraft)
            throws IOException {
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
        row.put("is_towing_aircraft",
                legacyAirframeTypeDecidingIsTowingAircraft == LEGACY_AIRCRAFT_TYPE_TOW);
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

    private record MapRow(UUID legacyGuid, UUID newUuid) { }

    private static byte[] pgcopyMap2(MapRow... rows) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            for (MapRow row : rows) {
                writer.write(row.legacyGuid(), row.newUuid());
            }
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
