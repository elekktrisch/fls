package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.migrations.domain.MigrationBundleCipher;
import ch.alpenflight.platform.security.JwtTestFixture;
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

/**
 * S-141b AC1 — vertical-slice parity round-trip. Proves the FULL_PORT
 * NDJSON ingest path works end-to-end with FK rewriting against the
 * pgcopy-seeded {@code legacy_id_map_<entity>} maps.
 *
 * <p>Scope is narrower than the QA plan's literal text (5 mappers
 * round-trip through {@code LegacyFixtureSeeder + ProducerHarness +
 * KnownMappers.all()}): the harness in {@code migration-bundle/}'s
 * {@code parity} source set is not on the server's testImplementation
 * classpath (composite-include exposes the main artifact only), and the
 * CLUB FULL_PORT path conflicts with the provisioning service's
 * t_club-create — those are S-141c (provisioning-vs-ingest reconciliation)
 * and S-187a (cross-module test fixture sharing). What this IT proves
 * is sufficient for the orchestrator's load-bearing changes in S-141b:
 *
 * <ul>
 *   <li>{@code legacy_id_map/<entity>.pgcopy} entries COPY into the
 *       per-bundle temp tables in the same single txn.</li>
 *   <li>SYSTEM_GLOBAL_RESOLVE FKs ({@code language_id}) translate from
 *       the bundle's synthetic UUID to the V2-seed UUID via lookup
 *       against {@code legacy_id_map_language}.</li>
 *   <li>FULL_PORT FKs ({@code club_id}) translate via the
 *       {@code seedClubLegacyIdMap}-populated {@code legacy_id_map_club}.</li>
 *   <li>The wire-format {@code legacy_guid} column maps to the
 *       destination's {@code id} per ADR 0019 — UserMapper's NDJSON
 *       lands on {@code t_user.id} with the legacy UserId preserved.</li>
 * </ul>
 *
 * <p>The full 5-mapper / MSSQL-fixture round-trip lives in the parity
 * oracle harness ({@code ParityOracleHarnessTest}, gated to the parity
 * source set + Docker-availability check).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
@Tag("slow")
class MigrationBundleParityRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    /**
     * Legacy LanguageId 7 → de (matches the canonical FLSTest seed). The
     * UserMapper writes this as the synthetic UUID
     * {@code 00000000-0000-0000-0000-000000000007} via
     * {@code Coercions.legacyIntIdToUuidString}.
     */
    private static final UUID LEGACY_LANGUAGE_DE_SYNTHETIC = new UUID(0L, 7L);
    /** Legacy ClubStateId 1 → ACTIVE, same convention. */
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
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?", userSub.toString());
        // Bundle-ingested users land under the provisioned Club — tear those
        // down first so the FK to t_club doesn't block the Club delete.
        jdbc.update("DELETE FROM t_user WHERE club_id IN "
                + "(SELECT id FROM t_club WHERE deployment_id IN "
                + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid))",
                userSub.toString());
        jdbc.update("DELETE FROM t_migration_run WHERE upload_id IN "
                + "(SELECT id FROM t_migration_upload WHERE user_id = ?::uuid)", userId.toString());
        jdbc.update("DELETE FROM t_migration_upload WHERE user_id = ?::uuid", userId.toString());
        String clubsByOwner =
                "SELECT id FROM t_club WHERE deployment_id IN "
                        + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)";
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

        // Manifest: USER as FULL_PORT, 3 reference entities as
        // SYSTEM_GLOBAL_RESOLVE (their pgcopy entries seed the legacy_id_map_*
        // temp tables before the FK resolver consults them on the USER row).
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

        // The ingested User row: id preserved from legacy_guid; club_id
        // resolved to the provisioning-minted newClubId via
        // legacy_id_map_club (seedClubLegacyIdMap); language_id resolved
        // from synthetic UUID to V2-seed via legacy_id_map_language.
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

        // Provisioning state: 1 Deployment + 1 Club created with the
        // manifest-declared metadata.
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

        // Exactly one t_club under the deployment: the provisioned row, reconciled
        // — not a second row inserted from CLUB.ndjson.
        Integer clubCount = jdbc.queryForObject(
                "SELECT count(*) FROM t_club WHERE deployment_id = ?::uuid",
                Integer.class, deploymentId.toString());
        assertThat(clubCount)
                .as("CLUB.ndjson must reconcile onto the provisioned row, not insert a second")
                .isEqualTo(1);

        Map<String, Object> reconciled = jdbc.queryForMap(
                "SELECT id, clubname, club_key, address, slug, public_registration_enabled "
                        + "FROM t_club WHERE deployment_id = ?::uuid", deploymentId.toString());
        assertThat(UUID.fromString(reconciled.get("id").toString()))
                .as("the reconciled row keeps the provisioning-minted id")
                .isEqualTo(newClubId);
        // Legacy columns from CLUB.ndjson land via the UPSERT (bundle wins on the
        // mapper columns).
        assertThat(reconciled.get("clubname")).isEqualTo("Legacy Aero Club");
        assertThat(reconciled.get("address")).isEqualTo("Flugplatzstrasse 1");
        // Provisioning-owned synthetic columns (absent from ClubMapper) survive.
        assertThat(reconciled.get("slug")).isEqualTo(testClubSlug);
        assertThat(reconciled.get("public_registration_enabled")).isEqualTo(false);
        // club_key carried identically by manifest + bundle — no ux_club_key dup.
        assertThat(reconciled.get("club_key")).isEqualTo(testClubKey);

        // Child user lands under the reconciled (provisioned) club.
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
        UUID strayClubId = UUID.randomUUID(); // never declared in the manifest
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
        tarEntries.put("CLUB.ndjson", clubNdjson(strayClubId, testClubKey, "Stray Club",
                "Nowhere 0", legacyCountryId, LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Parity IT Deployment",
                List.of(club), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("a CLUB row whose legacy id was not provisioned must fail closed; body=%s",
                        res.getBody())
                .isNotEqualTo(HttpStatus.OK);
        // The whole single txn rolled back — no Deployment provisioned for the caller.
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
        // CLUB.ndjson order (B then A) is the reverse of the manifest order to
        // prove the legacy_id_map_club pairing is by club_key identity, not index.
        byte[] both = concatNdjson(
                clubNdjson(legacyClubIdB, keyB, "Club B Legacy", "Addr B",
                        legacyCountryId, LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC),
                clubNdjson(legacyClubIdA, keyA, "Club A Legacy", "Addr A",
                        legacyCountryId, LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC));
        tarEntries.put("CLUB.ndjson", both);

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
        // Each provisioned club received its OWN legacy columns, matched by club_key.
        assertThat(jdbc.queryForObject("SELECT address FROM t_club WHERE club_key = ?",
                String.class, keyA)).isEqualTo("Addr A");
        assertThat(jdbc.queryForObject("SELECT address FROM t_club WHERE club_key = ?",
                String.class, keyB)).isEqualTo("Addr B");
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
        // t_club.created_on / modified_on are NOT NULL — carry explicit instants
        // so the UPSERT's EXCLUDED values don't suppress the row's timestamps.
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
        // t_user.created_on / modified_on are NOT NULL DEFAULT now() in V2.
        // The mapper unconditionally binds the NDJSON value via setTimestamp
        // — passing NULL explicitly suppresses the DEFAULT and the INSERT
        // FK-violates. Carry an explicit ISO-8601 instant on both fields.
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
