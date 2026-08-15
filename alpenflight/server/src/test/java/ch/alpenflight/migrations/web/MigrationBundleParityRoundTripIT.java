package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.MockKeycloakDirectoryConfig;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
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
class MigrationBundleParityRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final UUID LEGACY_LANGUAGE_DE_SYNTHETIC = new UUID(0L, 7L);
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

    @BeforeEach
    void seedUser() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        legacyCountryId = UUID.randomUUID();
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "IT-" + tag;
        testClubSlug = "parity-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "parity-it-" + userSub, "Parity IT",
                "parity-" + userSub + "@example.com",
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
        jdbc.update("DELETE FROM t_aircraft_reservation WHERE operating_club_id IN ("
                + clubsByOwner + ")", userSub.toString());
        jdbc.update("DELETE FROM t_aircraft_reservation_type WHERE operating_club_id IN ("
                + clubsByOwner + ")", userSub.toString());
        jdbc.update("DELETE FROM t_aircraft WHERE immatriculation = 'HB-3999'");
        jdbc.update("DELETE FROM t_flight_type WHERE flight_type_name = 'Reservation Type Flight'");
        jdbc.update("DELETE FROM t_location WHERE location_name = 'Reservation Field'");
        jdbc.update("DELETE FROM t_person WHERE lastname IN ('Pilot', 'Crew') "
                + "AND firstname IN ('Reservation', 'Second')");
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?", userSub.toString());
        jdbc.update("DELETE FROM t_user WHERE club_id IN "
                + "(SELECT id FROM t_club WHERE deployment_id IN "
                + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid))",
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
    }

    @Test
    void user_full_port_ndjson_ingests_with_fk_rewriting_against_seeded_id_maps() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        UUID legacyUserId = UUID.randomUUID();
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "Parity Club", testClubSlug, testClubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.USER, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.LANGUAGE, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/LANGUAGE.pgcopy",
                pgcopyMap(LEGACY_LANGUAGE_DE_SYNTHETIC, SEED_LANGUAGE_DE));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("USER.ndjson",
                userNdjson(legacyUserId, legacyClubId, LEGACY_LANGUAGE_DE_SYNTHETIC));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Parity IT Deployment",
                List.of(club), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);

        assertThat(res.getStatusCode())
                .as("parity round-trip ingest failed; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        assertThat(body.get("clubIds").size()).isEqualTo(1);
        UUID newClubId = UUID.fromString(body.get("clubIds").get(0).asText());

        Map<String, Object> ingestedUser = jdbc.queryForMap(
                "SELECT id, club_id, language_id, username FROM t_user WHERE id = ?::uuid",
                legacyUserId.toString());
        assertThat(UUID.fromString(ingestedUser.get("id").toString()))
                .as("legacy_guid wire-format column must land on t_user.id")
                .isEqualTo(legacyUserId);
        assertThat(UUID.fromString(ingestedUser.get("club_id").toString()))
                .as("club_id FK must be rewritten via legacy_id_map_club to the "
                        + "provisioning-minted Club id")
                .isEqualTo(newClubId);
        assertThat(UUID.fromString(ingestedUser.get("language_id").toString()))
                .as("language_id SYSTEM_GLOBAL FK must be rewritten via "
                        + "legacy_id_map_language to the V2 seed UUID")
                .isEqualTo(SEED_LANGUAGE_DE);
        assertThat(ingestedUser.get("username")).isEqualTo("parity-user-" + legacyUserId);

        Map<String, Object> deployment = jdbc.queryForMap(
                "SELECT name FROM t_deployment WHERE id = ?::uuid", deploymentId.toString());
        assertThat(deployment.get("name")).isEqualTo("Parity IT Deployment");
        Map<String, Object> provisionedClub = jdbc.queryForMap(
                "SELECT clubname, slug, club_key FROM t_club WHERE id = ?::uuid",
                newClubId.toString());
        assertThat(provisionedClub.get("clubname")).isEqualTo("Parity Club");
        assertThat(provisionedClub.get("slug")).isEqualTo(testClubSlug);
        assertThat(provisionedClub.get("club_key")).isEqualTo(testClubKey);
    }

    @Test
    void reservation_and_type_round_trip_with_off_convention_fk_columns_resolved()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "Reservation Club", testClubSlug, testClubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        UUID legacyAircraftId = UUID.randomUUID();
        UUID legacyPilotPersonId = UUID.randomUUID();
        UUID legacySecondCrewPersonId = UUID.randomUUID();
        UUID legacyLocationId = UUID.randomUUID();
        UUID legacyFlightTypeId = UUID.randomUUID();
        UUID legacyReservationTypeId = UUID.randomUUID();
        UUID legacyReservationId = UUID.randomUUID();

        UUID newAircraftId = legacyAircraftId;
        UUID newPilotPersonId = legacyPilotPersonId;
        UUID newSecondCrewPersonId = legacySecondCrewPersonId;
        UUID newFlightTypeId = legacyFlightTypeId;
        UUID newReservationTypeId = legacyReservationTypeId;
        UUID newLocationId =
                ch.alpenflight.migration.bundle.Coercions.deriveFanOutId(legacyLocationId, legacyClubId);

        Map<EntityType, EntityPolicy> entityPolicies = new java.util.HashMap<>();
        entityPolicies.put(EntityType.AIRCRAFT_RESERVATION_TYPE, fullPortPolicy());
        entityPolicies.put(EntityType.AIRCRAFT_RESERVATION, fullPortPolicy());
        entityPolicies.put(EntityType.COUNTRY, systemGlobalPolicy());
        entityPolicies.put(EntityType.LANGUAGE, systemGlobalPolicy());
        entityPolicies.put(EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy", pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("legacy_id_map/AIRCRAFT.pgcopy", pgcopyMap(legacyAircraftId, newAircraftId));
        tarEntries.put("legacy_id_map/PERSON.pgcopy", pgcopyMapMulti(
                new UUID[] {legacyPilotPersonId, newPilotPersonId},
                new UUID[] {legacySecondCrewPersonId, newSecondCrewPersonId}));
        tarEntries.put("legacy_id_map/FLIGHT_TYPE.pgcopy",
                pgcopyMap(legacyFlightTypeId, newFlightTypeId));
        tarEntries.put("legacy_id_map/AIRCRAFT_RESERVATION_TYPE.pgcopy",
                pgcopyMap(legacyReservationTypeId, newReservationTypeId));
        tarEntries.put("legacy_id_map/LOCATION.pgcopy",
                pgcopyMapFanOut(legacyLocationId, legacyClubId, newLocationId));
        tarEntries.put("AIRCRAFT_RESERVATION_TYPE.ndjson",
                reservationTypeNdjson(legacyReservationTypeId, legacyClubId));
        tarEntries.put("AIRCRAFT_RESERVATION.ndjson", reservationNdjson(
                legacyReservationId, legacyClubId, legacyAircraftId, legacyPilotPersonId,
                legacySecondCrewPersonId, legacyLocationId, legacyReservationTypeId,
                legacyFlightTypeId));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Reservation IT Deployment",
                List.of(club), entityPolicies, tarEntries);

        seedReservationFkParents(newAircraftId, newPilotPersonId, newSecondCrewPersonId,
                newLocationId, newFlightTypeId);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);

        assertThat(res.getStatusCode())
                .as("reservation + type round-trip ingest failed (FK resolution); body=%s",
                        res.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        UUID newClubId = UUID.fromString(body.get("clubIds").get(0).asText());

        Map<String, Object> type = jdbc.queryForMap(
                "SELECT id, operating_club_id, reservation_type_name "
                        + "FROM t_aircraft_reservation_type WHERE id = ?::uuid",
                newReservationTypeId.toString());
        assertThat(UUID.fromString(type.get("operating_club_id").toString()))
                .as("operating_club_id must rewrite to the provisioned club via "
                        + "legacy_id_map_club — NOT keep the verbatim legacy guid (the T-22 23503)")
                .isEqualTo(newClubId);

        Map<String, Object> reservation = jdbc.queryForMap(
                "SELECT operating_club_id, aircraft_id, pilot_person_id, second_crew_person_id, "
                        + "location_id, reservation_type_id, flight_type_id "
                        + "FROM t_aircraft_reservation WHERE id = ?::uuid",
                legacyReservationId.toString());
        assertThat(UUID.fromString(reservation.get("operating_club_id").toString()))
                .isEqualTo(newClubId);
        assertThat(UUID.fromString(reservation.get("aircraft_id").toString()))
                .isEqualTo(newAircraftId);
        assertThat(UUID.fromString(reservation.get("pilot_person_id").toString()))
                .isEqualTo(newPilotPersonId);
        assertThat(UUID.fromString(reservation.get("second_crew_person_id").toString()))
                .isEqualTo(newSecondCrewPersonId);
        assertThat(UUID.fromString(reservation.get("location_id").toString()))
                .as("location_id must rewrite through the composite fan-out map "
                        + "disambiguated by the reservation's own operating_club_id")
                .isEqualTo(newLocationId);
        assertThat(UUID.fromString(reservation.get("reservation_type_id").toString()))
                .isEqualTo(newReservationTypeId);
        assertThat(UUID.fromString(reservation.get("flight_type_id").toString()))
                .isEqualTo(newFlightTypeId);
    }

    @Test
    void club_full_port_reconciles_onto_provisioned_club_no_second_row() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        UUID legacyUserId = UUID.randomUUID();
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "Parity Club", testClubSlug, testClubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.USER, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.LANGUAGE, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy", pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/LANGUAGE.pgcopy",
                pgcopyMap(LEGACY_LANGUAGE_DE_SYNTHETIC, SEED_LANGUAGE_DE));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("CLUB.ndjson", clubNdjson(legacyClubId, testClubKey, "Legacy Aero Club",
                "Flugplatzstrasse 1", legacyCountryId, LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC));
        tarEntries.put("USER.ndjson",
                userNdjson(legacyUserId, legacyClubId, LEGACY_LANGUAGE_DE_SYNTHETIC));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Parity IT Deployment",
                List.of(club), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("CLUB FULL_PORT reconcile ingest failed; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        assertThat(body.get("clubIds").size()).isEqualTo(1);
        UUID newClubId = UUID.fromString(body.get("clubIds").get(0).asText());

        Integer clubCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_club WHERE deployment_id = ?::uuid",
                Integer.class, deploymentId.toString());
        assertThat(clubCount)
                .as("CLUB.ndjson must reconcile onto the provisioned row, not insert a second")
                .isEqualTo(1);

        Map<String, Object> reconciled = jdbc.queryForMap(
                "SELECT id, clubname, club_key, address, send_aircraft_statistic_report_to, "
                        + "run_delivery_creation_job, slug, public_registration_enabled "
                        + "FROM t_club WHERE deployment_id = ?::uuid", deploymentId.toString());
        assertThat(UUID.fromString(reconciled.get("id").toString()))
                .as("the reconciled row keeps the provisioning-minted id")
                .isEqualTo(newClubId);
        assertThat(reconciled.get("clubname")).isEqualTo("Legacy Aero Club");
        assertThat(reconciled.get("address")).isEqualTo("Flugplatzstrasse 1");
        assertThat(reconciled.get("send_aircraft_statistic_report_to"))
                .as("the bundle wins on a config column provisioning leaves NULL — so the "
                        + "parity holds on legacy values, not on values that match a default")
                .isEqualTo("ops-" + testClubKey);
        assertThat(reconciled.get("run_delivery_creation_job"))
                .as("the bundle wins on a boolean that diverges from the schema DEFAULT false")
                .isEqualTo(true);
        assertThat(reconciled.get("slug"))
                .as("provisioning-owned columns absent from ClubMapper survive the UPSERT")
                .isEqualTo(testClubSlug);
        assertThat(reconciled.get("public_registration_enabled")).isEqualTo(false);
        assertThat(reconciled.get("club_key"))
                .as("manifest and bundle carry the same club_key — the UPSERT must not mint "
                        + "a second row and trip ux_club_key")
                .isEqualTo(testClubKey);

        Map<String, Object> childUser = jdbc.queryForMap(
                "SELECT club_id FROM t_user WHERE id = ?::uuid", legacyUserId.toString());
        assertThat(UUID.fromString(childUser.get("club_id").toString())).isEqualTo(newClubId);
    }

    @Test
    void club_ndjson_with_unprovisioned_legacy_id_fails_closed() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID manifestClubId = UUID.randomUUID();
        UUID clubIdNeverDeclaredInTheManifest = UUID.randomUUID();
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                manifestClubId, "Parity Club", testClubSlug, testClubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy", pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("CLUB.ndjson", clubNdjson(clubIdNeverDeclaredInTheManifest, testClubKey, "Stray Club",
                "Nowhere 0", legacyCountryId, LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Parity IT Deployment",
                List.of(club), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("a CLUB row whose legacy id was not provisioned must fail closed; body=%s",
                        res.getBody())
                .isNotEqualTo(HttpStatus.OK);
        assertThat(JSON.readTree(res.getBody()).get("errorCode").asText())
                .as("the unprovisioned-CLUB abort is the fail-closed tenant-leak guard, "
                        + "not an incidental constraint violation")
                .isEqualTo("BUNDLE_CROSS_TENANT_FK_LEAK");
        Integer deployments = jdbc.queryForObject(
                "SELECT count(*) FROM t_deployment WHERE owner_keycloak_sub = ?::uuid",
                Integer.class, userSub.toString());
        assertThat(deployments).isEqualTo(0);
    }

    @Test
    void multi_club_each_full_port_reconciles_onto_its_own_provisioned_row() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubIdA = UUID.randomUUID();
        UUID legacyClubIdB = UUID.randomUUID();
        String keyA = testClubKey + "A";
        String keyB = testClubKey + "B";
        BundleManifest.ClubDeclaration clubA = new BundleManifest.ClubDeclaration(
                legacyClubIdA, "Club A", testClubSlug + "-a", keyA, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        BundleManifest.ClubDeclaration clubB = new BundleManifest.ClubDeclaration(
                legacyClubIdB, "Club B", testClubSlug + "-b", keyB, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy", pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        byte[] clubNdjsonInReverseOfManifestOrder = concatNdjson(
                clubNdjson(legacyClubIdB, keyB, "Club B Legacy", "Addr B",
                        legacyCountryId, LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC),
                clubNdjson(legacyClubIdA, keyA, "Club A Legacy", "Addr A",
                        legacyCountryId, LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC));
        tarEntries.put("CLUB.ndjson", clubNdjsonInReverseOfManifestOrder);

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Multi Club Deployment",
                List.of(clubA, clubB), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("multi-club CLUB reconcile failed; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());

        Integer clubCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_club WHERE deployment_id = ?::uuid",
                Integer.class, deploymentId.toString());
        assertThat(clubCount).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT address FROM t_club WHERE club_key = ?",
                String.class, keyA))
                .as("club A kept its OWN legacy address — the NDJSON pairs by club_key, not "
                        + "by position (it arrives reversed from the manifest order)")
                .isEqualTo("Addr A");
        assertThat(jdbc.queryForObject("SELECT address FROM t_club WHERE club_key = ?",
                String.class, keyB)).isEqualTo("Addr B");
        assertThat(jdbc.queryForObject(
                "SELECT send_aircraft_statistic_report_to FROM t_club WHERE club_key = ?",
                String.class, keyA))
                .as("a second per-club sentinel column, so a 'both rows got club B's "
                        + "columns' bug cannot hide behind one matching column")
                .isEqualTo("ops-" + keyA);
        assertThat(jdbc.queryForObject(
                "SELECT send_aircraft_statistic_report_to FROM t_club WHERE club_key = ?",
                String.class, keyB)).isEqualTo("ops-" + keyB);
    }

    private static byte[] clubNdjson(UUID legacyClubId, String clubKey, String clubname,
                                     String address, UUID legacyCountryId, UUID syntheticClubStateId)
            throws IOException {
        ObjectNode row = JSON.createObjectNode();
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
        row.put("club_state_id", syntheticClubStateId.toString());
        row.put("send_aircraft_statistic_report_to", "ops-" + clubKey);
        row.putNull("send_planning_day_info_mail_to");
        row.putNull("send_delivery_mail_export_to");
        row.putNull("send_trial_flight_registration_operator_email");
        row.putNull("send_passenger_flight_registration_operator_email");
        row.putNull("reply_to_email_address");
        row.put("run_delivery_creation_job", true);
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

    private static byte[] ndjsonLine(ObjectNode row) throws IOException {
        byte[] line = JSON.writeValueAsBytes(row);
        byte[] payload = new byte[line.length + 1];
        System.arraycopy(line, 0, payload, 0, line.length);
        payload[line.length] = '\n';
        return payload;
    }

    private static byte[] concatNdjson(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static byte[] pgcopyMap(UUID legacyGuid, UUID newUuid) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            writer.write(legacyGuid, newUuid);
        }
        return sink.toByteArray();
    }

    private static byte[] pgcopyMapMulti(UUID[]... legacyGuidAndNewUuidPairs)
            throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            for (UUID[] pair : legacyGuidAndNewUuidPairs) {
                writer.write(pair[0], pair[1]);
            }
        }
        return sink.toByteArray();
    }

    private static byte[] pgcopyMapFanOut(UUID legacyGuid, UUID legacyClubId, UUID newUuid)
            throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            writer.write(legacyGuid, legacyClubId, newUuid);
        }
        return sink.toByteArray();
    }

    private void seedReservationFkParents(UUID aircraftId, UUID pilotPersonId,
                                          UUID secondCrewPersonId, UUID locationId,
                                          UUID flightTypeId) {
        UUID anyCountry = jdbc.queryForObject(
                "SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID anyLocationType = jdbc.queryForObject(
                "SELECT id FROM t_location_type LIMIT 1", UUID.class);
        UUID anyAircraftType = jdbc.queryForObject(
                "SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);

        jdbc.update("INSERT INTO t_person (id, lastname, firstname) VALUES (?::uuid, ?, ?)",
                pilotPersonId.toString(), "Pilot", "Reservation");
        jdbc.update("INSERT INTO t_person (id, lastname, firstname) VALUES (?::uuid, ?, ?)",
                secondCrewPersonId.toString(), "Crew", "Second");
        jdbc.update("INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid)",
                locationId.toString(), SEED_TENANT_USER_CLUB.toString(), "Reservation Field",
                anyCountry.toString(), anyLocationType.toString());
        jdbc.update("INSERT INTO t_aircraft (id, managing_club_id, aircraft_type_id, immatriculation) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, ?)",
                aircraftId.toString(), SEED_TENANT_USER_CLUB.toString(),
                anyAircraftType.toString(), "HB-3999");
        jdbc.update("INSERT INTO t_flight_type (id, operating_club_id, flight_type_name) "
                        + "VALUES (?::uuid, ?::uuid, ?)",
                flightTypeId.toString(), SEED_TENANT_USER_CLUB.toString(), "Reservation Type Flight");
    }

    private static byte[] reservationTypeNdjson(UUID legacyTypeId, UUID legacyClubId)
            throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyTypeId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        row.put("reservation_type_name", "Wartung");
        row.put("is_instructor_required", false);
        row.put("is_maintenance", true);
        row.put("is_active", true);
        row.putNull("remarks");
        String created = Instant.parse("2024-01-01T00:00:00Z").toString();
        row.put("created_on", created);
        row.putNull("created_by_user_id");
        row.put("modified_on", created);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private static byte[] reservationNdjson(UUID legacyId, UUID legacyClubId, UUID legacyAircraftId,
                                            UUID legacyPilotPersonId, UUID legacySecondCrewPersonId,
                                            UUID legacyLocationId, UUID legacyReservationTypeId,
                                            UUID legacyFlightTypeId) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        row.put("aircraft_id", legacyAircraftId.toString());
        row.put("reservation_start", Instant.parse("2024-06-15T07:00:00Z").toString());
        row.put("reservation_end", Instant.parse("2024-06-15T11:00:00Z").toString());
        row.put("is_all_day", false);
        row.put("pilot_person_id", legacyPilotPersonId.toString());
        row.put("second_crew_person_id", legacySecondCrewPersonId.toString());
        row.put("location_id", legacyLocationId.toString());
        row.put("reservation_type_id", legacyReservationTypeId.toString());
        row.put("flight_type_id", legacyFlightTypeId.toString());
        row.put("info", "Cross-tenant timed reservation (fixture)");
        String created = Instant.parse("2024-01-01T00:00:00Z").toString();
        row.put("created_on", created);
        row.putNull("created_by_user_id");
        row.put("modified_on", created);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private static byte[] userNdjson(UUID legacyUserId, UUID legacyClubId, UUID syntheticLanguageId)
            throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyUserId.toString());
        row.put("club_id", legacyClubId.toString());
        row.put("username", "parity-user-" + legacyUserId);
        row.put("friendly_name", "Parity User");
        row.putNull("person_id");
        row.put("notification_email", "parity-user-" + legacyUserId + "@example.com");
        row.putNull("phone_number");
        row.putNull("remarks");
        row.put("language_id", syntheticLanguageId.toString());
        row.putNull("keycloak_sub");
        String createdInstant = Instant.parse("2024-01-01T00:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.putNull("created_by_user_id");
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        byte[] line = JSON.writeValueAsBytes(row);
        byte[] payload = new byte[line.length + 1];
        System.arraycopy(line, 0, payload, 0, line.length);
        payload[line.length] = '\n';
        return payload;
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

    private static byte[] decodePem(String pem) {
        String body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

}
