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
 * The Article migrate round-trip proof (the {@code DeliveryItem.article_id}
 * RESTRICT FK target). The {@code ArticleMapper} + {@code t_article} schema
 * shipped at the V3 baseline but had NO
 * {@link ch.alpenflight.migration.bundle.MapperLegacyBindings} producer entry, so
 * the producer SELECT + real round-trip had never run
 * ({@code verify_infra_is_run_not_just_authored}). The binding is now wired; this IT
 * proves it end-to-end against the REAL server ingest pipeline so the
 * {@link ch.alpenflight.migrations.application.ForeignKeyResolver} runs live.
 *
 * <p>The load-bearing invariant: {@code operating_club_id} is the {@code @TenantId}
 * and is OFF-convention for the CLUB FK (the resolver's default derives
 * {@code club_id}). The mapper's {@code foreignKeyColumns()} override is what
 * rewrites the legacy {@code ClubId} GUID to the provisioned club's new id —
 * without it every article reaches the INSERT with a verbatim legacy GUID and
 * 23503s {@code fk_article_operating_club_id} (the orphan-tenant FK failure that
 * would red the ~20-min fanout). Mirrors {@link AccountingRuleFilterMigrationRoundTripIT}.
 *
 * <p>No collision dedupe: legacy {@code Articles} already enforces UNIQUE
 * {@code (ArticleNumber, ClubId)} over live rows, mirroring V3's
 * {@code ux_article_club_number} ({@code WHERE deleted_on IS NULL}) — so a
 * soft-deleted article can share a live one's {@code (club, article_number)}
 * without colliding. This IT seeds that exact case and asserts both land.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({JwtTestFixture.class, MockKeycloakDirectoryConfig.class})
@Tag("slow")
class ArticleMigrationRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

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
    void seedActor() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        legacyCountryId = UUID.randomUUID();
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "ART-" + tag;
        testClubSlug = "art-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "art-it-" + userSub, "Article IT",
                "art-" + userSub + "@example.com",
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
        jdbc.update("DELETE FROM t_article WHERE operating_club_id IN ("
                + clubsByOwner + ")", userSub.toString());
        jdbc.update("DELETE FROM t_migration_run WHERE upload_id IN "
                + "(SELECT id FROM t_migration_upload WHERE user_id = ?::uuid)", userId.toString());
        jdbc.update("DELETE FROM t_migration_upload WHERE user_id = ?::uuid", userId.toString());
        jdbc.update("DELETE FROM t_user WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
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
    void article_migrates_tenant_scoped_with_resolved_club_fk_and_no_unique_collision()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubIdA = UUID.randomUUID();
        UUID legacyClubIdB = UUID.randomUUID();
        String keyA = testClubKey + "A";
        String keyB = testClubKey + "B";
        BundleManifest.ClubDeclaration clubA = new BundleManifest.ClubDeclaration(
                legacyClubIdA, "Art Club A", testClubSlug + "-a", keyA, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        BundleManifest.ClubDeclaration clubB = new BundleManifest.ClubDeclaration(
                legacyClubIdB, "Art Club B", testClubSlug + "-b", keyB, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        // Club A: a live article, plus a SOFT-DELETED article sharing the SAME
        // (club, article_number) — legacy permits it (UNIQUE covers live rows
        // only) and it must land without 23505 on ux_article_club_number.
        UUID liveArticleA = UUID.randomUUID();
        UUID softDeletedArticleA = UUID.randomUUID();
        // Club B: one article — tenant isolation + the same article_number "A-1"
        // in a DIFFERENT club must NOT collide (the UNIQUE is per club).
        UUID articleB = UUID.randomUUID();

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.ARTICLE, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("CLUB.ndjson", concat(
                clubNdjson(legacyClubIdA, keyA, "Art Club A Legacy"),
                clubNdjson(legacyClubIdB, keyB, "Art Club B Legacy")));
        tarEntries.put("ARTICLE.ndjson", concat(concat(
                articleNdjson(liveArticleA, legacyClubIdA, "A-1", "Landing fee", false),
                articleNdjson(softDeletedArticleA, legacyClubIdA, "A-1", "Old landing fee", true)),
                articleNdjson(articleB, legacyClubIdB, "A-1", "Club B landing fee", false)));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "Article Migrate IT Deployment",
                List.of(clubA, clubB), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("Article migrate round-trip ingest failed (a 23503 here is the "
                        + "unresolved operating_club_id CLUB FK); body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        Map<String, UUID> clubIdByKey = clubIdsByKey(deploymentId);
        UUID newClubA = clubIdByKey.get(keyA);
        UUID newClubB = clubIdByKey.get(keyB);

        // The live club-A article: operating_club_id resolved to the provisioned
        // club (NOT the verbatim legacy GUID — that would 23503 fk_article_operating_club_id).
        Map<String, Object> live = jdbc.queryForMap(
                "SELECT operating_club_id, article_number, deleted_on "
                        + "FROM t_article WHERE id = ?::uuid", liveArticleA.toString());
        assertThat(UUID.fromString(live.get("operating_club_id").toString()))
                .as("operating_club_id resolves through foreignKeyColumns() to the "
                        + "provisioned club A — NOT the verbatim legacy GUID (which would "
                        + "FK-violate fk_article_operating_club_id)")
                .isEqualTo(newClubA);
        assertThat(live.get("deleted_on"))
                .as("the live article has no deleted_on")
                .isNull();

        // The soft-deleted club-A dup: lands without a ux_article_club_number 23505
        // even though it shares (club A, "A-1") with the live row.
        Map<String, Object> softDeleted = jdbc.queryForMap(
                "SELECT operating_club_id, deleted_on "
                        + "FROM t_article WHERE id = ?::uuid", softDeletedArticleA.toString());
        assertThat(UUID.fromString(softDeleted.get("operating_club_id").toString()))
                .isEqualTo(newClubA);
        assertThat(softDeleted.get("deleted_on"))
                .as("the soft-deleted dup carries its legacy deleted_on — so it does NOT "
                        + "collide with the live (club, article_number) on the partial UNIQUE")
                .isNotNull();

        // Club B's "A-1": same article_number, different club — no cross-club collision.
        Map<String, Object> clubBArticle = jdbc.queryForMap(
                "SELECT operating_club_id FROM t_article WHERE id = ?::uuid", articleB.toString());
        assertThat(UUID.fromString(clubBArticle.get("operating_club_id").toString()))
                .as("club B's article is tenant-scoped on club B")
                .isEqualTo(newClubB);

        // Tenant isolation: club A owns exactly its 2 articles; club B exactly 1.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM t_article WHERE operating_club_id = ?::uuid",
                Integer.class, newClubA.toString()))
                .as("club A owns exactly its 2 migrated articles (tenant isolation)")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM t_article WHERE operating_club_id = ?::uuid",
                Integer.class, newClubB.toString()))
                .as("club B owns exactly its 1 migrated article")
                .isEqualTo(1);
    }

    private byte[] clubNdjson(UUID legacyClubId, String clubKey, String clubname)
            throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyClubId.toString());
        row.put("clubname", clubname);
        row.put("club_key", clubKey);
        row.put("address", "Addr");
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

    /** NDJSON shaped exactly as {@code ArticleMapper.writeNdjson}. */
    private byte[] articleNdjson(UUID legacyId, UUID legacyClubId, String articleNumber,
            String articleName, boolean softDeleted) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        row.put("article_number", articleNumber);
        row.put("article_name", articleName);
        row.putNull("article_info");
        row.putNull("description");
        row.put("is_active", !softDeleted);
        String createdInstant = Instant.parse("2024-01-01T12:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        if (softDeleted) {
            row.put("deleted_on", Instant.parse("2024-03-01T12:00:00Z").toString());
            row.put("deleted_by_user_id", actorUserId.toString());
        } else {
            row.putNull("deleted_on");
            row.putNull("deleted_by_user_id");
        }
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
        return java.util.Base64.getDecoder().decode(body);
    }
}
