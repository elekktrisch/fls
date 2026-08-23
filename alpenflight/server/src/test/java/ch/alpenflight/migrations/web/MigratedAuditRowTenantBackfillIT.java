package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.MockKeycloakDirectoryConfig;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class MigratedAuditRowTenantBackfillIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH =
            UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE =
            UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    private static final String TEST_NAME_PREFIX = "IT_MATB_";
    private static final String TEST_KEY_PREFIX = "IT_MB_";

    private static final String DESCRIBED_ENTITY_THAT_NAMES_A_CLUB = "User";
    private static final String DESCRIBED_ENTITY_THAT_IS_CROSS_TENANT = "Person";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MigrationBundleCipher cipher;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubOfTheActor;
    private UUID clubOfTheDescribedUser;
    private UUID actorUserId;
    private UUID describedUserId;

    private UUID legacyActorGuid;
    private UUID legacyDescribedUserGuid;
    private UUID legacyPersonGuid;
    private UUID legacyUserGuidTheBundleNeverMigrated;

    private UUID rowDescribingTheClubUser;
    private UUID rowDescribingACrossTenantPerson;
    private UUID rowDescribingAnUnmigratedUser;

    private UUID uploaderUserId;
    private UUID uploaderSub;
    private String verifiedToken;
    private String describedClubAdministratorToken;
    private String otherClubAdministratorToken;

    @BeforeEach
    void seedTwoClubsOneActorAndOneDescribedUserInTheOtherClub() {
        TwoClubFixture fixture = new TwoClubFixture(
                jdbc, clubs, countries, clubStates, TEST_NAME_PREFIX, TEST_KEY_PREFIX);
        fixture.seed();
        clubOfTheActor = fixture.clubA();
        clubOfTheDescribedUser = fixture.clubB();

        uploaderSub = UUID.randomUUID();
        uploaderUserId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        describedUserId = UUID.randomUUID();
        legacyActorGuid = UUID.randomUUID();
        legacyDescribedUserGuid = UUID.randomUUID();
        legacyPersonGuid = UUID.randomUUID();
        legacyUserGuidTheBundleNeverMigrated = UUID.randomUUID();
        rowDescribingTheClubUser = UUID.randomUUID();
        rowDescribingACrossTenantPerson = UUID.randomUUID();
        rowDescribingAnUnmigratedUser = UUID.randomUUID();

        insertUser(uploaderUserId, clubOfTheActor, "matb-up-" + uploaderSub, uploaderSub);
        insertUser(actorUserId, clubOfTheActor, "matb-actor-" + uploaderSub, null);
        insertUser(describedUserId, clubOfTheDescribedUser, "matb-target-" + uploaderSub, null);

        verifiedToken = jwts.mint(c -> c
                .subject(uploaderSub.toString())
                .claim("email_verified", true));
        describedClubAdministratorToken = clubAdministratorTokenFor(clubOfTheDescribedUser);
        otherClubAdministratorToken = clubAdministratorTokenFor(clubOfTheActor);
    }

    private String clubAdministratorTokenFor(UUID clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private void insertUser(UUID id, UUID clubId, String username, UUID keycloakSub) {
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                id.toString(), clubId.toString(), username, username,
                username + "@example.com", SEED_LANGUAGE_DE.toString(),
                keycloakSub == null ? null : keycloakSub.toString());
    }

    @AfterEach
    void removeTheProbeRowsAndTheProvisionedDeployment() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE id IN (?::uuid, ?::uuid, ?::uuid)",
                rowDescribingTheClubUser.toString(),
                rowDescribingACrossTenantPerson.toString(),
                rowDescribingAnUnmigratedUser.toString());
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?",
                uploaderSub.toString());
        String clubsByOwner =
                "SELECT id FROM t_club WHERE deployment_id IN "
                        + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)";
        jdbc.update("DELETE FROM t_migration_run WHERE upload_id IN "
                + "(SELECT id FROM t_migration_upload WHERE user_id = ?::uuid)",
                uploaderUserId.toString());
        jdbc.update("DELETE FROM t_migration_upload WHERE user_id = ?::uuid",
                uploaderUserId.toString());
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id IN (" + clubsByOwner + ")",
                uploaderSub.toString());
        jdbc.update("DELETE FROM t_member_state WHERE club_id IN (" + clubsByOwner + ")",
                uploaderSub.toString());
        jdbc.update("DELETE FROM t_user WHERE club_id IN (" + clubsByOwner + ")",
                uploaderSub.toString());
        jdbc.update("DELETE FROM t_club WHERE deployment_id IN "
                + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)",
                uploaderSub.toString());
        jdbc.update("DELETE FROM t_deployment WHERE owner_keycloak_sub = ?::uuid",
                uploaderSub.toString());
        jdbc.update("DELETE FROM t_user WHERE id IN (?::uuid, ?::uuid, ?::uuid)",
                uploaderUserId.toString(), actorUserId.toString(), describedUserId.toString());
    }

    @Test
    void theMigratedRowAdoptsTheClubOfTheUserItDescribesAndItsAdministratorReadsIt()
            throws Exception {
        ingestTheAuditBundle();

        assertThat(clubOfTheActor)
                .as("the two clubs really differ, so 'club of the described user' and "
                        + "'club of the actor' are distinguishable answers")
                .isNotEqualTo(clubOfTheDescribedUser);
        assertThat(jdbc.queryForObject("SELECT club_id FROM t_user WHERE id = ?::uuid",
                UUID.class, actorUserId.toString()))
                .as("the audit row's actor genuinely lives in the other club")
                .isEqualTo(clubOfTheActor);

        assertThat(tenantOf(rowDescribingTheClubUser))
                .as("the migrated row takes the club of the User it describes, never the club "
                        + "of the actor who made the change")
                .isEqualTo(clubOfTheDescribedUser)
                .isNotEqualTo(clubOfTheActor);

        assertThat(auditRowIdsVisibleToTheDescribedClubAdministrator())
                .as("the backfilled club puts the migrated row on the audit-log screen of the "
                        + "club it belongs to")
                .contains(rowDescribingTheClubUser);
    }

    @Test
    void aRowDescribingACrossTenantOrUnmigratedEntityKeepsNoClubAndStaysOffEveryScreen()
            throws Exception {
        ingestTheAuditBundle();

        assertThat(tenantOf(rowDescribingACrossTenantPerson))
                .as("t_person carries no club column — a Person is cross-tenant per ADR 0008, "
                        + "so the row it describes names no club and stays NULL")
                .isNull();
        assertThat(tenantOf(rowDescribingAnUnmigratedUser))
                .as("the bundle never migrated this legacy User, so no migrated row names its "
                        + "club and the audit row stays NULL")
                .isNull();

        assertThat(auditRowIdsVisibleToTheDescribedClubAdministrator())
                .as("a row without a club reaches no club administrator's screen")
                .doesNotContain(rowDescribingACrossTenantPerson)
                .doesNotContain(rowDescribingAnUnmigratedUser);
    }

    @Test
    void anAdministratorOfTheActorsClubStillReadsNoMigratedRowOfTheDescribedClub()
            throws Exception {
        ingestTheAuditBundle();

        assertThat(auditRowIdsVisibleTo(otherClubAdministratorToken))
                .as("the backfill grants no tenancy bypass: the administrator of the actor's "
                        + "club still reads none of the described club's migrated rows")
                .doesNotContain(rowDescribingTheClubUser)
                .doesNotContain(rowDescribingACrossTenantPerson)
                .doesNotContain(rowDescribingAnUnmigratedUser);
    }

    private void ingestTheAuditBundle() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        String tag = uploaderSub.toString().substring(0, 5);
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), "Backfill Club", "matb-" + tag, "MATB-" + tag, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.AUDIT_LOG, new EntityPolicy(
                        EntityPolicy.PortPolicy.FULL_PORT,
                        EntityPolicy.TombstonePolicy.PORT_ALL,
                        Set.of("actor_user_id"),
                        List.of()));

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/USER.pgcopy", pgcopyMap(Map.of(
                legacyActorGuid, actorUserId,
                legacyDescribedUserGuid, describedUserId)));
        tarEntries.put("AUDIT_LOG.ndjson", concat(List.of(
                auditLogNdjson(rowDescribingTheClubUser, 91_001L,
                        DESCRIBED_ENTITY_THAT_NAMES_A_CLUB, legacyDescribedUserGuid),
                auditLogNdjson(rowDescribingACrossTenantPerson, 91_002L,
                        DESCRIBED_ENTITY_THAT_IS_CROSS_TENANT, legacyPersonGuid),
                auditLogNdjson(rowDescribingAnUnmigratedUser, 91_003L,
                        DESCRIBED_ENTITY_THAT_NAMES_A_CLUB,
                        legacyUserGuidTheBundleNeverMigrated))));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Audit Tenant Backfill IT Deployment",
                List.of(club), entityPolicies, tarEntries);

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/migrations/" + uploadId + "/bundle"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bundle),
                String.class);
        assertThat(res.getStatusCode())
                .as("AUDIT_LOG ingest failed. body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private UUID tenantOf(UUID auditRowId) {
        return jdbc.queryForObject(
                "SELECT tenant_club_id FROM t_mutation_audit_event WHERE id = ?::uuid",
                UUID.class, auditRowId.toString());
    }

    private List<UUID> auditRowIdsVisibleToTheDescribedClubAdministrator() throws IOException {
        return auditRowIdsVisibleTo(describedClubAdministratorToken);
    }

    private List<UUID> auditRowIdsVisibleTo(String token) throws IOException {
        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/admin/audit-events"
                                + "?targetEntityType=" + DESCRIBED_ENTITY_THAT_NAMES_A_CLUB
                                + "&pageSize=200"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UUID> ids = new ArrayList<>();
        for (JsonNode row : JSON.readTree(res.getBody()).get("items")) {
            ids.add(UUID.fromString(row.get("id").asText()));
        }
        return ids;
    }

    private byte[] auditLogNdjson(UUID legacyGuid,
                                  long legacyAuditLogIdentity,
                                  String targetEntityType,
                                  UUID targetEntityId) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyGuid.toString());
        row.put("occurred_at", Instant.parse("2024-06-15T08:30:00Z").toString());
        row.put("actor_user_id", legacyActorGuid.toString());
        row.putNull("actor_keycloak_sub");
        row.putNull("tenant_club_id");
        row.put("action", "UPDATE");
        row.put("actor_kind", "LEGACY_MIGRATED");
        row.put("target_entity_type", targetEntityType);
        row.put("target_entity_id", targetEntityId.toString());
        row.putNull("request_id");
        row.putNull("before_state");
        row.putNull("after_state");
        row.put("failed", false);
        row.put("system_actor", false);
        row.putNull("http_status");
        row.putNull("failure_reason");
        row.put("legacy_actor_user_id", "matb.actor");
        row.put("legacy_int_id", legacyAuditLogIdentity);
        row.putNull("legacy_target_record_id");
        row.putNull("legacy_orphan_actor_id");
        return ndjsonLine(row);
    }

    private static byte[] ndjsonLine(ObjectNode row) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator gen = JSON.getFactory().createGenerator(sink)) {
            JSON.writeTree(gen, row);
        }
        sink.write('\n');
        return sink.toByteArray();
    }

    private static byte[] pgcopyMap(Map<UUID, UUID> newUuidByLegacyGuid) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            for (Map.Entry<UUID, UUID> mapping : newUuidByLegacyGuid.entrySet()) {
                writer.write(mapping.getKey(), mapping.getValue());
            }
        }
        return sink.toByteArray();
    }

    private static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            joined.writeBytes(part);
        }
        return joined.toByteArray();
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

    private static byte[] decodePem(String pem) {
        String body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return java.util.Base64.getDecoder().decode(body);
    }
}
