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
 * J-8 T-10 — the AccountingRuleFilter migrate round-trip proof: a legacy→
 * export→ingest round-trip against the REAL server ingest pipeline, so the
 * {@link ch.alpenflight.migrations.application.ReferenceLookupResolver} runs live
 * (the T-09 finding — without {@code referenceLookups()} the
 * {@code filter_type_id} synthetic UUID reaches the INSERT verbatim and
 * FK-violates {@code fk_arf_filter_type_id} for EVERY type). Mirrors
 * {@link PlanningDayMigrationRoundTripIT}.
 *
 * <p>The mapper + schema were authored at the V4 baseline but had NO
 * {@code MapperLegacyBindings} producer entry, so the producer SELECT + real
 * round-trip had never run ({@code verify_infra_is_run_not_just_authored} /
 * {@code project_synth_bundle_doesnt_validate_producer_select}). T-10 wires the
 * binding; this IT proves it end-to-end.
 *
 * <p>ACCOUNTING_RULE_FILTER is <strong>fan-out NO</strong> (per-club config;
 * legacy_guid preserved verbatim as the destination {@code id}), tenant-scoped on
 * {@code operating_club_id}. The NDJSON is shaped exactly as the mapper emits
 * over its bound producer SELECT.
 *
 * <p>Asserts the load-bearing migration invariants:
 * <ul>
 *   <li><strong>Reference FK resolution.</strong> {@code filter_type_id}
 *       resolves through {@code referenceLookups()} against the V4/V42-seeded
 *       {@code t_accounting_rule_filter_type.legacy_int_id} — including a type-5
 *       (DO_NOT_INVOICE) row that V42 seeded; {@code accounting_unit_type_id}
 *       resolves against {@code t_accounting_unit_type}.</li>
 *   <li><strong>Target scalars + descriptive text.</strong> {@code article_target}
 *       / {@code recipient_target} carry the bare scalars; the descriptive
 *       {@code deliveryLineText} / {@code recipientName} round-trip in
 *       {@code filter_config} (the AC "reload round-trips every field").</li>
 *   <li><strong>Match-lists parse as JSON, not comma.</strong> A migrated
 *       {@code filter_config} match-list carries the distinct elements of a JSON
 *       array, never a single corrupted comma-joined element.</li>
 *   <li><strong>Collision lands without 23505.</strong> Two rows in ONE club with
 *       DISTINCT (producer-renumbered) {@code sort_indicator} both INSERT — no
 *       {@code ux_arf_club_sort_partial} violation.</li>
 *   <li><strong>Tenant isolation.</strong> Every filter carries the owning club's
 *       {@code operating_club_id}; the other club has none.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({JwtTestFixture.class, MockKeycloakDirectoryConfig.class})
@Tag("slow")
class AccountingRuleFilterMigrationRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final UUID LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC = new UUID(0L, 1L);

    // Real Flyway-seed reference PKs the ref-lookup resolver must land on.
    private static final int LEGACY_FILTER_TYPE_FLIGHT_TIME = 30;       // V4
    private static final int LEGACY_FILTER_TYPE_DO_NOT_INVOICE = 5;     // V42 (T-09)
    private static final UUID SEED_FILTER_TYPE_FLIGHT_TIME =
            UUID.fromString("019e2e15-2c00-7652-8000-000000004652");
    private static final UUID SEED_FILTER_TYPE_DO_NOT_INVOICE =
            UUID.fromString("019e2e15-2c00-7658-8000-000000004658");
    private static final int LEGACY_UNIT_TYPE_MINUTES = 10;             // V4
    private static final UUID SEED_UNIT_TYPE_MINUTES =
            UUID.fromString("019e2e15-2c00-7a38-8000-000000004a38");

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
        testClubKey = "ARF-" + tag;
        testClubSlug = "arf-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "arf-it-" + userSub, "Accounting IT",
                "arf-" + userSub + "@example.com",
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
        jdbc.update("DELETE FROM t_accounting_rule_filter WHERE operating_club_id IN ("
                + clubsByOwner + ")", userSub.toString());
        jdbc.update("DELETE FROM t_migration_run WHERE upload_id IN "
                + "(SELECT id FROM t_migration_upload WHERE user_id = ?::uuid)", userId.toString());
        jdbc.update("DELETE FROM t_migration_upload WHERE user_id = ?::uuid", userId.toString());
        jdbc.update("DELETE FROM t_user WHERE club_id IN (" + clubsByOwner + ")",
                userSub.toString());
        // Club provisioning seeds per-club reference rows (flight types, member
        // states) referencing the club — delete them before the club, else the
        // club DELETE FK-violates.
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
    void accountingRuleFilter_migrates_with_resolved_ref_types_targets_and_round_tripped_config()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubIdA = UUID.randomUUID();
        UUID legacyClubIdB = UUID.randomUUID();
        String keyA = testClubKey + "A";
        String keyB = testClubKey + "B";
        BundleManifest.ClubDeclaration clubA = new BundleManifest.ClubDeclaration(
                legacyClubIdA, "Arf Club A", testClubSlug + "-a", keyA, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        BundleManifest.ClubDeclaration clubB = new BundleManifest.ClubDeclaration(
                legacyClubIdB, "Arf Club B", testClubSlug + "-b", keyB, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        // Two filters in club A with DISTINCT (already producer-renumbered)
        // sort_indicators 1 and 2 — both must INSERT without colliding on
        // ux_arf_club_sort_partial (the renumber the producer SELECT applies upstream).
        UUID legacyFlightTimeFilterId = UUID.randomUUID();
        // A type-5 DO_NOT_INVOICE filter — V42 seeded the type so the ref lookup resolves.
        UUID legacyDoNotInvoiceFilterId = UUID.randomUUID();
        // One filter in club B — tenant isolation.
        UUID legacyClubBFilterId = UUID.randomUUID();

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.ACCOUNTING_RULE_FILTER, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("CLUB.ndjson", concat(
                clubNdjson(legacyClubIdA, keyA, "Arf Club A Legacy"),
                clubNdjson(legacyClubIdB, keyB, "Arf Club B Legacy")));
        tarEntries.put("ACCOUNTING_RULE_FILTER.ndjson", concat(concat(
                // A FLIGHT_TIME (30) filter with an article target + minutes unit + a
                // JSON-array match-list — sort_indicator 1.
                accountingRuleFilterNdjson(legacyFlightTimeFilterId, legacyClubIdA,
                        LEGACY_FILTER_TYPE_FLIGHT_TIME, LEGACY_UNIT_TYPE_MINUTES, 1,
                        "Flight time charge", "4001",
                        null, null, List.of("HB-3170", "HB-1234")),
                // A DO_NOT_INVOICE (5) filter — the V42 seed makes the ref lookup
                // resolve; sort_indicator 2 in the SAME club (no 23505).
                accountingRuleFilterNdjson(legacyDoNotInvoiceFilterId, legacyClubIdA,
                        LEGACY_FILTER_TYPE_DO_NOT_INVOICE, null, 2,
                        null, null, null, null, List.of())),
                // Club B's filter — tenant isolation.
                accountingRuleFilterNdjson(legacyClubBFilterId, legacyClubIdB,
                        LEGACY_FILTER_TYPE_FLIGHT_TIME, null, 1,
                        null, null, "1042", "Club Treasurer", List.of())));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "AccountingRuleFilter Migrate IT Deployment",
                List.of(clubA, clubB), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("AccountingRuleFilter migrate round-trip ingest failed; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        Map<String, UUID> clubIdByKey = clubIdsByKey(deploymentId);
        UUID newClubA = clubIdByKey.get(keyA);
        UUID newClubB = clubIdByKey.get(keyB);

        // The FLIGHT_TIME filter: ref types resolved, article target scalar landed,
        // deliveryLineText round-tripped in filter_config, match-list JSON-parsed.
        Map<String, Object> flightTime = jdbc.queryForMap(
                "SELECT operating_club_id, filter_type_id, accounting_unit_type_id, "
                        + "article_target, recipient_target, sort_indicator, "
                        + "filter_config FROM t_accounting_rule_filter WHERE id = ?::uuid",
                legacyFlightTimeFilterId.toString());
        assertThat(UUID.fromString(flightTime.get("operating_club_id").toString()))
                .as("the migrated filter is tenant-scoped on club A")
                .isEqualTo(newClubA);
        assertThat(UUID.fromString(flightTime.get("filter_type_id").toString()))
                .as("filter_type_id resolves through referenceLookups() to the V4-seeded "
                        + "FLIGHT_TIME (30) PK — NOT the verbatim synthetic UUID (which would "
                        + "FK-violate fk_arf_filter_type_id)")
                .isEqualTo(SEED_FILTER_TYPE_FLIGHT_TIME);
        assertThat(UUID.fromString(flightTime.get("accounting_unit_type_id").toString()))
                .as("accounting_unit_type_id resolves to the V4-seeded MINUTES (10) PK")
                .isEqualTo(SEED_UNIT_TYPE_MINUTES);
        assertThat(flightTime.get("article_target"))
                .as("article_target carries the bare ArticleNumber scalar (JSON_VALUE-extracted)")
                .isEqualTo("4001");
        assertThat(((Number) flightTime.get("sort_indicator")).intValue()).isEqualTo(1);

        JsonNode flightTimeConfig = JSON.readTree(flightTime.get("filter_config").toString());
        assertThat(flightTimeConfig.get("deliveryLineText").asText())
                .as("deliveryLineText round-trips in filter_config (AC: reload round-trips "
                        + "every field) — the T-03/T-10 addition")
                .isEqualTo("Flight time charge");
        assertThat(flightTimeConfig.get("aircraftImmatriculations").get("matched"))
                .as("the match-list parses as a JSON array — each element distinct, NOT a "
                        + "single corrupted comma-joined string (the classic producer-SELECT gap)")
                .extracting(JsonNode::asText)
                .containsExactly("HB-3170", "HB-1234");

        // The type-5 DO_NOT_INVOICE filter: the V42-seeded type resolves, and it lands
        // in the SAME club at sort_indicator 2 — proving no 23505 on the partial UNIQUE.
        Map<String, Object> doNotInvoice = jdbc.queryForMap(
                "SELECT filter_type_id, sort_indicator FROM t_accounting_rule_filter "
                        + "WHERE id = ?::uuid", legacyDoNotInvoiceFilterId.toString());
        assertThat(UUID.fromString(doNotInvoice.get("filter_type_id").toString()))
                .as("filter_type_id resolves to the V42-seeded DO_NOT_INVOICE (5) PK — "
                        + "the T-09 missing-type that would 23503 at fanout before V42")
                .isEqualTo(SEED_FILTER_TYPE_DO_NOT_INVOICE);
        assertThat(((Number) doNotInvoice.get("sort_indicator")).intValue())
                .as("the 2nd club-A filter lands at a DISTINCT sort_indicator — no "
                        + "ux_arf_club_sort_partial 23505")
                .isEqualTo(2);

        // Club B's filter: recipient target scalar + recipientName round-trip.
        Map<String, Object> clubBFilter = jdbc.queryForMap(
                "SELECT operating_club_id, recipient_target, filter_config "
                        + "FROM t_accounting_rule_filter WHERE id = ?::uuid",
                legacyClubBFilterId.toString());
        assertThat(UUID.fromString(clubBFilter.get("operating_club_id").toString()))
                .isEqualTo(newClubB);
        assertThat(clubBFilter.get("recipient_target"))
                .as("recipient_target carries the bare member-number scalar")
                .isEqualTo("1042");
        assertThat(JSON.readTree(clubBFilter.get("filter_config").toString())
                .get("recipientName").asText())
                .as("recipientName round-trips in filter_config")
                .isEqualTo("Club Treasurer");

        // Tenant isolation: club A has exactly its 2 filters; club B exactly 1.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM t_accounting_rule_filter WHERE operating_club_id = ?::uuid",
                Integer.class, newClubA.toString()))
                .as("club A owns exactly its 2 migrated filters (tenant isolation)")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM t_accounting_rule_filter WHERE operating_club_id = ?::uuid",
                Integer.class, newClubB.toString()))
                .as("club B owns exactly its 1 migrated filter")
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

    /** NDJSON shaped exactly as {@code AccountingRuleFilterMapper.writeNdjson}. */
    private byte[] accountingRuleFilterNdjson(UUID legacyId, UUID legacyClubId,
            int filterTypeInt, Integer unitTypeInt, int sortIndicator,
            String deliveryLineText, String articleNumber,
            String memberNumber, String recipientNameForConfig,
            List<String> matchedImmatriculations) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        // Synthetic new UUID(0, legacyIntId) — resolved ingest-side by referenceLookups().
        row.put("filter_type_id", Coercions.legacyIntIdToUuidString(filterTypeInt));
        if (unitTypeInt == null) {
            row.putNull("accounting_unit_type_id");
        } else {
            row.put("accounting_unit_type_id", Coercions.legacyIntIdToUuidString(unitTypeInt));
        }
        row.put("rule_filter_name", "Filter " + sortIndicator);
        row.putNull("description");
        row.put("is_active", true);
        row.put("sort_indicator", sortIndicator);
        row.put("stop_rule_engine_when_applied", false);
        row.put("is_charged_to_club_internal", false);
        // article_target / recipient_target are the bare scalars (JSON_VALUE-extracted
        // by the producer SELECT); descriptive text rides filter_config.
        if (articleNumber == null) {
            row.putNull("article_target");
        } else {
            row.put("article_target", articleNumber);
        }
        if (memberNumber == null) {
            row.putNull("recipient_target");
        } else {
            row.put("recipient_target", memberNumber);
        }
        ObjectNode config = row.putObject("filter_config");
        config.put("isRuleForGliderFlights", true);
        config.put("isRuleForTowingFlights", false);
        config.put("isRuleForMotorFlights", false);
        config.put("noLandingTaxForGlider", false);
        config.put("noLandingTaxForTowingAircraft", false);
        config.put("noLandingTaxForAircraft", false);
        config.put("includeFlightTypeName", false);
        config.put("extendMatchingFlightTypeCodesToGliderAndTowFlight", false);
        config.put("includeThresholdText", false);
        config.putNull("thresholdText");
        config.putNull("minFlightTimeInSecondsMatchingValue");
        config.putNull("maxFlightTimeInSecondsMatchingValue");
        config.putNull("minEngineTimeInSecondsMatchingValue");
        config.putNull("maxEngineTimeInSecondsMatchingValue");
        matchListNode(config, "aircraftImmatriculations", matchedImmatriculations);
        matchListNode(config, "startTypes", List.of());
        matchListNode(config, "flightTypeCodes", List.of());
        matchListNode(config, "startLocations", List.of());
        matchListNode(config, "ldgLocations", List.of());
        matchListNode(config, "clubMemberNumbers", List.of());
        matchListNode(config, "flightCrewTypes", List.of());
        matchListNode(config, "aircraftHomebases", List.of());
        matchListNode(config, "memberStates", List.of());
        matchListNode(config, "personCategories", List.of());
        if (deliveryLineText == null) {
            config.putNull("deliveryLineText");
        } else {
            config.put("deliveryLineText", deliveryLineText);
        }
        if (recipientNameForConfig == null) {
            config.putNull("recipientName");
        } else {
            config.put("recipientName", recipientNameForConfig);
        }
        String createdInstant = Instant.parse("2024-01-01T12:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private static void matchListNode(ObjectNode config, String key, List<String> matched) {
        ObjectNode node = config.putObject(key);
        node.put("useAllExcept", matched.isEmpty());
        var arr = node.putArray("matched");
        for (String element : matched) {
            arr.add(element);
        }
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
