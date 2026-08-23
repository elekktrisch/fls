package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

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
class AuditLogMigrationRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final String LEGACY_ACTOR_USERNAME = "h.muster";
    private static final String LEGACY_ORPHAN_ACTOR_USERNAME = "left.the.club";
    private static final long LEGACY_AUDIT_LOG_IDENTITY_OF_THE_RESOLVED_ROW = 4242L;
    private static final long LEGACY_AUDIT_LOG_IDENTITY_OF_THE_ORPHAN_ROW = 4243L;
    private static final String LEGACY_TARGET_RECORD_ID_THAT_IS_NOT_A_UUID = "42";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired MigrationBundleCipher cipher;

    private UUID uploaderUserId;
    private UUID uploaderSub;
    private String verifiedToken;
    private String testClubKey;
    private String testClubSlug;

    private UUID legacyActorUserId;
    private UUID migratedActorUserId;
    private UUID resolvedAuditRowId;
    private UUID orphanAuditRowId;
    private UUID synthesisedOrphanActorId;

    @BeforeEach
    void seedUploaderAndTheMigratedActorTheIdMapResolvesTo() {
        uploaderSub = UUID.randomUUID();
        uploaderUserId = UUID.randomUUID();
        legacyActorUserId = UUID.randomUUID();
        migratedActorUserId = UUID.randomUUID();
        resolvedAuditRowId = UUID.randomUUID();
        orphanAuditRowId = UUID.randomUUID();
        synthesisedOrphanActorId = UUID.randomUUID();
        String tag = uploaderSub.toString().substring(0, 5);
        testClubKey = "AUD-" + tag;
        testClubSlug = "aud-" + tag;

        insertUser(uploaderUserId, "aud-it-" + uploaderSub, "Audit IT Uploader",
                "aud-" + uploaderSub + "@example.com", uploaderSub);
        insertUser(migratedActorUserId, LEGACY_ACTOR_USERNAME + "-" + tag, "Hans Muster",
                "muster-" + uploaderSub + "@example.com", null);

        verifiedToken = jwts.mint(c -> c
                .subject(uploaderSub.toString())
                .claim("email_verified", true));
    }

    private void insertUser(UUID id, String username, String friendlyName,
                            String notificationEmail, UUID keycloakSub) {
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                id.toString(), SEED_TENANT_USER_CLUB.toString(), username, friendlyName,
                notificationEmail, SEED_LANGUAGE_DE.toString(),
                keycloakSub == null ? null : keycloakSub.toString());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE id IN (?::uuid, ?::uuid)",
                resolvedAuditRowId.toString(), orphanAuditRowId.toString());
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
        jdbc.update("DELETE FROM t_user WHERE id IN (?::uuid, ?::uuid)",
                uploaderUserId.toString(), migratedActorUserId.toString());
    }

    @Test
    void migratedAuditRowsLandInTheTableTheSystemLogsScreenReadsWithTheActorResolved()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                UUID.randomUUID(), "Audit Club", testClubSlug, testClubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.AUDIT_LOG, new EntityPolicy(
                        EntityPolicy.PortPolicy.FULL_PORT,
                        EntityPolicy.TombstonePolicy.PORT_ALL,
                        Set.of("actor_user_id"),
                        List.of()));

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/USER.pgcopy",
                pgcopyMap(legacyActorUserId, migratedActorUserId));
        tarEntries.put("AUDIT_LOG.ndjson", concat(
                auditLogNdjson(resolvedAuditRowId, legacyActorUserId, LEGACY_ACTOR_USERNAME,
                        LEGACY_AUDIT_LOG_IDENTITY_OF_THE_RESOLVED_ROW, null),
                auditLogNdjson(orphanAuditRowId, null, LEGACY_ORPHAN_ACTOR_USERNAME,
                        LEGACY_AUDIT_LOG_IDENTITY_OF_THE_ORPHAN_ROW, synthesisedOrphanActorId)));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Audit Migrate IT Deployment",
                List.of(club), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("AUDIT_LOG ingest failed. A 'relation t_audit_log does not exist' here is "
                        + "the enum-derived destination table; the real destination is "
                        + "t_mutation_audit_event. body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> resolved = jdbc.queryForMap(
                "SELECT actor_user_id, actor_kind, action, target_entity_type, "
                        + "       legacy_actor_user_id, legacy_int_id, "
                        + "       legacy_target_record_id, legacy_orphan_actor_id "
                        + "  FROM t_mutation_audit_event WHERE id = ?::uuid",
                resolvedAuditRowId.toString());

        assertThat(UUID.fromString(resolved.get("actor_user_id").toString()))
                .as("the ingest resolves actor_user_id through legacy_id_map_user onto the "
                        + "migrated AlpenFlight user; the raw legacy actor guid %s must not "
                        + "survive into the column /system/logs renders", legacyActorUserId)
                .isEqualTo(migratedActorUserId)
                .isNotEqualTo(legacyActorUserId);
        assertThat(jdbc.queryForObject(
                "SELECT username FROM t_user WHERE id = ?::uuid", String.class,
                resolved.get("actor_user_id").toString()))
                .as("actor_user_id points at a real t_user row, so the screen can render a "
                        + "user name instead of a raw legacy identifier")
                .isNotBlank();
        assertThat(resolved.get("actor_kind")).isEqualTo("LEGACY_MIGRATED");
        assertThat(resolved.get("action")).isEqualTo("UPDATE");
        assertThat(resolved.get("legacy_actor_user_id")).isEqualTo(LEGACY_ACTOR_USERNAME);
        assertThat(((Number) resolved.get("legacy_int_id")).longValue())
                .isEqualTo(LEGACY_AUDIT_LOG_IDENTITY_OF_THE_RESOLVED_ROW);
        assertThat(resolved.get("legacy_target_record_id"))
                .isEqualTo(LEGACY_TARGET_RECORD_ID_THAT_IS_NOT_A_UUID);
        assertThat(resolved.get("legacy_orphan_actor_id"))
                .as("a resolved actor carries no synthesised orphan id — the pair is exclusive")
                .isNull();

        Map<String, Object> orphan = jdbc.queryForMap(
                "SELECT actor_user_id, legacy_actor_user_id, legacy_orphan_actor_id "
                        + "  FROM t_mutation_audit_event WHERE id = ?::uuid",
                orphanAuditRowId.toString());
        assertThat(orphan.get("actor_user_id"))
                .as("an actor the bundle never migrated stays NULL on the FK-constrained column")
                .isNull();
        assertThat(UUID.fromString(orphan.get("legacy_orphan_actor_id").toString()))
                .isEqualTo(synthesisedOrphanActorId);
        assertThat(orphan.get("legacy_actor_user_id"))
                .isEqualTo(LEGACY_ORPHAN_ACTOR_USERNAME);
    }

    private byte[] auditLogNdjson(UUID legacyGuid,
                                  UUID legacyActorGuid,
                                  String legacyUserName,
                                  long legacyAuditLogIdentity,
                                  UUID orphanActorId) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyGuid.toString());
        row.put("occurred_at", Instant.parse("2024-06-15T08:30:00Z").toString());
        if (legacyActorGuid == null) {
            row.putNull("actor_user_id");
        } else {
            row.put("actor_user_id", legacyActorGuid.toString());
        }
        row.putNull("actor_keycloak_sub");
        row.putNull("tenant_club_id");
        row.put("action", "UPDATE");
        row.put("actor_kind", "LEGACY_MIGRATED");
        row.put("target_entity_type", "Flight");
        row.putNull("target_entity_id");
        row.putNull("request_id");
        row.putNull("before_state");
        row.putNull("after_state");
        row.put("failed", false);
        row.put("system_actor", false);
        row.putNull("http_status");
        row.putNull("failure_reason");
        row.put("legacy_actor_user_id", legacyUserName);
        row.put("legacy_int_id", legacyAuditLogIdentity);
        row.put("legacy_target_record_id", LEGACY_TARGET_RECORD_ID_THAT_IS_NOT_A_UUID);
        if (orphanActorId == null) {
            row.putNull("legacy_orphan_actor_id");
        } else {
            row.put("legacy_orphan_actor_id", orphanActorId.toString());
        }
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

    private static byte[] pgcopyMap(UUID legacyGuid, UUID newUuid) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            writer.write(legacyGuid, newUuid);
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

    private static byte[] decodePem(String pem) {
        String body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return java.util.Base64.getDecoder().decode(body);
    }
}
