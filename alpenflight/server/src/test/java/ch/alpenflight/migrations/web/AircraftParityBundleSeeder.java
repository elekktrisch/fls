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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AircraftParityBundleSeeder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID SEED_COUNTRY_CH =
            UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE =
            UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final int LEGACY_AIRCRAFT_TYPE_GLIDER = 1;
    private static final int LEGACY_AIRCRAFT_STATE_OK = 1;
    private static final int LEGACY_LOCATION_TYPE_GRASS = 2;
    private static final int LEGACY_UNIT_FEET = 2;
    private static final int LEGACY_COUNTER_UNIT_DECIMAL_HOURS = 2;
    private static final UUID LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC = new UUID(0L, 1L);

    private AircraftParityBundleSeeder() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: AircraftParityBundleSeeder <publicKeyPemPath> "
                    + "<uploadId> <immatriculation> <clubKey> <outputPath>");
            System.exit(2);
            return;
        }
        Path publicKeyPemPath = Path.of(args[0]);
        UUID uploadId = UUID.fromString(args[1]);
        String immatriculation = args[2];
        String clubKey = args[3];
        Path outputPath = Path.of(args[4]);

        String pem = Files.readString(publicKeyPemPath, StandardCharsets.UTF_8);
        byte[] publicKeyDer = decodePem(pem);

        MigrationBundleCipher cipher = new TinkMigrationBundleCipher();

        UUID legacyClubId = UUID.randomUUID();
        UUID legacyCountryId = UUID.randomUUID();
        UUID legacyPersonId = UUID.randomUUID();
        UUID legacyAircraftId = UUID.randomUUID();
        UUID legacyHomebaseLocationId = UUID.randomUUID();
        UUID legacyOperatingCounterId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        String slug = clubKey.toLowerCase(java.util.Locale.ROOT);

        BundleManifest.ClubDeclaration club = new BundleManifest.ClubDeclaration(
                legacyClubId, "J1 Aircraft Parity Club", slug, clubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = Map.of(
                EntityType.CLUB, fullPortPolicy(),
                EntityType.PERSON, fullPortPolicy(),
                EntityType.LOCATION, fullPortPolicy(),
                EntityType.AIRCRAFT, fullPortPolicy(),
                EntityType.AIRCRAFT_AIRCRAFT_STATE, fullPortPolicy(),
                EntityType.AIRCRAFT_OPERATING_COUNTER, fullPortPolicy(),
                EntityType.COUNTRY, systemGlobalPolicy(),
                EntityType.CLUB_STATE, systemGlobalPolicy());

        UUID homebaseReplicaId =
                Coercions.deriveFanOutId(legacyHomebaseLocationId, legacyClubId);

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        tarEntries.put("CLUB.ndjson",
                clubNdjson(legacyClubId, clubKey, "J1 Aircraft Parity Club Legacy",
                        legacyCountryId));
        tarEntries.put("legacy_id_map/PERSON.pgcopy",
                pgcopyMap(legacyPersonId, legacyPersonId));
        tarEntries.put("PERSON.ndjson",
                personNdjson(legacyPersonId, legacyCountryId, "Owner", "Aircraft"));
        tarEntries.put("LOCATION.ndjson",
                locationNdjson(legacyHomebaseLocationId, legacyClubId, legacyCountryId,
                        "LSZH", actorUserId));
        tarEntries.put("legacy_id_map/LOCATION.pgcopy", pgcopyMapFanOut(
                new FanOutMapRow(legacyHomebaseLocationId, legacyClubId, homebaseReplicaId)));
        tarEntries.put("AIRCRAFT.ndjson", aircraftNdjson(
                legacyAircraftId, legacyClubId, legacyPersonId, legacyHomebaseLocationId,
                immatriculation, actorUserId));
        tarEntries.put("legacy_id_map/AIRCRAFT.pgcopy",
                pgcopyMap(legacyAircraftId, legacyAircraftId));
        tarEntries.put("AIRCRAFT_AIRCRAFT_STATE.ndjson",
                aircraftStateNdjson(legacyAircraftId, "Annual inspection passed", actorUserId));
        tarEntries.put("AIRCRAFT_OPERATING_COUNTER.ndjson",
                operatingCounterNdjson(legacyOperatingCounterId, legacyAircraftId, 360000L,
                        actorUserId));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "J1 Aircraft Parity Deployment",
                List.of(club), entityPolicies, tarEntries);

        Files.write(outputPath, java.util.Base64.getEncoder().encode(bundle));

        ObjectNode result = JSON.createObjectNode();
        result.put("clubKey", clubKey);
        result.put("immatriculation", immatriculation);
        result.put("bundlePath", outputPath.toAbsolutePath().toString());
        System.out.println(JSON.writeValueAsString(result));
    }

    private static byte[] aircraftNdjson(UUID legacyAircraftId, UUID legacyClubId,
                                         UUID ownerPersonId, UUID homebaseLocationId,
                                         String immatriculation, UUID actorUserId)
            throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyAircraftId.toString());
        row.put("managing_club_id", legacyClubId.toString());
        row.put("owner_club_id", legacyClubId.toString());
        row.put("aircraft_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_AIRCRAFT_TYPE_GLIDER));
        row.put("manufacturer_name", "Schleicher");
        row.put("aircraft_model", "ASK 21");
        row.put("immatriculation", immatriculation);
        row.put("competition_sign", "J1");
        row.putNull("flarm_id");
        row.putNull("aircraft_serial_number");
        row.putNull("year_of_manufacture");
        row.putNull("noise_class");
        row.putNull("noise_level");
        row.putNull("mtom");
        row.put("nr_of_seats", 2);
        row.put("aircraft_owner_person_id", ownerPersonId.toString());
        row.put("flight_operating_counter_unit_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_COUNTER_UNIT_DECIMAL_HOURS));
        row.putNull("engine_operating_counter_unit_type_id");
        row.put("homebase_id", homebaseLocationId.toString());
        row.putNull("spot_link");
        row.put("is_towing_or_winch_required", false);
        row.put("is_towing_start_allowed", true);
        row.put("is_winch_start_allowed", true);
        row.put("is_towing_aircraft", false);
        row.put("is_fast_entry_record", false);
        row.putNull("comment");
        row.putNull("daec_index");
        String created = Instant.parse("2022-04-01T08:00:00Z").toString();
        row.put("created_on", created);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", created);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private static byte[] aircraftStateNdjson(UUID legacyAircraftId, String remarks,
                                              UUID actorUserId) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("aircraft_id", legacyAircraftId.toString());
        row.put("aircraft_state_id",
                Coercions.legacyIntIdToUuidString(LEGACY_AIRCRAFT_STATE_OK));
        String validFrom = Instant.parse("2022-04-01T08:00:00Z").toString();
        row.put("valid_from", validFrom);
        row.putNull("valid_to");
        row.putNull("noticed_by_person_id");
        row.put("remarks", remarks);
        String created = Instant.parse("2022-04-01T08:00:00Z").toString();
        row.put("created_on", created);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", created);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private static byte[] operatingCounterNdjson(UUID legacyCounterId, UUID legacyAircraftId,
                                                 long flightSeconds, UUID actorUserId)
            throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyCounterId.toString());
        row.put("aircraft_id", legacyAircraftId.toString());
        String atDateTime = Instant.parse("2024-05-01T10:00:00Z").toString();
        row.put("at_date_time", atDateTime);
        row.putNull("total_towed_glider_starts");
        row.putNull("total_winch_launch_starts");
        row.putNull("total_self_starts");
        row.put("flight_operating_counter_in_seconds", flightSeconds);
        row.putNull("engine_operating_counter_in_seconds");
        row.putNull("next_maintenance_at_flight_operating_counter_in_seconds");
        row.putNull("next_maintenance_at_engine_operating_counter_in_seconds");
        String created = Instant.parse("2024-05-01T10:00:00Z").toString();
        row.put("created_on", created);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", created);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private static byte[] locationNdjson(UUID legacyLocationId, UUID legacyClubId,
                                         UUID countryId, String icao, UUID actorUserId)
            throws IOException {
        ObjectNode row = JSON.createObjectNode();
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
        row.put("description", "J1 aircraft parity homebase");
        row.put("sort_indicator", 10);
        row.put("is_inbound_route_required", false);
        row.put("is_outbound_route_required", true);
        row.put("is_fast_entry_record", false);
        String created = Instant.parse("2024-01-01T12:00:00Z").toString();
        row.put("created_on", created);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", created);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
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

    private static byte[] personNdjson(UUID legacyPersonId, UUID legacyCountryGuid,
                                       String lastname, String firstname) throws IOException {
        ObjectNode row = JSON.createObjectNode();
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
        row.put("country_id", legacyCountryGuid.toString());
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
        String created = Instant.parse("2019-09-01T00:00:00Z").toString();
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
