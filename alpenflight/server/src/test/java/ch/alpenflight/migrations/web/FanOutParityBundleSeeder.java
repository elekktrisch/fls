package ch.alpenflight.migrations.web;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migration.bundle.crypto.TinkMigrationBundleCipher;
import ch.alpenflight.migrations.application.BundleManifest;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * J-0c T-03 — the SYNTHESIZED fan-out parity bundle builder, launched from the
 * real-idp Playwright spec ({@code fan-out-migration-parity.spec.ts}) via the
 * {@code seedFanOutParityBundle} Gradle {@code JavaExec} task.
 *
 * <p><strong>Why a Java seeder at all?</strong> The ALPF bundle envelope (Tink
 * StreamingAead body + RSA-OAEP-wrapped session key + the exact NDJSON / pgcopy
 * tar shape the ingest pipeline expects) is built in Java by
 * {@link MigrationBundleTestFactory} — the same factory the server ITs
 * ({@code LocationMigrationRoundTripIT} / {@code LocationRealProducerRoundTripIT})
 * use. A TypeScript spec cannot reproduce that envelope, so the bundle-build
 * stays in Java and this class is the thinnest launchable seam over it.
 *
 * <p><strong>What it does NOT do:</strong> it does not talk to Keycloak, the
 * backend, or Postgres. It is a pure byte-factory — given the per-upload RSA
 * public key (from the spec's REAL {@code POST /migrations/handshake}) and the
 * {@code uploadId}, it emits the encrypted bundle bytes to disk as base64. The
 * spec then POSTs those bytes through the REAL
 * {@code POST /api/v1/migrations/{uploadId}/bundle} endpoint (with a real
 * verified-email Bearer), so the fan-out keying + Keycloak provisioning run
 * live against the dev stack — exactly the seam the journey requires (ingest
 * through the real endpoint, never a DB INSERT).
 *
 * <p>The fan-out shape mirrors {@code LocationMigrationRoundTripIT}: one shared
 * legacy Location ({@code legacy_guid} identical) referenced by 2 clubs
 * ({@code club_id} distinct) → the LOCATION producer SELECT emits 2 NDJSON rows
 * → the ingest fans them out to 2 distinct {@code t_location} rows. The
 * Location name is the spec-supplied random {@code J0C-<rand>} freshness token
 * so the UI assertion proves data actually flowed.
 *
 * <p>Args (positional): {@code <publicKeyPemPath> <uploadId> <locationName>
 * <clubKeyPrefix> <outputPath>}. Writes the base64-encoded encrypted bundle to
 * {@code outputPath} and prints a single JSON line to stdout:
 * {@code {"clubKeyA":"…","clubKeyB":"…","locationName":"…","bundlePath":"…"}}
 * which the spec parses to correlate the ingest response's {@code clubIds} back
 * to per-club expectations.
 */
public final class FanOutParityBundleSeeder {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Real Flyway-seed reference PKs (V2 / V3 / V22) — same set the round-trip
    // IT pins. The synthetic-UUID encoding the reference-lookup resolver rewrites
    // to these is produced via Coercions, identical to the producer path.
    private static final UUID SEED_COUNTRY_CH =
            UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE =
            UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final int LEGACY_LOCATION_TYPE_GRASS = 2;   // GRASS_RUNWAY (V3)
    private static final int LEGACY_UNIT_FEET = 2;             // FEET (V22 backfill)
    // The CLUB_STATE legacy synthetic id the CLUB NDJSON references; mapped to
    // the real seed PK via the CLUB_STATE.pgcopy id-map below.
    private static final UUID LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC = new UUID(0L, 1L);

    private FanOutParityBundleSeeder() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: FanOutParityBundleSeeder <publicKeyPemPath> "
                    + "<uploadId> <locationName> <clubKeyPrefix> <outputPath>");
            System.exit(2);
            return;
        }
        Path publicKeyPemPath = Path.of(args[0]);
        UUID uploadId = UUID.fromString(args[1]);
        String locationName = args[2];
        String clubKeyPrefix = args[3];
        Path outputPath = Path.of(args[4]);

        String pem = Files.readString(publicKeyPemPath, StandardCharsets.UTF_8);
        byte[] publicKeyDer = decodePem(pem);

        MigrationBundleCipher cipher = new TinkMigrationBundleCipher();

        UUID legacyClubIdA = UUID.randomUUID();
        UUID legacyClubIdB = UUID.randomUUID();
        // Club keys / slugs are unique per run (the prefix carries the spec's
        // run id) so a replayed seed doesn't 409 on the slug-unique index — the
        // two declared clubs are always DISTINCT from each other and from the
        // Flyway seed club.
        String keyA = clubKeyPrefix + "A";
        String keyB = clubKeyPrefix + "B";
        String slugBase = clubKeyPrefix.toLowerCase(java.util.Locale.ROOT);

        BundleManifest.ClubDeclaration clubA = new BundleManifest.ClubDeclaration(
                legacyClubIdA, "J0C Parity Club A", slugBase + "-a", keyA, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        BundleManifest.ClubDeclaration clubB = new BundleManifest.ClubDeclaration(
                legacyClubIdB, "J0C Parity Club B", slugBase + "-b", keyB, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        // The single shared legacy Location, referenced by BOTH clubs (the exact
        // union the LocationMapper SELECT fans out on). legacy_guid identical
        // across the two NDJSON rows; club_id distinct → ingest fan-out to 2 rows.
        UUID sharedLocationId = UUID.randomUUID();
        UUID legacyCountryId = UUID.randomUUID();

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.LOCATION, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("CLUB.ndjson", concat(
                clubNdjson(legacyClubIdA, keyA, "J0C Parity Club A Legacy",
                        legacyCountryId),
                clubNdjson(legacyClubIdB, keyB, "J0C Parity Club B Legacy",
                        legacyCountryId)));
        // Fan-out: SAME legacy Location, one NDJSON row per referencing club.
        tarEntries.put("LOCATION.ndjson", concat(
                locationNdjson(sharedLocationId, legacyClubIdA, legacyCountryId, locationName),
                locationNdjson(sharedLocationId, legacyClubIdB, legacyCountryId, locationName)));
        // Composite (legacy_guid, club_id, new_uuid) id-map for the fanned-out
        // LOCATION — one row per replica, keyed on the LEGACY club id, so the
        // ingest-side composite lookup is populated.
        tarEntries.put("legacy_id_map/LOCATION.pgcopy", pgcopyMapFanOut(
                new FanOutMapRow(sharedLocationId, legacyClubIdA,
                        Coercions.deriveFanOutId(sharedLocationId, legacyClubIdA)),
                new FanOutMapRow(sharedLocationId, legacyClubIdB,
                        Coercions.deriveFanOutId(sharedLocationId, legacyClubIdB))));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "J0C Fan-out Parity Deployment",
                List.of(clubA, clubB), entityPolicies, tarEntries);

        Files.write(outputPath, Base64.getEncoder().encode(bundle));

        ObjectNode result = JSON.createObjectNode();
        result.put("clubKeyA", keyA);
        result.put("clubKeyB", keyB);
        result.put("locationName", locationName);
        result.put("bundlePath", outputPath.toAbsolutePath().toString());
        // Single machine-readable line on stdout for the spec to parse.
        System.out.println(JSON.writeValueAsString(result));
    }

    private static byte[] clubNdjson(UUID legacyClubId, String clubKey, String clubname,
                                     UUID legacyCountryId) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyClubId.toString());
        row.put("clubname", clubname);
        row.put("club_key", clubKey);
        row.put("address", "Parity Addr");
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
        String created = Instant.parse("2020-06-15T00:00:00Z").toString();
        row.put("created_on", created);
        row.putNull("created_by_user_id");
        row.put("modified_on", created);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    /** NDJSON shaped exactly as {@code LocationMapper.writeNdjson} after fan-out. */
    private static byte[] locationNdjson(UUID legacyLocationId, UUID legacyClubId,
                                         UUID countryId, String locationName) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("id", Coercions.deriveFanOutId(legacyLocationId, legacyClubId).toString());
        row.put("legacy_guid", legacyLocationId.toString());
        row.put("club_id", legacyClubId.toString());
        // The freshness token — both replicas carry the SAME random name (one
        // shared legacy Location), so each club sees its own copy under it.
        row.put("location_name", locationName);
        row.put("location_short_name", "J0C");
        row.put("country_id", countryId.toString());
        row.put("location_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_LOCATION_TYPE_GRASS));
        // Unique ICAO per club replica avoids any (club_id, icao_code) ambiguity;
        // the legacy producer would emit the same ICAO, but isolation is what we
        // assert, not ICAO sharing — keep it simple and distinct per replica.
        row.put("icao_code", "J" + Integer.toHexString(legacyClubId.hashCode()).substring(0, 3).toUpperCase(java.util.Locale.ROOT));
        row.put("latitude", "47.46");
        row.put("longitude", "8.55");
        row.put("elevation", 1416);
        row.put("elevation_unit_type_id", Coercions.legacyIntIdToUuidString(LEGACY_UNIT_FEET));
        row.put("runway_direction", "14/32");
        row.put("runway_length", 12139);
        row.put("runway_length_unit_type_id", Coercions.legacyIntIdToUuidString(LEGACY_UNIT_FEET));
        row.put("airport_frequency", "118.100");
        row.put("description", "J0C fan-out parity location");
        row.put("sort_indicator", 10);
        row.put("is_inbound_route_required", false);
        row.put("is_outbound_route_required", true);
        row.put("is_fast_entry_record", false);
        String created = Instant.parse("2024-01-01T12:00:00Z").toString();
        row.put("created_on", created);
        row.putNull("created_by_user_id");
        row.put("modified_on", created);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
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
