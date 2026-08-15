package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migration.bundle.identity.ClubMapper;
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
class LocationRealProducerRoundTripIT extends PostgresIntegrationTest {

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

    private static final LegacyJdbcReader NO_LEGACY_JDBC_READER = null;

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
    private UUID crossTenantLegacyPersonId;

    @BeforeEach
    void seedActor() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        legacyCountryId = UUID.randomUUID();
        crossTenantLegacyPersonId = UUID.randomUUID();
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "RLP-" + tag;
        testClubSlug = "rlp-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "rlp-it-" + userSub, "Real-Producer IT",
                "rlp-" + userSub + "@example.com",
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
        jdbc.update("DELETE FROM t_flight_type WHERE operating_club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_member_state WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        jdbc.update("DELETE FROM t_club WHERE deployment_id IN "
                + "(SELECT id FROM t_deployment WHERE owner_keycloak_sub = ?::uuid)", userSub.toString());
        jdbc.update("DELETE FROM t_deployment WHERE owner_keycloak_sub = ?::uuid", userSub.toString());
        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", userId.toString());
        jdbc.update("DELETE FROM t_person WHERE id = ?::uuid", crossTenantLegacyPersonId.toString());
    }

    @Test
    void real_producer_bundle_orders_pgcopy_before_ndjson_so_child_resolves_to_own_club() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubIdA = UUID.randomUUID();
        UUID legacyClubIdB = UUID.randomUUID();
        String keyA = testClubKey + "A";
        String keyB = testClubKey + "B";
        BundleManifest.ClubDeclaration clubA = new BundleManifest.ClubDeclaration(
                legacyClubIdA, "RLP Club A", testClubSlug + "-a", keyA, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        BundleManifest.ClubDeclaration clubB = new BundleManifest.ClubDeclaration(
                legacyClubIdB, "RLP Club B", testClubSlug + "-b", keyB, false,
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

        EntityStreamResult clubStream = ndjsonStream(EntityType.CLUB, concat(
                clubNdjson(legacyClubIdA, keyA, "RLP Club A Legacy", "Addr A"),
                clubNdjson(legacyClubIdB, keyB, "RLP Club B Legacy", "Addr B")), 2);
        EntityStreamResult locationStream = ndjsonStream(EntityType.LOCATION, concat(
                locationNdjson(sharedLocationId, legacyClubIdA, legacyCountryId, "LSZH"),
                locationNdjson(sharedLocationId, legacyClubIdB, legacyCountryId, "LSZH")), 2);
        EntityStreamResult iopStream = ndjsonStream(EntityType.INOUTBOUND_POINT, concat(
                inoutboundPointNdjson(childIopIdA, sharedLocationId, legacyClubIdA),
                inoutboundPointNdjson(childIopIdB, sharedLocationId, legacyClubIdB)), 2);

        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                "Real-Producer IT Deployment",
                List.of(clubA, clubB),
                null,
                entityPolicies,
                unmappedReasonFor(entityPolicies));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);

        Path producerTarGz = workDir.resolve("real-producer-bundle.tar.gz");
        BundleWriter realProducer = new BundleWriter(NO_LEGACY_JDBC_READER, workDir, false);
        realProducer.assembleTarGz(manifestBytes, List.of(clubStream, locationStream, iopStream),
                producerTarGz);

        Map<String, byte[]> systemGlobalMaps = new LinkedHashMap<>();
        systemGlobalMaps.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        systemGlobalMaps.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        byte[] tarGzPlaintext = spliceAfterManifest(
                Files.readAllBytes(producerTarGz), systemGlobalMaps);

        byte[] bundle = MigrationBundleTestFactory.encryptTarGzPlaintext(
                cipher, uploadId, publicKeyDer, tarGzPlaintext);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("real-producer bundle ingest failed (would be 500 "
                        + "BUNDLE_CROSS_TENANT_FK_LEAK if assembleTarGz regressed to "
                        + "NDJSON-before-pgcopy); body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        Map<String, UUID> clubIdByKey = clubIdsByKey(deploymentId);
        UUID newClubA = clubIdByKey.get(keyA);
        UUID newClubB = clubIdByKey.get(keyB);

        assertThat(jdbc.queryForObject(
                "SELECT clubname FROM t_club WHERE id = ?::uuid", String.class,
                newClubA.toString()))
                .as("CLUB NDJSON reconciled onto the provisioned club via the "
                        + "orchestrator-seeded id-map (no legacy_id_map_club collision)")
                .isEqualTo("RLP Club A Legacy");
        assertThat(jdbc.queryForObject(
                "SELECT clubname FROM t_club WHERE id = ?::uuid", String.class,
                newClubB.toString()))
                .isEqualTo("RLP Club B Legacy");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, club_id, location_type_id, elevation_unit_type_id, "
                        + "runway_length_unit_type_id FROM t_location "
                        + "WHERE club_id IN (?::uuid, ?::uuid) ORDER BY club_id",
                newClubA.toString(), newClubB.toString());
        assertThat(rows)
                .as("shared legacy Location fans out to one row per club")
                .hasSize(2);
        assertThat(rows.stream().map(r -> r.get("id").toString()).distinct().count())
                .as("the two replicas have DISTINCT ids")
                .isEqualTo(2L);
        for (Map<String, Object> r : rows) {
            assertThat(UUID.fromString(r.get("location_type_id").toString()))
                    .isEqualTo(SEED_LOCATION_TYPE_GRASS);
            assertThat(UUID.fromString(r.get("elevation_unit_type_id").toString()))
                    .isEqualTo(SEED_ELEVATION_UNIT_FEET);
            assertThat(UUID.fromString(r.get("runway_length_unit_type_id").toString()))
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
                .as("each fan-out club's child IOP attaches to a fanned-out parent")
                .hasSize(2);

        Map<String, UUID> iopLocationIdByClub = new LinkedHashMap<>();
        for (Map<String, Object> iop : iops) {
            iopLocationIdByClub.put(iop.get("club_id").toString(),
                    UUID.fromString(iop.get("location_id").toString()));
        }
        assertThat(iopLocationIdByClub.get(newClubA.toString()))
                .as("club-aware FK: club-A child resolves to club A's OWN replica "
                        + "(needs LOCATION.pgcopy drained before INOUTBOUND_POINT.ndjson)")
                .isEqualTo(replicaInClubA);
        assertThat(iopLocationIdByClub.get(newClubB.toString()))
                .as("club-aware FK: club-B child resolves to club B's OWN replica")
                .isEqualTo(replicaInClubB);
    }

    @Test
    void real_producer_resolves_legacy_country_guid_to_seed_pk_through_provisioning_and_ndjson()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        String key = testClubKey + "G";
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "T15 Club", testClubSlug + "-g", key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy(),
                EntityType.CLUB, fullPortPolicy());

        EntityStreamResult countryStream = ndjsonStream(EntityType.COUNTRY,
                countryNdjson(legacyCountryId, "CH"), 1);
        EntityStreamResult clubStateStream = ndjsonStream(EntityType.CLUB_STATE,
                clubStateNdjson(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, "ACTIVE"), 1);
        EntityStreamResult clubStream = ndjsonStream(EntityType.CLUB,
                clubNdjson(legacyClubId, key, "T15 Club Legacy", "Addr"), 1);

        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                "T15 Resolve Deployment",
                List.of(club),
                null,
                entityPolicies,
                unmappedReasonFor(entityPolicies));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);

        Path producerTarGz = workDir.resolve("t15-real-producer-bundle.tar.gz");
        BundleWriter realProducer = new BundleWriter(NO_LEGACY_JDBC_READER, workDir, false);
        realProducer.assembleTarGz(manifestBytes,
                List.of(countryStream, clubStateStream, clubStream), producerTarGz);

        byte[] bundle = MigrationBundleTestFactory.encryptTarGzPlaintext(
                cipher, uploadId, publicKeyDer, Files.readAllBytes(producerTarGz));

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("real-producer bundle with an UNRESOLVED legacy Country GUID must "
                        + "ingest 200 (pre-T-15 this 500'd with fk_club_country_id "
                        + "violation at provisioning); body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        UUID newClub = clubIdsByKey(deploymentId).get(key);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT country_id, club_state_id FROM t_club WHERE id = ?::uuid",
                newClub.toString());
        assertThat(UUID.fromString(row.get("country_id").toString()))
                .as("CLUB.country_id resolved the legacy GUID to the new-stack seed PK")
                .isEqualTo(SEED_COUNTRY_CH);
        assertThat(UUID.fromString(row.get("club_state_id").toString()))
                .as("CLUB.club_state_id resolved the synthetic INT to the seed PK")
                .isEqualTo(SEED_CLUB_STATE_ACTIVE);
    }

    @Test
    void real_producer_resolves_user_language_id_synthetic_to_seed_language_pk()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        UUID legacyUserId = UUID.randomUUID();
        String key = testClubKey + "L";
        UUID syntheticLanguageGuid = new UUID(0L, 1L);

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "T20 Club", testClubSlug + "-l", key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy(),
                EntityType.LANGUAGE, systemGlobalPolicy(),
                EntityType.CLUB, fullPortPolicy(),
                EntityType.USER, fullPortPolicy());

        EntityStreamResult countryStream = ndjsonStream(EntityType.COUNTRY,
                countryNdjson(legacyCountryId, "CH"), 1);
        EntityStreamResult clubStateStream = ndjsonStream(EntityType.CLUB_STATE,
                clubStateNdjson(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, "ACTIVE"), 1);
        EntityStreamResult languageStream = ndjsonStream(EntityType.LANGUAGE,
                languageNdjson(syntheticLanguageGuid, "de"), 1);
        EntityStreamResult clubStream = ndjsonStream(EntityType.CLUB,
                clubNdjson(legacyClubId, key, "T20 Club Legacy", "Addr"), 1);
        EntityStreamResult userStream = ndjsonStream(EntityType.USER,
                userNdjson(legacyUserId, legacyClubId, "t20-user", syntheticLanguageGuid), 1);

        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                "T20 Language Resolve Deployment",
                List.of(club),
                null,
                entityPolicies,
                unmappedReasonFor(entityPolicies));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);

        Path producerTarGz = workDir.resolve("t20-language-bundle.tar.gz");
        BundleWriter realProducer = new BundleWriter(NO_LEGACY_JDBC_READER, workDir, false);
        realProducer.assembleTarGz(manifestBytes,
                List.of(countryStream, clubStateStream, languageStream, clubStream, userStream),
                producerTarGz);

        byte[] bundle = MigrationBundleTestFactory.encryptTarGzPlaintext(
                cipher, uploadId, publicKeyDer, Files.readAllBytes(producerTarGz));

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("a USER whose language_id is the synthetic new UUID(0,LanguageId) must "
                        + "ingest 200 (pre-T-20 this 400'd BUNDLE_CROSS_TENANT_FK_LEAK: "
                        + "legacy_id_map_LANGUAGE had no resolution); body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        UUID newClub = clubIdsByKey(deploymentId).get(key);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT language_id FROM t_user WHERE club_id = ?::uuid AND username = ?",
                newClub.toString(), "t20-user");
        assertThat(UUID.fromString(row.get("language_id").toString()))
                .as("USER.language_id resolved the synthetic legacy LanguageId to the "
                        + "new-stack t_language seed PK (de)")
                .isEqualTo(SEED_LANGUAGE_DE);
    }

    @Test
    void real_producer_emits_person_so_user_person_id_fk_resolves() throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        UUID legacyUserId = UUID.randomUUID();
        String key = testClubKey + "P";
        UUID syntheticLanguageGuid = new UUID(0L, 1L);

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "T21 Club", testClubSlug + "-p", key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy(),
                EntityType.LANGUAGE, systemGlobalPolicy(),
                EntityType.CLUB, fullPortPolicy(),
                EntityType.PERSON, fullPortPolicy(),
                EntityType.USER, fullPortPolicy());

        EntityStreamResult countryStream = ndjsonStream(EntityType.COUNTRY,
                countryNdjson(legacyCountryId, "CH"), 1);
        EntityStreamResult clubStateStream = ndjsonStream(EntityType.CLUB_STATE,
                clubStateNdjson(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, "ACTIVE"), 1);
        EntityStreamResult languageStream = ndjsonStream(EntityType.LANGUAGE,
                languageNdjson(syntheticLanguageGuid, "de"), 1);
        EntityStreamResult clubStream = ndjsonStream(EntityType.CLUB,
                clubNdjson(legacyClubId, key, "T21 Club Legacy", "Addr"), 1);
        EntityStreamResult personStream = ndjsonStream(EntityType.PERSON,
                personNdjson(crossTenantLegacyPersonId, legacyCountryId, "Otheradmin", "Other"), 1);
        EntityStreamResult userStream = ndjsonStream(EntityType.USER,
                userNdjson(legacyUserId, legacyClubId, "t21-user",
                        syntheticLanguageGuid, crossTenantLegacyPersonId), 1);

        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                "T21 Person Resolve Deployment",
                List.of(club),
                null,
                entityPolicies,
                unmappedReasonFor(entityPolicies));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);

        Path producerTarGz = workDir.resolve("t21-person-bundle.tar.gz");
        BundleWriter realProducer = new BundleWriter(NO_LEGACY_JDBC_READER, workDir, false);
        realProducer.assembleTarGz(manifestBytes,
                List.of(countryStream, clubStateStream, languageStream, clubStream,
                        personStream, userStream),
                producerTarGz);

        byte[] bundle = MigrationBundleTestFactory.encryptTarGzPlaintext(
                cipher, uploadId, publicKeyDer, Files.readAllBytes(producerTarGz));

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("a USER with a non-null person_id must ingest 200 (pre-T-21 the "
                        + "USER INSERT 500'd: t_person was empty because PERSON had no "
                        + "MapperLegacyBindings binding, so fk_user_person_id dangled); "
                        + "body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        UUID newClub = clubIdsByKey(deploymentId).get(key);

        Map<String, Object> person = jdbc.queryForMap(
                "SELECT id, lastname, firstname, country_id FROM t_person WHERE id = ?::uuid",
                crossTenantLegacyPersonId.toString());
        assertThat(person.get("lastname")).isEqualTo("Otheradmin");
        assertThat(person.get("firstname")).isEqualTo("Other");
        assertThat(UUID.fromString(person.get("country_id").toString()))
                .as("PERSON.country_id resolved the legacy Country GUID to the seed PK "
                        + "via the COUNTRY id-map (the FK PersonMapper declares)")
                .isEqualTo(SEED_COUNTRY_CH);

        Map<String, Object> userRow = jdbc.queryForMap(
                "SELECT person_id FROM t_user WHERE club_id = ?::uuid AND username = ?",
                newClub.toString(), "t21-user");
        assertThat(UUID.fromString(userRow.get("person_id").toString()))
                .as("USER.person_id resolved to the migrated t_person row")
                .isEqualTo(crossTenantLegacyPersonId);
    }

    @Test
    void real_producer_migrates_legacy_system_club_state_zero_to_active_through_chain()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        String key = testClubKey + "S";
        UUID systemClubStateGuid =
                UUID.fromString(Coercions.legacyIntIdToUuidString(0));

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "System-Verein", testClubSlug + "-s", key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy(),
                EntityType.CLUB, fullPortPolicy());

        EntityStreamResult countryStream = ndjsonStream(EntityType.COUNTRY,
                countryNdjson(legacyCountryId, "CH"), 1);
        EntityStreamResult clubStateStream = ndjsonStream(EntityType.CLUB_STATE,
                clubStateNdjson(systemClubStateGuid, "ACTIVE"), 1);
        EntityStreamResult clubStream = ndjsonStream(EntityType.CLUB,
                clubNdjson(legacyClubId, key, "System-Verein Legacy", "Addr",
                        systemClubStateGuid), 1);

        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                "T16 System Club Deployment",
                List.of(club),
                null,
                entityPolicies,
                unmappedReasonFor(entityPolicies));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);

        Path producerTarGz = workDir.resolve("t16-system-club-bundle.tar.gz");
        BundleWriter realProducer = new BundleWriter(NO_LEGACY_JDBC_READER, workDir, false);
        realProducer.assembleTarGz(manifestBytes,
                List.of(countryStream, clubStateStream, clubStream), producerTarGz);

        byte[] bundle = MigrationBundleTestFactory.encryptTarGzPlaintext(
                cipher, uploadId, publicKeyDer, Files.readAllBytes(producerTarGz));

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("a legacy System club (ClubStateId=0) must ingest 200 (pre-T-16 the "
                        + "export aborted: ClubStateId 0 had no V2 destination); body=%s",
                        res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        UUID newClub = clubIdsByKey(deploymentId).get(key);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT club_state_id FROM t_club WHERE id = ?::uuid", newClub.toString());
        assertThat(UUID.fromString(row.get("club_state_id").toString()))
                .as("legacy System club (ClubStateId=0) resolves to the ACTIVE seed PK")
                .isEqualTo(SEED_CLUB_STATE_ACTIVE);
    }

    @Test
    void real_producer_coalesces_null_modified_on_to_created_on_for_never_modified_club()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        String key = testClubKey + "M";
        Instant createdOn = Instant.parse("2017-03-09T08:30:00Z");

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "Never-Modified Club", testClubSlug + "-m", key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy(),
                EntityType.CLUB, fullPortPolicy());

        EntityStreamResult countryStream = ndjsonStream(EntityType.COUNTRY,
                countryNdjson(legacyCountryId, "CH"), 1);
        EntityStreamResult clubStateStream = ndjsonStream(EntityType.CLUB_STATE,
                clubStateNdjson(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, "ACTIVE"), 1);
        EntityStreamResult clubStream = ndjsonStream(EntityType.CLUB,
                clubNdjsonViaRealMapper(legacyClubId, key, "Never-Modified Club Legacy",
                        createdOn, null), 1);

        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                "T19 Never-Modified Deployment",
                List.of(club),
                null,
                entityPolicies,
                unmappedReasonFor(entityPolicies));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);

        Path producerTarGz = workDir.resolve("t19-never-modified-bundle.tar.gz");
        BundleWriter realProducer = new BundleWriter(NO_LEGACY_JDBC_READER, workDir, false);
        realProducer.assembleTarGz(manifestBytes,
                List.of(countryStream, clubStateStream, clubStream), producerTarGz);

        byte[] bundle = MigrationBundleTestFactory.encryptTarGzPlaintext(
                cipher, uploadId, publicKeyDer, Files.readAllBytes(producerTarGz));

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("a never-modified legacy Club (ModifiedOn NULL) must ingest 200 "
                        + "(pre-T-19 the producer emitted modified_on=null and the CLUB "
                        + "INSERT 23502'd on t_club.modified_on NOT NULL); body=%s",
                        res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        UUID newClub = clubIdsByKey(deploymentId).get(key);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT created_on, modified_on FROM t_club WHERE id = ?::uuid",
                newClub.toString());
        assertThat(((java.sql.Timestamp) row.get("modified_on")).toInstant())
                .as("never-modified Club's modified_on coalesced to its created_on")
                .isEqualTo(createdOn);
        assertThat(((java.sql.Timestamp) row.get("modified_on")).toInstant())
                .as("modified_on equals created_on for a never-modified row")
                .isEqualTo(((java.sql.Timestamp) row.get("created_on")).toInstant());
    }

    @Test
    void real_producer_carries_registration_operator_recipients_in_the_form_the_mailer_splits()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        String key = testClubKey + "R";
        Instant createdOn = Instant.parse("2019-07-04T06:15:00Z");

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "Organiser Club", testClubSlug + "-r", key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy(),
                EntityType.CLUB, fullPortPolicy());

        EntityStreamResult countryStream = ndjsonStream(EntityType.COUNTRY,
                countryNdjson(legacyCountryId, "CH"), 1);
        EntityStreamResult clubStateStream = ndjsonStream(EntityType.CLUB_STATE,
                clubStateNdjson(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, "ACTIVE"), 1);
        EntityStreamResult clubStream = ndjsonStream(EntityType.CLUB,
                clubNdjsonViaRealMapper(legacyClubId, key, "Organiser Club Legacy",
                        createdOn, createdOn,
                        "Trial@Club.CH; second@Club.ch  third@club.ch",
                        "keine;  scenic@club.ch"), 1);

        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                "Organiser Recipients Deployment",
                List.of(club),
                null,
                entityPolicies,
                unmappedReasonFor(entityPolicies));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);

        Path producerTarGz = workDir.resolve("organiser-recipients-bundle.tar.gz");
        BundleWriter realProducer = new BundleWriter(NO_LEGACY_JDBC_READER, workDir, false);
        realProducer.assembleTarGz(manifestBytes,
                List.of(countryStream, clubStateStream, clubStream), producerTarGz);

        byte[] bundle = MigrationBundleTestFactory.encryptTarGzPlaintext(
                cipher, uploadId, publicKeyDer, Files.readAllBytes(producerTarGz));

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("a club carrying registration-operator recipients must ingest 200; body=%s",
                        res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        UUID newClub = clubIdsByKey(deploymentId).get(key);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT send_trial_flight_registration_operator_email, "
                        + "send_passenger_flight_registration_operator_email "
                        + "FROM t_club WHERE id = ?::uuid",
                newClub.toString());
        assertThat(row.get("send_trial_flight_registration_operator_email"))
                .as("a legacy semicolon/whitespace recipient list arrives comma-joined and "
                        + "lower-cased, so the mailer's comma split yields all three organisers")
                .isEqualTo("trial@club.ch,second@club.ch,third@club.ch");
        assertThat(row.get("send_passenger_flight_registration_operator_email"))
                .as("an address legacy never validated ports verbatim alongside the valid one "
                        + "rather than being silently discarded at migration")
                .isEqualTo("keine,scenic@club.ch");
    }

    private byte[] clubNdjsonViaRealMapper(
            UUID legacyClubId, String clubKey, String clubname,
            Instant createdOn, Instant modifiedOn) throws Exception {
        return clubNdjsonViaRealMapper(legacyClubId, clubKey, clubname, createdOn, modifiedOn,
                null, null);
    }

    private byte[] clubNdjsonViaRealMapper(
            UUID legacyClubId, String clubKey, String clubname,
            Instant createdOn, Instant modifiedOn,
            String trialRegistrationOperatorEmailTo,
            String passengerRegistrationOperatorEmailTo) throws Exception {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("ClubId", legacyClubId.toString());
        legacy.put("Clubname", clubname);
        legacy.put("ClubKey", clubKey);
        legacy.put("CountryId", legacyCountryId.toString());
        legacy.put("ClubStateId", 1);
        legacy.put("SendTrialFlightRegistrationOperatorEmailTo",
                trialRegistrationOperatorEmailTo);
        legacy.put("SendPassengerFlightRegistrationOperatorEmailTo",
                passengerRegistrationOperatorEmailTo);
        legacy.put("RunDeliveryCreationJob", false);
        legacy.put("RunDeliveryMailExportJob", false);
        legacy.put("IsClubMemberNumberReadonly", false);
        legacy.put("CreatedOn", java.sql.Timestamp.from(createdOn));
        legacy.put("ModifiedOn",
                modifiedOn == null ? null : java.sql.Timestamp.from(modifiedOn));

        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        org.mockito.Mockito.lenient().when(rs.getString(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    Object v = legacy.get(inv.<String>getArgument(0));
                    return v == null ? null : v.toString();
                });
        org.mockito.Mockito.lenient().when(rs.getInt(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    Object v = legacy.get(inv.<String>getArgument(0));
                    return v instanceof Number n ? n.intValue() : 0;
                });
        org.mockito.Mockito.lenient().when(rs.getBoolean(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    Object v = legacy.get(inv.<String>getArgument(0));
                    return v instanceof Boolean b && b;
                });
        org.mockito.Mockito.lenient().when(rs.getTimestamp(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> legacy.get(inv.<String>getArgument(0)));

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator gen = JSON.getFactory().createGenerator(sink)) {
            new ClubMapper().writeNdjson(rs, gen);
        }
        sink.write('\n');
        return sink.toByteArray();
    }

    private byte[] countryNdjson(UUID legacyCountryGuid, String iso2) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyCountryGuid.toString());
        row.put("iso2_code", iso2);
        return ndjsonLine(row);
    }

    private byte[] clubStateNdjson(UUID syntheticLegacyGuid, String code) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", syntheticLegacyGuid.toString());
        row.put("code", code);
        return ndjsonLine(row);
    }

    private byte[] languageNdjson(UUID syntheticLegacyGuid, String code) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", syntheticLegacyGuid.toString());
        row.put("code", code);
        return ndjsonLine(row);
    }

    private byte[] userNdjson(UUID legacyUserId, UUID legacyClubId, String username,
                              UUID syntheticLanguageGuid) throws IOException {
        return userNdjson(legacyUserId, legacyClubId, username, syntheticLanguageGuid, null);
    }

    private byte[] userNdjson(UUID legacyUserId, UUID legacyClubId, String username,
                              UUID syntheticLanguageGuid, UUID legacyPersonId) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyUserId.toString());
        row.put("club_id", legacyClubId.toString());
        row.put("username", username);
        row.put("friendly_name", "T20 User");
        if (legacyPersonId == null) {
            row.putNull("person_id");
        } else {
            row.put("person_id", legacyPersonId.toString());
        }
        row.put("notification_email", username + "@example.com");
        row.putNull("phone_number");
        row.putNull("remarks");
        row.put("language_id", syntheticLanguageGuid.toString());
        row.putNull("keycloak_sub");
        String createdInstant = Instant.parse("2021-05-01T00:00:00Z").toString();
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
        if (legacyCountryGuid == null) {
            row.putNull("country_id");
        } else {
            row.put("country_id", legacyCountryGuid.toString());
        }
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

    private byte[] clubNdjson(UUID legacyClubId, String clubKey, String clubname, String address)
            throws IOException {
        return clubNdjson(legacyClubId, clubKey, clubname, address,
                LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC);
    }

    private byte[] clubNdjson(UUID legacyClubId, String clubKey, String clubname, String address,
            UUID clubStateLegacyGuid)
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
        row.put("club_state_id", clubStateLegacyGuid.toString());
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
        return Base64.getDecoder().decode(body);
    }
}
