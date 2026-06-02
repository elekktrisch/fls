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

/**
 * J-0b T-10 — real-producer round-trip gate for the fan-out tar-entry ordering.
 *
 * <p>Unlike {@link LocationMigrationRoundTripIT}, which hand-orders the tar
 * entries via {@code MigrationBundleTestFactory.buildBundleWithEntries} (it
 * interleaves {@code legacy_id_map/LOCATION.pgcopy} BETWEEN {@code LOCATION.ndjson}
 * and {@code INOUTBOUND_POINT.ndjson} — an order the real producer never emits),
 * this IT assembles the FULL_PORT slice through the <strong>real
 * {@link BundleWriter#assembleTarGz}</strong> and ingests its actual output
 * through the real server ingest pipeline.
 *
 * <p>The bug it guards (gap-hunter, PR #198 gate): {@code assembleTarGz} used to
 * emit ALL entity NDJSON entries first, then ALL {@code legacy_id_map/*.pgcopy}.
 * The server drains tar entries single-pass in arrival order and resolves a
 * fan-out child's {@code (legacy_guid, club_id)} FK against
 * {@code legacy_id_map_location} DURING the child's NDJSON ingest — so with the
 * old order {@code LOCATION.pgcopy} landed AFTER {@code INOUTBOUND_POINT.ndjson},
 * the composite map was empty, and the child FK resolve failed closed with
 * {@code BUNDLE_CROSS_TENANT_FK_LEAK} on every real bundle. The fix emits all
 * pgcopy maps before the NDJSON streams; this IT fails red if that ever
 * regresses.
 *
 * <p>The producer emits {@code legacy_id_map} entries only for FULL_PORT
 * entities EXCEPT CLUB: {@code legacy_id_map_club} is orchestrator-owned
 * ({@code seedClubLegacyIdMap} maps the legacy club guid to the provisioned
 * {@code t_club.id}), so a producer-emitted CLUB identity pgcopy would collide
 * on {@code legacy_id_map_club_pkey} (J-0c T-01,
 * {@link EntityType#idMapSeededFromProvisioning()}). This IT drives a real CLUB
 * stream through {@link BundleWriter#assembleTarGz} to prove that collision is
 * gone — the CLUB NDJSON upserts onto the provisioned row, no CLUB pgcopy is
 * emitted. The SYSTEM_GLOBAL maps (COUNTRY / CLUB_STATE) the ingest also
 * requires are not part of the producer's tar today, so they are spliced in
 * right after {@code manifest.json} — leaving the real producer's
 * FULL_PORT-pgcopy-then-NDJSON relative order (the thing under test) untouched.
 */
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

    @BeforeEach
    void seedActor() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        legacyCountryId = UUID.randomUUID();
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

        // NDJSON temp files shaped EXACTLY as the production mappers emit (the
        // same row builders LocationMigrationRoundTripIT uses), fed into the REAL
        // BundleWriter so it computes the pgcopy maps + tar entry order itself.
        //
        // CLUB drives through the real producer too (J-0c T-01): the real
        // alpenflight-export bundle DOES contain a CLUB stream, and the producer
        // must NOT emit a legacy_id_map/CLUB.pgcopy — legacy_id_map_club is
        // orchestrator-owned (seedClubLegacyIdMap maps legacy guid -> the
        // provisioned t_club.id), so a producer CLUB identity pgcopy would
        // collide on legacy_id_map_club_pkey (23505). This IT fails red if that
        // collision is reintroduced and proves the CLUB NDJSON upserts onto the
        // provisioned row. LOCATION + INOUTBOUND_POINT remain the FAN_OUT slice
        // the original ordering bug lived on.
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

        // The REAL producer assembles the tar: manifest, then every FULL_PORT
        // pgcopy id-map (CLUB excluded — orchestrator-owned), then every entity
        // NDJSON (the J-0b T-10 order). CLUB leads the NDJSON streams as the
        // tenant root.
        Path producerTarGz = workDir.resolve("real-producer-bundle.tar.gz");
        BundleWriter writer = new BundleWriter(/* reader */ null, workDir, false);
        writer.assembleTarGz(manifestBytes, List.of(clubStream, locationStream, iopStream),
                producerTarGz);

        // Splice the SYSTEM_GLOBAL maps (not produced by assembleTarGz today) in
        // right after manifest.json, preserving the producer's FULL_PORT-pgcopy-
        // then-NDJSON relative order — the ordering under test.
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

        // The real CLUB stream upserted onto the provisioned t_club (no pgcopy
        // collision): the legacy clubname overlaid the provisioned row, keyed by
        // the orchestrator-seeded legacy_id_map_club -> provisioned id (J-0c T-01).
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

        // Fan-out + reference-FK resolve survived the real producer ordering.
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

        // The exact invariant the producer-ordering bug broke: each fanned-out
        // child IOP resolves to the parent replica in ITS OWN club.
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

    /**
     * J-0c T-15 regression guard — the producer resolves an UNRESOLVED legacy
     * Country GUID (and synthetic club-state) to the new-stack SEED PKs entirely
     * via the real {@link BundleWriter}, with NO hand-spliced
     * {@code legacy_id_map} entries. Unlike the test above (which pre-resolves
     * SEED_COUNTRY_CH into the manifest + hand-builds the COUNTRY pgcopy), here:
     *
     * <ul>
     *   <li>the CLUB NDJSON carries the RAW legacy Country GUID + the synthetic
     *       club-state UUID (exactly what {@code ClubMapper.writeNdjson} emits);</li>
     *   <li>a COUNTRY NDJSON ({@code legacy_guid=GUID, iso2_code='CH'}) and a
     *       CLUB_STATE NDJSON ({@code legacy_guid=synthetic, code='ACTIVE'}) flow
     *       through {@link BundleWriter#assembleTarGz}, which derives the
     *       {@code legacy_id_map/COUNTRY.pgcopy} + {@code CLUB_STATE.pgcopy}
     *       seed maps from those streams via the same
     *       {@code SeedReferenceUuids} derivation the manifest uses;</li>
     *   <li>the manifest's {@code ClubDeclaration} carries the resolved SEED PKs
     *       (what {@code ManifestBuilder} now produces) so provisioning inserts a
     *       valid {@code fk_club_country_id} / {@code fk_club_club_state_id}.</li>
     * </ul>
     *
     * Pre-T-15 this 500'd: provisioning inserted the raw legacy Country GUID into
     * {@code t_club.country_id}, FK-violating {@code fk_club_country_id}. Asserts
     * the migrated club resolves to {@code SEED_COUNTRY_CH} /
     * {@code SEED_CLUB_STATE_ACTIVE}.
     */
    @Test
    void real_producer_resolves_legacy_country_guid_to_seed_pk_through_provisioning_and_ndjson()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubId = UUID.randomUUID();
        String key = testClubKey + "G";
        // The manifest carries the RESOLVED seed PKs — exactly what ManifestBuilder
        // computes from the legacy GUID/INT (legacyCountryId -> ISO2 'CH' -> seed,
        // synthetic 1 -> code ACTIVE -> seed).
        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "T15 Club", testClubSlug + "-g", key, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy(),
                EntityType.CLUB, fullPortPolicy());

        // COUNTRY + CLUB_STATE NDJSON carry the natural key the producer resolves
        // the seed map from; the CLUB NDJSON carries the RAW legacy refs.
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

        // The REAL producer assembles the tar AND derives the COUNTRY/CLUB_STATE
        // seed id-maps from their NDJSON — no hand-spliced legacy_id_map here.
        Path producerTarGz = workDir.resolve("t15-real-producer-bundle.tar.gz");
        BundleWriter writer = new BundleWriter(/* reader */ null, workDir, false);
        writer.assembleTarGz(manifestBytes,
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

    /** NDJSON shaped exactly as {@code CountryMapper.writeNdjson}. */
    private byte[] countryNdjson(UUID legacyCountryGuid, String iso2) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyCountryGuid.toString());
        row.put("iso2_code", iso2);
        return ndjsonLine(row);
    }

    /** NDJSON shaped exactly as {@code ClubStateMapper.writeNdjson}. */
    private byte[] clubStateNdjson(UUID syntheticLegacyGuid, String code) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", syntheticLegacyGuid.toString());
        row.put("code", code);
        return ndjsonLine(row);
    }

    /** Write {@code payload} to a temp NDJSON file + wrap as a producer stream result. */
    private EntityStreamResult ndjsonStream(EntityType type, byte[] payload, long rows)
            throws IOException {
        Path file = Files.createTempFile(workDir, type.name() + "-", ".ndjson");
        Files.write(file, payload);
        return new EntityStreamResult(type, file, rows, "sha-not-asserted");
    }

    /**
     * Re-tar a producer tar.gz with the supplied entries spliced in immediately
     * after {@code manifest.json}, every other entry kept in its original order.
     */
    private static byte[] spliceAfterManifest(byte[] tarGz, Map<String, byte[]> afterManifest)
            throws IOException {
        // Preserve original order; a LinkedHashMap is enough — no duplicate
        // entry names in a producer tar.
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

    /** NDJSON shaped exactly as {@code ClubMapper.writeNdjson}. */
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
