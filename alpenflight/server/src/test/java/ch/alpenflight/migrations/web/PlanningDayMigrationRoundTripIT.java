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
import java.time.LocalDate;
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
 * J-6 T-11 — the PlanningDay migrate round-trip proof: a legacy→export→ingest
 * round-trip exercised against the REAL server ingest pipeline (so the
 * {@link ch.alpenflight.migrations.application.ForeignKeyResolver} fan-out
 * resolution runs live, not the parity-set {@code ConsumerHarness} stand-in).
 * Mirrors {@link LocationMigrationRoundTripIT}.
 *
 * <p>The 3 PlanningDay mappers were authored + unit-tested at the S-014 baseline
 * but had NO {@code MapperLegacyBindings} producer entry, so the producer SELECT
 * + real round-trip had never run ({@code verify_infra_is_run_not_just_authored}).
 * T-11 wires the bindings; this IT proves them.
 *
 * <p>PlanningDay is <strong>fan-out NO</strong> (per-club operational data;
 * legacy_guid preserved verbatim as the destination {@code id}) but
 * <strong>references LOCATION, which fans out</strong>. The NDJSON is shaped
 * exactly as the mappers emit over their bound producer SELECTs: PlanningDay
 * carries {@code operating_club_id} + {@code location_id} (the SHARED legacy
 * LocationId), and {@code PlanningDayMapper.foreignKeyColumns()} names
 * {@code operating_club_id} as the fan-out disambiguator — so the migrated day's
 * {@code location_id} must resolve to the Location replica in the day's OWN club
 * via the composite {@code (legacy_guid, club_id)} lookup, never the other club's.
 *
 * <p>Asserts the load-bearing migration invariants:
 * <ul>
 *   <li><strong>Fan-out-resolved location.</strong> A shared legacy Location
 *       referenced by 2 clubs fans out to 2 replicas; the PlanningDay created by
 *       club A points at club A's OWN replica (the J-0b own-club FK invariant).</li>
 *   <li><strong>Day + assignments land.</strong> {@code t_planning_day} carries
 *       the renamed {@code planning_date} (= legacy {@code Day}, no tz shift) +
 *       {@code info} (= legacy {@code Remarks}), tenant-scoped on
 *       {@code operating_club_id}; the assignment + its type land with the right
 *       FKs.</li>
 *   <li><strong>Role↔type mapping.</strong> The 3 well-known per-club assignment
 *       types ({@code segelflugleiter}/{@code schlepppilot}/{@code fluglehrer})
 *       migrate from the REAL legacy data (NOT migration-seeded), and the
 *       assignment's {@code assignment_type_id} resolves to the migrated
 *       {@code segelflugleiter} type (→ FLIGHT_OPERATOR by name).</li>
 *   <li><strong>Tenant isolation.</strong> Every planning row carries club A's
 *       {@code operating_club_id}; club B has none.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({JwtTestFixture.class, MockKeycloakDirectoryConfig.class})
@Tag("slow")
class PlanningDayMigrationRoundTripIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH = UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE = UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID SEED_LANGUAGE_DE = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final UUID SEED_TENANT_USER_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    // Real Flyway-seed PKs the LOCATION reference-lookup resolver must land on.
    private static final int LEGACY_LOCATION_TYPE_GRASS = 2;     // GRASS_RUNWAY (V3)
    private static final int LEGACY_UNIT_FEET = 2;               // FEET (V22 backfill)

    private static final UUID LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC = new UUID(0L, 1L);

    /** The day's planned-for date — must survive migrate as a pure DATE, no tz shift. */
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 7, 4);

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
    private UUID legacyPersonId;

    @BeforeEach
    void seedActor() {
        userSub = UUID.randomUUID();
        userId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        legacyCountryId = UUID.randomUUID();
        legacyPersonId = UUID.randomUUID();
        String tag = userSub.toString().substring(0, 5);
        testClubKey = "PLN-" + tag;
        testClubSlug = "pln-" + tag;
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id, keycloak_sub)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """,
                userId.toString(), SEED_TENANT_USER_CLUB.toString(),
                "pln-it-" + userSub, "Planning IT",
                "pln-" + userSub + "@example.com",
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
        jdbc.update("DELETE FROM t_planning_day_assignment WHERE operating_club_id IN ("
                + clubsByOwner + ")", userSub.toString());
        jdbc.update("DELETE FROM t_planning_day WHERE operating_club_id IN ("
                + clubsByOwner + ")", userSub.toString());
        jdbc.update("DELETE FROM t_planning_day_assignment_type WHERE operating_club_id IN ("
                + clubsByOwner + ")", userSub.toString());
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
        // t_person is cross-tenant (no club_id) — clean the migrated person by its
        // preserved legacy id AFTER the referencing assignment rows are gone.
        jdbc.update("DELETE FROM t_person WHERE id = ?::uuid", legacyPersonId.toString());
    }

    @Test
    void planning_day_migrates_with_assignments_types_and_fan_out_resolved_own_club_location()
            throws Exception {
        JsonNode handshake = mintHandshake();
        UUID uploadId = UUID.fromString(handshake.get("uploadId").asText());
        byte[] publicKeyDer = decodePem(handshake.get("publicKeyPem").asText());

        UUID legacyClubIdA = UUID.randomUUID();
        UUID legacyClubIdB = UUID.randomUUID();
        String keyA = testClubKey + "A";
        String keyB = testClubKey + "B";
        BundleManifest.ClubDeclaration clubA = new BundleManifest.ClubDeclaration(
                legacyClubIdA, "Pln Club A", testClubSlug + "-a", keyA, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        BundleManifest.ClubDeclaration clubB = new BundleManifest.ClubDeclaration(
                legacyClubIdB, "Pln Club B", testClubSlug + "-b", keyB, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        // The SHARED legacy Location referenced by BOTH clubs (fan-out source). The
        // PlanningDay lives in club A and must resolve location_id to club A's OWN
        // replica via the composite (legacy_guid, club_id) lookup keyed on the day's
        // operating_club_id (the declared disambiguator).
        UUID sharedLocationId = UUID.randomUUID();
        // The 3 well-known per-club assignment types (real legacy data, NOT seeded).
        UUID legacyTypeFlightOperator = UUID.randomUUID(); // segelflugleiter
        UUID legacyTypeTowingPilot = UUID.randomUUID();    // schlepppilot
        UUID legacyTypeInstructor = UUID.randomUUID();     // fluglehrer
        UUID legacyDayId = UUID.randomUUID();
        UUID legacyAssignmentId = UUID.randomUUID();

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.LOCATION, fullPortPolicy(),
                EntityType.PERSON, fullPortPolicy(),
                EntityType.PLANNING_DAY_ASSIGNMENT_TYPE, fullPortPolicy(),
                EntityType.PLANNING_DAY, fullPortPolicy(),
                EntityType.PLANNING_DAY_ASSIGNMENT, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("CLUB.ndjson", concat(
                clubNdjson(legacyClubIdA, keyA, "Pln Club A Legacy"),
                clubNdjson(legacyClubIdB, keyB, "Pln Club B Legacy")));
        // Fan-out: the SAME legacy Location, one NDJSON row per referencing Club.
        tarEntries.put("LOCATION.ndjson", concat(
                locationNdjson(sharedLocationId, legacyClubIdA),
                locationNdjson(sharedLocationId, legacyClubIdB)));
        // Composite LOCATION id-map ordered BEFORE any entity that resolves a
        // location_id, so the day's club-aware FK resolve lands on the right replica.
        tarEntries.put("legacy_id_map/LOCATION.pgcopy", pgcopyMapFanOut(
                new FanOutMapRow(sharedLocationId, legacyClubIdA,
                        Coercions.deriveFanOutId(sharedLocationId, legacyClubIdA)),
                new FanOutMapRow(sharedLocationId, legacyClubIdB,
                        Coercions.deriveFanOutId(sharedLocationId, legacyClubIdB))));
        // PERSON is FULL_PORT non-fan-out: id preserved, so the assignment's
        // assigned_person_id (the legacy PersonId, verbatim) resolves against this
        // t_person row at INSERT time (no PERSON id-map needed).
        tarEntries.put("PERSON.ndjson", personNdjson(legacyPersonId, "Duty", "Crew"));
        // The 3 per-club assignment types — migrated from real legacy data (the
        // migration tests assert they are NOT migration-seeded). All in club A.
        tarEntries.put("PLANNING_DAY_ASSIGNMENT_TYPE.ndjson", concat(concat(
                assignmentTypeNdjson(legacyTypeFlightOperator, legacyClubIdA, "Segelflugleiter"),
                assignmentTypeNdjson(legacyTypeTowingPilot, legacyClubIdA, "Schlepppilot")),
                assignmentTypeNdjson(legacyTypeInstructor, legacyClubIdA, "Fluglehrer")));
        // The day lives in club A, referencing the SHARED legacy Location.
        tarEntries.put("PLANNING_DAY.ndjson",
                planningDayNdjson(legacyDayId, legacyClubIdA, sharedLocationId));
        // One assignment: the duty crew as FLIGHT_OPERATOR (segelflugleiter type).
        tarEntries.put("PLANNING_DAY_ASSIGNMENT.ndjson", planningDayAssignmentNdjson(
                legacyAssignmentId, legacyClubIdA, legacyDayId, legacyPersonId,
                legacyTypeFlightOperator));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "PlanningDay Migrate IT Deployment",
                List.of(clubA, clubB), entityPolicies, tarEntries);

        ResponseEntity<String> res = postBundle(uploadId, bundle, verifiedToken);
        assertThat(res.getStatusCode())
                .as("PlanningDay migrate round-trip ingest failed; body=%s", res.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode body = JSON.readTree(res.getBody());
        UUID deploymentId = UUID.fromString(body.get("deploymentId").asText());
        Map<String, UUID> clubIdByKey = clubIdsByKey(deploymentId);
        UUID newClubA = clubIdByKey.get(keyA);
        UUID newClubB = clubIdByKey.get(keyB);

        // The fanned-out Location replica id in each club.
        Map<String, UUID> replicaIdByClub = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, club_id FROM t_location WHERE club_id IN (?::uuid, ?::uuid)",
                newClubA.toString(), newClubB.toString())) {
            replicaIdByClub.put(r.get("club_id").toString(),
                    UUID.fromString(r.get("id").toString()));
        }
        UUID replicaInClubA = replicaIdByClub.get(newClubA.toString());
        UUID replicaInClubB = replicaIdByClub.get(newClubB.toString());
        assertThat(replicaInClubA)
                .as("the shared legacy Location fanned out to a distinct club-A replica")
                .isNotNull()
                .isNotEqualTo(replicaInClubB);

        // The migrated planning day: id preserved (legacy_guid → id), tenant-scoped
        // on club A, planning_date = legacy Day (no tz shift), info = legacy Remarks.
        Map<String, Object> day = jdbc.queryForMap(
                "SELECT id, operating_club_id, planning_date, location_id, info "
                        + "FROM t_planning_day WHERE id = ?::uuid", legacyDayId.toString());
        assertThat(UUID.fromString(day.get("operating_club_id").toString()))
                .as("the migrated day is tenant-scoped on club A")
                .isEqualTo(newClubA);
        assertThat(((java.sql.Date) day.get("planning_date")).toLocalDate())
                .as("legacy Day → planning_date with NO tz shift (pure DATE)")
                .isEqualTo(PLANNING_DATE);
        assertThat(day.get("info"))
                .as("legacy Remarks → info")
                .isEqualTo("Saturday ops");

        // THE load-bearing fan-out invariant: the day points at club A's OWN
        // Location replica (resolved via the composite (legacy_guid, club_id) lookup
        // keyed on operating_club_id — NOT club B's replica).
        assertThat(UUID.fromString(day.get("location_id").toString()))
                .as("the migrated day's location_id resolves to the Location replica "
                        + "in the day's OWN club (fan-out disambiguated on "
                        + "operating_club_id), not the other club's replica")
                .isEqualTo(replicaInClubA);

        // The 3 well-known assignment types migrated from real legacy data, all in
        // club A — proving they ride the real producer stream, not a migration seed.
        List<Map<String, Object>> types = jdbc.queryForList(
                "SELECT id, assignment_type_name FROM t_planning_day_assignment_type "
                        + "WHERE operating_club_id = ?::uuid ORDER BY assignment_type_name",
                newClubA.toString());
        assertThat(types.stream().map(t -> t.get("assignment_type_name").toString()).toList())
                .as("the 3 per-club assignment types migrated from real legacy data")
                .containsExactly("Fluglehrer", "Schlepppilot", "Segelflugleiter");

        // The assignment landed with the right resolved FKs: the day, the person
        // (preserved legacy id), and the segelflugleiter type (→ FLIGHT_OPERATOR).
        Map<String, Object> assignment = jdbc.queryForMap(
                "SELECT operating_club_id, planning_day_id, assigned_person_id, "
                        + "assignment_type_id FROM t_planning_day_assignment WHERE id = ?::uuid",
                legacyAssignmentId.toString());
        assertThat(UUID.fromString(assignment.get("operating_club_id").toString()))
                .isEqualTo(newClubA);
        assertThat(UUID.fromString(assignment.get("planning_day_id").toString()))
                .as("assignment attaches to the migrated planning day")
                .isEqualTo(legacyDayId);
        assertThat(UUID.fromString(assignment.get("assigned_person_id").toString()))
                .as("assigned_person_id is the preserved legacy PersonId (PERSON FULL_PORT)")
                .isEqualTo(legacyPersonId);
        assertThat(UUID.fromString(assignment.get("assignment_type_id").toString()))
                .as("the assignment resolves to the migrated segelflugleiter type "
                        + "(role↔type-name: segelflugleiter → FLIGHT_OPERATOR)")
                .isEqualTo(legacyTypeFlightOperator);

        // Tenant isolation: club B has no planning data (the shared Location fanned
        // out, but the day/assignment/types are club A's alone).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM t_planning_day WHERE operating_club_id = ?::uuid",
                Integer.class, newClubB.toString()))
                .as("club B has no planning day (tenant isolation)")
                .isZero();
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

    /** NDJSON shaped exactly as {@code LocationMapper.writeNdjson}. */
    private byte[] locationNdjson(UUID legacyLocationId, UUID legacyClubId) throws IOException {
        var row = JSON.createObjectNode();
        row.put("id", Coercions.deriveFanOutId(legacyLocationId, legacyClubId).toString());
        row.put("legacy_guid", legacyLocationId.toString());
        row.put("club_id", legacyClubId.toString());
        row.put("location_name", "Bern-Belp");
        row.put("location_short_name", "BLP");
        row.put("country_id", legacyCountryId.toString());
        row.put("location_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_LOCATION_TYPE_GRASS));
        row.put("icao_code", "LSZW");
        row.put("latitude", "46.91");
        row.put("longitude", "7.50");
        row.put("elevation", 1674);
        row.put("elevation_unit_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_UNIT_FEET));
        row.put("runway_direction", "07/25");
        row.put("runway_length", 3543);
        row.put("runway_length_unit_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_UNIT_FEET));
        row.put("airport_frequency", "121.000");
        row.put("description", "Bern-Belp");
        row.put("sort_indicator", 10);
        row.put("is_inbound_route_required", false);
        row.put("is_outbound_route_required", false);
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

    /** NDJSON shaped exactly as {@code PersonMapper.writeNdjson} (cross-tenant). */
    private byte[] personNdjson(UUID legacyPersonId, String lastname, String firstname)
            throws IOException {
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
        row.put("country_id", legacyCountryId.toString());
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

    /** NDJSON shaped exactly as {@code PlanningDayAssignmentTypeMapper.writeNdjson}. */
    private byte[] assignmentTypeNdjson(UUID legacyTypeId, UUID legacyClubId, String name)
            throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyTypeId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        row.put("assignment_type_name", name);
        row.put("required_nr_of_assignments", 1);
        String createdInstant = Instant.parse("2021-01-01T00:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.putNull("created_by_user_id");
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    /** NDJSON shaped exactly as {@code PlanningDayMapper.writeNdjson}. */
    private byte[] planningDayNdjson(UUID legacyDayId, UUID legacyClubId, UUID legacyLocationId)
            throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyDayId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        // Pure DATE on the wire — no time component, no tz shift on migrate.
        row.put("planning_date", PLANNING_DATE.toString());
        row.put("location_id", legacyLocationId.toString());
        row.put("info", "Saturday ops");
        String createdInstant = Instant.parse("2026-06-01T00:00:00Z").toString();
        row.put("created_on", createdInstant);
        row.putNull("created_by_user_id");
        row.put("modified_on", createdInstant);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    /** NDJSON shaped exactly as {@code PlanningDayAssignmentMapper.writeNdjson}. */
    private byte[] planningDayAssignmentNdjson(UUID legacyAssignmentId, UUID legacyClubId,
            UUID legacyDayId, UUID legacyPersonId, UUID legacyTypeId) throws IOException {
        var row = JSON.createObjectNode();
        row.put("legacy_guid", legacyAssignmentId.toString());
        // Denormalised @TenantId (producer sources it by JOINing PlanningDays).
        row.put("operating_club_id", legacyClubId.toString());
        row.put("planning_day_id", legacyDayId.toString());
        row.put("assigned_person_id", legacyPersonId.toString());
        row.put("assignment_type_id", legacyTypeId.toString());
        row.putNull("info");
        String createdInstant = Instant.parse("2026-06-01T00:00:00Z").toString();
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

    /** One composite (legacy_guid, club_id, new_uuid) id-map row for a fan-out entity. */
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
