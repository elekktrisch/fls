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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * J-2 T-08 — the SYNTHESIZED Flight + FlightCrew migration parity bundle builder,
 * launched from the real-idp Playwright spec
 * ({@code flight-migration-parity.spec.ts}) via the {@code seedFlightParityBundle}
 * Gradle {@code JavaExec} task.
 *
 * <p>Mirrors {@link AircraftParityBundleSeeder} (the J-1 template): a pure
 * byte-factory that, given the per-upload RSA public key (from the spec's REAL
 * {@code POST /migrations/handshake}) and the {@code uploadId}, emits the
 * encrypted bundle bytes to disk as base64. The spec then POSTs those bytes
 * through the REAL {@code POST /api/v1/migrations/{uploadId}/bundle} endpoint
 * (with a real verified-email Bearer), so the migration ingest + Keycloak
 * club-admin provisioning run LIVE against the dev stack.
 *
 * <p>The FLIGHT / FLIGHT_CREW NDJSON is byte-aligned with
 * {@link FlightMigrationRoundTripIT} (the authoritative round-trip proof) — every
 * field is shaped exactly as {@code FlightMapper.writeNdjson} /
 * {@code FlightCrewMapper.writeNdjson} emit it over the
 * {@code MapperLegacyBindings.FLIGHT} / {@code FLIGHT_CREW} producer SELECTs:
 * {@code legacy_guid} = the legacy {@code FlightId} (FLIGHT is non-fan-out
 * FULL_PORT → {@code legacy_guid} → {@code id}); {@code operating_club_id} = the
 * legacy {@code OwnerId} (T-07's producer-SELECT catch); reference-lookup columns
 * ({@code start_type_id}, {@code process_state_id},
 * {@code flight_cost_balance_type_id}, {@code flight_crew_type_id}) as the
 * synthetic {@code new UUID(0, legacyIntId)}; the empty-guid sentinel collapsed to
 * null on a non-towed flight.
 *
 * <p><strong>Two declared clubs</strong> — the primary owner (carries the glider
 * + paired tow + motor + DeliveryBooked flights) and a SECOND cross-tenant club
 * (carries one flight) so the spec's cross-tenant 404 assertion has a real
 * other-tenant subject in the SAME deployment. Each club has its own FK closure
 * (location replica, flight type, aircraft, pilot Person).
 *
 * <p><strong>Time-gate seed nuance</strong> (S-061, operator divergence). The
 * migrated {@code flight_date}s are computed RELATIVE TO {@code LocalDate.now(UTC)}
 * at seed time — NOT hardcoded calendar dates — because the real-idp proof backend
 * boots {@code TimeConfig} with {@code Clock.systemUTC()} (no fixed clock in the
 * {@code dev} profile the CI proof job runs), and {@code FlightsService} applies a
 * default {@code [today-90d, today]} list window when the client sends no from/to
 * (the flights store sends none by default). Hardcoded Dec-2025 dates fell OUTSIDE
 * that 90-day window at any CI run date past ~Mar-2026, so the migrated-render +
 * DeliveryBooked-read assertions silently fell off the list (J-2 T-16 gate blocker).
 * Relative offsets keep every migrated flight inside the 90-day window AT ANY RUN
 * DATE while still satisfying the gate semantics: the glider/tow pair sits
 * {@value #LOCKABLE_OFFSET_DAYS} days before now (≥2 days before, so the lock gate
 * {@code flight_date <= today-2d} ALLOWS a Valid→Locked transition); the
 * DeliveryBooked flight ports {@code process_state = DELIVERY_BOOKED(60)} so it is
 * read-only (edit/delete → 4xx) and carries {@code locked_at = ModifiedOn}
 * {@value #BILLABLE_LOCKED_OFFSET_DAYS} days before now (≥3 days before, so the bill
 * gate {@code locked_at <= today-3d} would ALLOW billing; mapper rule:
 * {@code ProcessStateId >= LOCKED(40)} → locked_at = ModifiedOn).
 *
 * <p>Tar order (pgcopy id-maps before the NDJSON that references them, per the
 * ingest resolver + the {@link FlightMigrationRoundTripIT} ordering):
 * {@code COUNTRY/CLUB_STATE/START_TYPE maps → CLUB → PERSON map+ndjson →
 * LOCATION ndjson+fanout-map → FLIGHT_TYPE ndjson+id-map → AIRCRAFT ndjson+id-map
 * → FLIGHT ndjson+id-map → FLIGHT_CREW ndjson}.
 *
 * <p>Args (positional): {@code <publicKeyPemPath> <uploadId> <freshnessToken>
 * <clubKey> <outputPath>}. {@code freshnessToken} stamps the rendered glider
 * flight's comment so the UI assertion proves data actually flowed end to end;
 * {@code clubKey} is the PRIMARY (owning) club's key — the cross-tenant club's key
 * is derived from it ({@code <clubKey>X}). Writes the base64-encoded encrypted
 * bundle to {@code outputPath} and prints a single JSON line to stdout:
 * {@code {"clubKey":"…","crossTenantClubKey":"…","freshnessToken":"…",
 * "bundlePath":"…"}} which the spec parses to correlate the ingest response's
 * {@code clubIds} back to the owning + cross-tenant clubs.
 */
public final class FlightParityBundleSeeder {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Real Flyway-seed reference PKs the reference-lookup resolver lands on
    // (V2 / V3) — the same set FlightMigrationRoundTripIT pins.
    private static final UUID SEED_COUNTRY_CH =
            UUID.fromString("019e2e15-2c00-74be-8000-0000000004be");
    private static final UUID SEED_CLUB_STATE_ACTIVE =
            UUID.fromString("019e2e15-2c00-7bb8-8000-000000000bb8");
    private static final UUID LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC = new UUID(0L, 1L);

    private static final int LEGACY_AIRCRAFT_TYPE_GLIDER = 1;
    private static final int LEGACY_AIRCRAFT_TYPE_TOW = 2;
    private static final int LEGACY_AIRCRAFT_TYPE_MOTOR = 4;
    private static final int LEGACY_LOCATION_TYPE_GRASS = 2;
    private static final int LEGACY_UNIT_FEET = 2;

    private static final int LEGACY_START_TYPE_AEROTOW = 2;     // -> V2 AEROTOW seed PK
    private static final int LEGACY_FCBT_PILOT_PAYS_ALL = 1;    // -> V3 seed PK

    private static final int LEGACY_PROCESS_STATE_VALID = 30;
    private static final int LEGACY_PROCESS_STATE_LOCKED = 40;
    private static final int LEGACY_PROCESS_STATE_DELIVERY_BOOKED = 60;

    private static final int LEGACY_CREW_TYPE_PILOT = 1;
    private static final int LEGACY_CREW_TYPE_CO_PILOT = 2;

    private static final int LEGACY_FLIGHT_AIRCRAFT_TYPE_GLIDER = 1;
    private static final int LEGACY_FLIGHT_AIRCRAFT_TYPE_TOW = 2;
    private static final int LEGACY_FLIGHT_AIRCRAFT_TYPE_MOTOR = 4;

    // Legacy AirState FlightPlanOpen(5) — the only air-state value whose
    // timestamp survives V13 (S-060). Others collapse to null.
    private static final int LEGACY_AIR_STATE_NEW = 0;
    private static final int LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN = 5;
    private static final int LEGACY_AIR_STATE_LANDED = 20;

    // Time-gate seed offsets are RELATIVE TO LocalDate.now() at seed time, NOT
    // hardcoded calendar dates: the real-idp proof backend runs Clock.systemUTC()
    // and FlightsService applies a default [today-90d, today] list window, so any
    // fixed past date eventually falls out of the window and the migrated rows stop
    // rendering (J-2 T-16). now-minus-offset keeps every migrated flight in-window
    // at any run date AND still satisfies the lock/bill day-boundary gates.
    //
    // Lockable glider+tow: flight_date = now-5d (>= today-2d so the lock gate ALLOWS
    // Valid->Locked; comfortably inside the 90-day window).
    private static final int LOCKABLE_OFFSET_DAYS = 5;
    // Motor: flight_date = now-3d (Valid; in-window).
    private static final int MOTOR_OFFSET_DAYS = 3;
    // DeliveryBooked: flight_date = now-10d, locked_at = now-5d (>= today-3d so the
    // bill gate ALLOWS billing; both inside the 90-day window).
    private static final int DELIVERY_BOOKED_FLIGHT_OFFSET_DAYS = 10;
    private static final int BILLABLE_LOCKED_OFFSET_DAYS = 5;

    private FlightParityBundleSeeder() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: FlightParityBundleSeeder <publicKeyPemPath> "
                    + "<uploadId> <freshnessToken> <clubKey> <outputPath>");
            System.exit(2);
            return;
        }
        Path publicKeyPemPath = Path.of(args[0]);
        UUID uploadId = UUID.fromString(args[1]);
        String freshnessToken = args[2];
        String clubKey = args[3];
        Path outputPath = Path.of(args[4]);

        String pem = Files.readString(publicKeyPemPath, StandardCharsets.UTF_8);
        byte[] publicKeyDer = decodePem(pem);

        MigrationBundleCipher cipher = new TinkMigrationBundleCipher();

        // The cross-tenant club shares this deployment but is DISTINCT from the
        // owning club — its admin's single-flight GET of the owning club's flight
        // must 404 (structural @TenantId scope, oracle #24).
        String crossTenantClubKey = clubKey + "X";

        // Unique per run (the clubKey carries the spec's run id) so a replayed
        // seed doesn't 409 on the slug/club-key unique index.
        UUID ownerClubId = UUID.randomUUID();
        UUID crossClubId = UUID.randomUUID();
        UUID legacyCountryId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();

        // Owner-club closure.
        UUID ownerPilotId = UUID.randomUUID();
        UUID ownerCoPilotId = UUID.randomUUID();
        UUID ownerLocationId = UUID.randomUUID();
        UUID ownerFlightTypeId = UUID.randomUUID();
        UUID gliderAircraftId = UUID.randomUUID();
        UUID towAircraftId = UUID.randomUUID();
        UUID motorAircraftId = UUID.randomUUID();

        UUID gliderFlightId = UUID.randomUUID();
        UUID towFlightId = UUID.randomUUID();
        UUID motorFlightId = UUID.randomUUID();
        UUID deliveryBookedFlightId = UUID.randomUUID();
        UUID gliderPilotCrewId = UUID.randomUUID();
        UUID gliderCoPilotCrewId = UUID.randomUUID();

        // Cross-tenant-club closure (minimal — one flight, no crew/tow).
        UUID crossPilotId = UUID.randomUUID();
        UUID crossLocationId = UUID.randomUUID();
        UUID crossFlightTypeId = UUID.randomUUID();
        UUID crossAircraftId = UUID.randomUUID();
        UUID crossFlightId = UUID.randomUUID();

        String ownerSlug = clubKey.toLowerCase(Locale.ROOT);
        String crossSlug = crossTenantClubKey.toLowerCase(Locale.ROOT);

        BundleManifest.ClubDeclaration ownerClub = new BundleManifest.ClubDeclaration(
                ownerClubId, "J2 Flight Parity Club", ownerSlug, clubKey, false,
                SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);
        BundleManifest.ClubDeclaration crossClub = new BundleManifest.ClubDeclaration(
                crossClubId, "J2 Flight Parity Cross-Tenant Club", crossSlug,
                crossTenantClubKey, false, SEED_COUNTRY_CH, SEED_CLUB_STATE_ACTIVE);

        Map<EntityType, EntityPolicy> entityPolicies = new LinkedHashMap<>();
        entityPolicies.put(EntityType.COUNTRY, systemGlobalPolicy());
        entityPolicies.put(EntityType.CLUB_STATE, systemGlobalPolicy());
        entityPolicies.put(EntityType.START_TYPE, systemGlobalPolicy());
        entityPolicies.put(EntityType.CLUB, fullPortPolicy());
        entityPolicies.put(EntityType.PERSON, fullPortPolicy());
        entityPolicies.put(EntityType.LOCATION, fullPortPolicy());
        entityPolicies.put(EntityType.FLIGHT_TYPE, fullPortPolicy());
        entityPolicies.put(EntityType.AIRCRAFT, fullPortPolicy());
        entityPolicies.put(EntityType.FLIGHT, fullPortPolicy());
        entityPolicies.put(EntityType.FLIGHT_CREW, fullPortPolicy());

        UUID ownerLocationReplicaId =
                Coercions.deriveFanOutId(ownerLocationId, ownerClubId);
        UUID crossLocationReplicaId =
                Coercions.deriveFanOutId(crossLocationId, crossClubId);

        Map<String, byte[]> tarEntries = new LinkedHashMap<>();
        tarEntries.put("legacy_id_map/COUNTRY.pgcopy",
                pgcopyMap(legacyCountryId, SEED_COUNTRY_CH));
        tarEntries.put("legacy_id_map/CLUB_STATE.pgcopy",
                pgcopyMap(LEGACY_CLUB_STATE_ACTIVE_SYNTHETIC, SEED_CLUB_STATE_ACTIVE));
        // START_TYPE SYSTEM_GLOBAL map: synthetic UUID(0, legacyId) -> real seed PK
        // (resolved structurally by the ingest against the V2 seed natural key).
        tarEntries.put("legacy_id_map/START_TYPE.pgcopy",
                pgcopyMap(new UUID(0L, LEGACY_START_TYPE_AEROTOW),
                        UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1")));

        tarEntries.put("CLUB.ndjson", concat(
                clubNdjson(ownerClubId, clubKey, "J2 Flight Parity Club Legacy",
                        legacyCountryId),
                clubNdjson(crossClubId, crossTenantClubKey,
                        "J2 Flight Parity Cross-Tenant Club Legacy", legacyCountryId)));

        // Persons (cross-tenant, FULL_PORT identity — id preserved) + identity
        // id-map so the FlightCrew person FKs resolve.
        tarEntries.put("legacy_id_map/PERSON.pgcopy", pgcopyMap3(
                new MapRow(ownerPilotId, ownerPilotId),
                new MapRow(ownerCoPilotId, ownerCoPilotId),
                new MapRow(crossPilotId, crossPilotId)));
        tarEntries.put("PERSON.ndjson", concat(
                personNdjson(ownerPilotId, legacyCountryId, "Pilot", "Glider"),
                personNdjson(ownerCoPilotId, legacyCountryId, "CoPilot", "Glider"),
                personNdjson(crossPilotId, legacyCountryId, "Pilot", "Cross")));

        // Locations (fan-out: one replica per club) + composite id-map.
        tarEntries.put("LOCATION.ndjson", concat(
                locationNdjson(ownerLocationId, ownerClubId, legacyCountryId, "LSZH",
                        "Zurich", actorUserId),
                locationNdjson(crossLocationId, crossClubId, legacyCountryId, "LSZB",
                        "Bern", actorUserId)));
        tarEntries.put("legacy_id_map/LOCATION.pgcopy", pgcopyMapFanOut(
                new FanOutMapRow(ownerLocationId, ownerClubId, ownerLocationReplicaId),
                new FanOutMapRow(crossLocationId, crossClubId, crossLocationReplicaId)));

        // FLIGHT_TYPE (FULL_PORT, non-fan-out): legacy_guid -> id.
        tarEntries.put("FLIGHT_TYPE.ndjson", concat(
                flightTypeNdjson(ownerFlightTypeId, ownerClubId, "Schulung", actorUserId),
                flightTypeNdjson(crossFlightTypeId, crossClubId, "Schulung", actorUserId)));
        tarEntries.put("legacy_id_map/FLIGHT_TYPE.pgcopy", pgcopyMap3(
                new MapRow(ownerFlightTypeId, ownerFlightTypeId),
                new MapRow(crossFlightTypeId, crossFlightTypeId)));

        // Aircraft (glider / tow / motor for the owner, one for the cross club),
        // all cross-tenant non-fan-out.
        tarEntries.put("AIRCRAFT.ndjson", concat(
                aircraftNdjson(gliderAircraftId, ownerClubId, "HB-3000",
                        LEGACY_AIRCRAFT_TYPE_GLIDER, actorUserId),
                aircraftNdjson(towAircraftId, ownerClubId, "HB-TOW1",
                        LEGACY_AIRCRAFT_TYPE_TOW, actorUserId),
                aircraftNdjson(motorAircraftId, ownerClubId, "HB-MOT1",
                        LEGACY_AIRCRAFT_TYPE_MOTOR, actorUserId),
                aircraftNdjson(crossAircraftId, crossClubId, "HB-3001",
                        LEGACY_AIRCRAFT_TYPE_GLIDER, actorUserId)));
        tarEntries.put("legacy_id_map/AIRCRAFT.pgcopy", pgcopyMap3(
                new MapRow(gliderAircraftId, gliderAircraftId),
                new MapRow(towAircraftId, towAircraftId),
                new MapRow(motorAircraftId, motorAircraftId),
                new MapRow(crossAircraftId, crossAircraftId)));

        // Resolve the relative time-gate dates against the seed-time wallclock
        // (= the real-idp proof backend's Clock.systemUTC() "now"), so the migrated
        // rows always land inside FlightsService's default [today-90d, today] list
        // window AND on the correct side of the lock/bill day boundaries (J-2 T-16).
        // UTC to match the backend's Clock.systemUTC() notion of "today" (no TZ skew).
        LocalDate seedNow = LocalDate.now(ZoneOffset.UTC);
        String lockableFlightDate = seedNow.minusDays(LOCKABLE_OFFSET_DAYS).toString();
        String motorFlightDate = seedNow.minusDays(MOTOR_OFFSET_DAYS).toString();
        String deliveryBookedFlightDate =
                seedNow.minusDays(DELIVERY_BOOKED_FLIGHT_OFFSET_DAYS).toString();
        String billableLockedAt = seedNow.minusDays(BILLABLE_LOCKED_OFFSET_DAYS).toString();

        // FLIGHT (non-fan-out): legacy_guid -> id. Tow first so its id-map row
        // precedes the glider that references it (mirror the producer's order).
        tarEntries.put("FLIGHT.ndjson", concat(
                // Tow flight: TOW type, no tow_flight_id, LANDED air-state, Valid;
                // paired with the glider (shared flight_date / location).
                flightNdjson(towFlightId, ownerClubId, towAircraftId, ownerLocationId,
                        ownerFlightTypeId, /* towFlightId */ null,
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_TOW, LEGACY_AIR_STATE_LANDED,
                        LEGACY_PROCESS_STATE_VALID, lockableFlightDate,
                        /* lockedAtDate */ null, "J2 tow " + freshnessToken),
                // Glider flight: tow_flight_id -> the tow flight; FlightPlanOpen(5)
                // air-state -> flight_plan_opened_on survives; Valid + a past
                // flight_date so the lock gate ALLOWS Valid->Locked (lockable).
                flightNdjson(gliderFlightId, ownerClubId, gliderAircraftId, ownerLocationId,
                        ownerFlightTypeId, towFlightId.toString(),
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_GLIDER, LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN,
                        LEGACY_PROCESS_STATE_VALID, lockableFlightDate,
                        /* lockedAtDate */ null, "J2 glider " + freshnessToken),
                // Motor flight: not towed (empty-guid -> null at the producer),
                // MOTOR(4) discriminator, Valid.
                flightNdjson(motorFlightId, ownerClubId, motorAircraftId, ownerLocationId,
                        ownerFlightTypeId, /* towFlightId */ null,
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_MOTOR, LEGACY_AIR_STATE_NEW,
                        LEGACY_PROCESS_STATE_VALID, motorFlightDate,
                        /* lockedAtDate */ null, "J2 motor " + freshnessToken),
                // DeliveryBooked flight: terminal read-only state (edit/delete ->
                // 4xx). Locked-or-beyond, so locked_at carries (billable window).
                flightNdjson(deliveryBookedFlightId, ownerClubId, gliderAircraftId,
                        ownerLocationId, ownerFlightTypeId, /* towFlightId */ null,
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_GLIDER, LEGACY_AIR_STATE_LANDED,
                        LEGACY_PROCESS_STATE_DELIVERY_BOOKED, deliveryBookedFlightDate,
                        billableLockedAt, "J2 delivery-booked " + freshnessToken),
                // Cross-tenant flight: a DIFFERENT club's flight in the same
                // deployment (the owning admin's GET of it must 404). Also relative
                // (now-5d) so it stays in-window for the cross club's own list.
                flightNdjson(crossFlightId, crossClubId, crossAircraftId, crossLocationId,
                        crossFlightTypeId, /* towFlightId */ null,
                        LEGACY_FLIGHT_AIRCRAFT_TYPE_GLIDER, LEGACY_AIR_STATE_NEW,
                        LEGACY_PROCESS_STATE_VALID, lockableFlightDate,
                        /* lockedAtDate */ null, "J2 cross-tenant " + freshnessToken)));
        tarEntries.put("legacy_id_map/FLIGHT.pgcopy", pgcopyMap3(
                new MapRow(towFlightId, towFlightId),
                new MapRow(gliderFlightId, gliderFlightId),
                new MapRow(motorFlightId, motorFlightId),
                new MapRow(deliveryBookedFlightId, deliveryBookedFlightId),
                new MapRow(crossFlightId, crossFlightId)));

        // FlightCrew: the glider flight carries a pilot + co-pilot.
        tarEntries.put("FLIGHT_CREW.ndjson", concat(
                flightCrewNdjson(gliderPilotCrewId, gliderFlightId, ownerPilotId,
                        LEGACY_CREW_TYPE_PILOT),
                flightCrewNdjson(gliderCoPilotCrewId, gliderFlightId, ownerCoPilotId,
                        LEGACY_CREW_TYPE_CO_PILOT)));

        byte[] bundle = MigrationBundleTestFactory.buildBundleWithEntries(
                cipher, uploadId, publicKeyDer, "J2 Flight Parity Deployment",
                List.of(ownerClub, crossClub), entityPolicies, tarEntries);

        Files.write(outputPath, java.util.Base64.getEncoder().encode(bundle));

        ObjectNode result = JSON.createObjectNode();
        result.put("clubKey", clubKey);
        result.put("crossTenantClubKey", crossTenantClubKey);
        result.put("freshnessToken", freshnessToken);
        result.put("bundlePath", outputPath.toAbsolutePath().toString());
        // Single machine-readable line on stdout for the spec to parse.
        System.out.println(JSON.writeValueAsString(result));
    }

    /** NDJSON shaped EXACTLY as {@code FlightMapper.writeNdjson}. FLIGHT is non-fan-out. */
    private static byte[] flightNdjson(UUID legacyFlightId, UUID legacyClubId, UUID aircraftId,
                                       UUID locationId, UUID flightTypeId, String towFlightId,
                                       int flightAircraftType, int legacyAirStateId,
                                       int legacyProcessStateId, String flightDate,
                                       String lockedAtDate, String comment)
            throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyFlightId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        row.put("aircraft_id", aircraftId.toString());
        row.put("flight_date", flightDate);
        row.put("start_date_time", Instant.parse(flightDate + "T08:00:00Z").toString());
        row.put("ldg_date_time", Instant.parse(flightDate + "T10:00:00Z").toString());
        row.putNull("block_start_date_time");
        row.putNull("block_end_date_time");
        row.put("start_location_id", locationId.toString());
        row.put("ldg_location_id", locationId.toString());
        row.put("start_runway", "14");
        row.put("ldg_runway", "32");
        row.putNull("outbound_route");
        row.putNull("inbound_route");
        row.put("flight_type_id", flightTypeId.toString());
        row.put("is_solo_flight", false);
        row.put("start_type_id", new UUID(0L, LEGACY_START_TYPE_AEROTOW).toString());
        if (towFlightId == null) {
            row.putNull("tow_flight_id");
        } else {
            row.put("tow_flight_id", towFlightId);
        }
        row.put("nr_of_ldgs", 1);
        row.put("nr_of_ldgs_on_start_location", 1);
        row.put("no_start_time_information", false);
        row.put("no_ldg_time_information", false);
        // The mapper translates legacy AirStateId==5 -> ModifiedOn; else null.
        if (legacyAirStateId == LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN) {
            row.put("flight_plan_opened_on", Instant.parse(flightDate + "T07:30:00Z").toString());
        } else {
            row.putNull("flight_plan_opened_on");
        }
        row.put("process_state_id", new UUID(0L, legacyProcessStateId).toString());
        row.put("flight_aircraft_type_id", flightAircraftType);
        row.putNull("engine_start_operating_counter_in_seconds");
        row.putNull("engine_end_operating_counter_in_seconds");
        row.put("comment", comment);
        row.putNull("incident_comment");
        row.putNull("validation_errors");
        row.putNull("coupon_number");
        row.put("flight_cost_balance_type_id", new UUID(0L, LEGACY_FCBT_PILOT_PAYS_ALL).toString());
        row.putNull("delivery_created_on");
        row.putNull("validated_on");
        row.putNull("nr_of_passengers");
        row.putNull("start_position");
        row.putNull("flight_report_sent_on");
        row.put("created_on", Instant.parse(flightDate + "T06:00:00Z").toString());
        row.put("created_by_user_id", "00000000-0000-0000-0000-000000000000");
        // ModifiedOn doubles as the legacy lock-time proxy: for a Locked-or-beyond
        // flight the mapper sets locked_at = ModifiedOn. Use the billable date so
        // the bill gate (locked_at <= today-3d) would allow billing.
        String modifiedOn = lockedAtDate != null
                ? Instant.parse(lockedAtDate + "T12:00:00Z").toString()
                : Instant.parse(flightDate + "T12:00:00Z").toString();
        row.put("modified_on", modifiedOn);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        // The mapper sets locked_at = ModifiedOn for ProcessStateId >= 40, null otherwise.
        if (legacyProcessStateId >= LEGACY_PROCESS_STATE_LOCKED) {
            row.put("locked_at", modifiedOn);
        } else {
            row.putNull("locked_at");
        }
        return ndjsonLine(row);
    }

    /** NDJSON shaped as {@code FlightCrewMapper.writeNdjson}. */
    private static byte[] flightCrewNdjson(UUID legacyCrewId, UUID legacyFlightId,
                                           UUID personId, int legacyCrewType)
            throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyCrewId.toString());
        row.put("flight_id", legacyFlightId.toString());
        row.put("person_id", personId.toString());
        row.put("flight_crew_type_id", new UUID(0L, legacyCrewType).toString());
        row.putNull("begin_flight_datetime");
        row.putNull("end_flight_datetime");
        row.putNull("begin_instruction_datetime");
        row.putNull("end_instruction_datetime");
        row.put("nr_of_ldgs", 1);
        row.put("nr_of_starts", 1);
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private static byte[] flightTypeNdjson(UUID legacyFlightTypeId, UUID legacyClubId,
                                           String name, UUID actorUserId) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyFlightTypeId.toString());
        row.put("operating_club_id", legacyClubId.toString());
        row.put("flight_type_name", name);
        row.put("flight_code", "SCH");
        row.put("instructor_required", false);
        row.put("observer_pilot_or_instructor_required", false);
        row.put("is_check_flight", false);
        row.put("is_passenger_flight", false);
        row.put("is_solo_flight", false);
        row.put("is_for_glider_flights", true);
        row.put("is_for_tow_flights", false);
        row.put("is_for_motor_flights", false);
        row.put("is_flight_cost_balance_selectable", true);
        row.put("is_coupon_number_required", false);
        row.put("is_for_aircraft_reservation_type", false);
        row.putNull("min_nr_of_aircraft_seats_required");
        String created = Instant.parse("2020-06-15T00:00:00Z").toString();
        row.put("created_on", created);
        row.put("created_by_user_id", actorUserId.toString());
        row.put("modified_on", created);
        row.putNull("modified_by_user_id");
        row.putNull("deleted_on");
        row.putNull("deleted_by_user_id");
        return ndjsonLine(row);
    }

    private static byte[] aircraftNdjson(UUID legacyAircraftId, UUID legacyClubId,
                                         String immatriculation, int legacyAircraftType,
                                         UUID actorUserId) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("legacy_guid", legacyAircraftId.toString());
        row.put("managing_club_id", legacyClubId.toString());
        row.put("owner_club_id", legacyClubId.toString());
        // AircraftType is the airframe type (not the flight discriminator); any
        // glider seed PK resolves — only the FK target matters here.
        row.put("aircraft_type_id",
                Coercions.legacyIntIdToUuidString(LEGACY_AIRCRAFT_TYPE_GLIDER));
        row.put("manufacturer_name", "Schleicher");
        row.put("aircraft_model", "ASK 21");
        row.put("immatriculation", immatriculation);
        row.putNull("competition_sign");
        row.putNull("flarm_id");
        row.putNull("aircraft_serial_number");
        row.putNull("year_of_manufacture");
        row.putNull("noise_class");
        row.putNull("noise_level");
        row.putNull("mtom");
        row.put("nr_of_seats", 2);
        row.putNull("aircraft_owner_person_id");
        row.putNull("flight_operating_counter_unit_type_id");
        row.putNull("engine_operating_counter_unit_type_id");
        row.putNull("homebase_id");
        row.putNull("spot_link");
        row.put("is_towing_or_winch_required", false);
        row.put("is_towing_start_allowed", true);
        row.put("is_winch_start_allowed", true);
        row.put("is_towing_aircraft", legacyAircraftType == LEGACY_AIRCRAFT_TYPE_TOW);
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

    private static byte[] locationNdjson(UUID legacyLocationId, UUID legacyClubId,
                                         UUID countryId, String icao, String name,
                                         UUID actorUserId) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("id", Coercions.deriveFanOutId(legacyLocationId, legacyClubId).toString());
        row.put("legacy_guid", legacyLocationId.toString());
        row.put("club_id", legacyClubId.toString());
        row.put("location_name", name);
        row.put("location_short_name", icao);
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
        row.put("description", "J2 flight parity airfield");
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

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            sink.write(part, 0, part.length);
        }
        return sink.toByteArray();
    }

    private static byte[] pgcopyMap(UUID legacyGuid, UUID newUuid) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            writer.write(legacyGuid, newUuid);
        }
        return sink.toByteArray();
    }

    private record MapRow(UUID legacyGuid, UUID newUuid) { }

    private static byte[] pgcopyMap3(MapRow... rows) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (LegacyIdMapWriter writer = new LegacyIdMapWriter(sink)) {
            for (MapRow row : rows) {
                writer.write(row.legacyGuid(), row.newUuid());
            }
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
