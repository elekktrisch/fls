// Canonical seed-UUID generator for S-012 + S-013 (and any future story that
// extends reference-data seeds against a clean baseline).
//
// THIS SCRIPT IS DOCUMENTATION, NOT BUILD INPUT.
// The migration V2__identity_and_reference.sql embeds the UUID literals
// produced by this script, and reference-seeds-canonical-uuids.json captures
// the same output as a test-time oracle. Do not regenerate after the
// migration has shipped to any environment — Flyway checksum-locks V2; a
// UUID change requires a follow-up migration with cascading FK updates.
//
// Run as a Java single-file source-code program (JEP 330, Java 11+):
//   java next/server/src/test/resources/scripts/GenerateCanonicalUuids.java
//
// Algorithm (RFC 9562 UUID v7, deterministic by construction):
//   - 48-bit timestamp prefix is fixed at 2026-05-16T00:00:00Z
//     (1778889600000 ms). This is the date S-012 lands.
//   - The "random" bits encode the row's per-table counter ordinal.
//     rand_a (12 bits) carries counter & 0xFFF; rand_b (62 bits) carries
//     counter & ((1<<62)-1). Version=7, variant=10 bits set per RFC 9562.
//   - Per-table offsets (1000 apart) ensure adding rows to one table never
//     renumbers neighbours.
//   - Re-running this script produces bit-identical output forever.
//
// Hibernate 7 + Spring Boot 4 will use com.github.f4b6a3:uuid-creator
// (UuidCreator.getTimeOrderedEpoch()) for ID generation at S-022 — that is
// the *runtime* generator. THIS script is a one-shot snapshot tool whose
// output is reviewable bit-for-bit; SecureRandom-based UUIDs from
// uuid-creator are non-deterministic and unsuitable here.

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GenerateCanonicalUuids {

    private static final long TIMESTAMP_MS = 1778889600000L; // 2026-05-16T00:00:00Z

    private static final Map<String, Long> TABLE_OFFSETS = new java.util.LinkedHashMap<>();
    static {
        // S-012 reference tables (offsets locked; Flyway checksum-protected once shipped).
        TABLE_OFFSETS.put("country",                  1_000L);
        TABLE_OFFSETS.put("language",                 2_000L);
        TABLE_OFFSETS.put("club_state",               3_000L);
        TABLE_OFFSETS.put("start_type",               4_000L);
        TABLE_OFFSETS.put("length_unit_type",         5_000L);
        TABLE_OFFSETS.put("elevation_unit_type",      6_000L);
        TABLE_OFFSETS.put("counter_unit_type",        7_000L);
        TABLE_OFFSETS.put("extension_type",           8_000L);
        TABLE_OFFSETS.put("role",                     9_000L);
        TABLE_OFFSETS.put("email_template",          10_000L); // system-default rows (club_id IS NULL)

        // S-013 reference tables.
        TABLE_OFFSETS.put("aircraft_type",           11_000L);
        TABLE_OFFSETS.put("aircraft_state",          12_000L);
        TABLE_OFFSETS.put("location_type",           13_000L);
        TABLE_OFFSETS.put("flight_crew_type",        14_000L);
        TABLE_OFFSETS.put("flight_process_state",    15_000L);
        TABLE_OFFSETS.put("flight_air_state",        16_000L);
        TABLE_OFFSETS.put("flight_cost_balance_type",17_000L);
    }

    static String uuidV7(long counter) {
        long ts = TIMESTAMP_MS & ((1L << 48) - 1L);
        long verAndRandA = (0x7L << 12) | (counter & 0xFFFL);          // 4 bits version + 12 bits rand_a
        long varAndRandB = (0b10L << 62) | (counter & ((1L << 62) - 1L)); // 2 bits variant + 62 bits rand_b
        long high64 = (ts << 16) | verAndRandA;
        long low64 = varAndRandB;
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) ((high64 >>> (56 - i * 8)) & 0xFF);
            bytes[8 + i] = (byte) ((low64 >>> (56 - i * 8)) & 0xFF);
        }
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    record Row(String tableName, String naturalKey, int index) {
        String uuid() {
            return uuidV7(TABLE_OFFSETS.get(tableName) + index);
        }
    }

    public static void main(String[] args) {
        PrintStream out = System.out;

        // Country: ISO 3166-1 alpha-2 codes (248 rows; AC requires >= 196).
        // Switzerland is the sacred-cow row tests pin against by UUID.
        String[] countryIso2 = {
                "AF","AL","DZ","AS","AD","AO","AI","AQ","AG","AR","AM","AW","AU","AT","AZ",
                "BS","BH","BD","BB","BY","BE","BZ","BJ","BM","BT","BO","BQ","BA","BW","BV",
                "BR","IO","BN","BG","BF","BI","CV","KH","CM","CA","KY","CF","TD","CL","CN",
                "CX","CC","CO","KM","CD","CG","CK","CR","CI","HR","CU","CW","CY","CZ","DK",
                "DJ","DM","DO","EC","EG","SV","GQ","ER","EE","SZ","ET","FK","FO","FJ","FI",
                "FR","GF","PF","TF","GA","GM","GE","DE","GH","GI","GR","GL","GD","GP","GU",
                "GT","GG","GN","GW","GY","HT","HM","VA","HN","HK","HU","IS","IN","ID","IR",
                "IQ","IE","IM","IL","IT","JM","JP","JE","JO","KZ","KE","KI","KP","KR","KW",
                "KG","LA","LV","LB","LS","LR","LY","LI","LT","LU","MO","MG","MW","MY","MV",
                "ML","MT","MH","MQ","MR","MU","YT","MX","FM","MD","MC","MN","ME","MS","MA",
                "MZ","MM","NA","NR","NP","NL","NC","NZ","NI","NE","NG","NU","NF","MK","MP",
                "NO","OM","PK","PW","PS","PA","PG","PY","PE","PH","PN","PL","PT","PR","QA",
                "RE","RO","RU","RW","BL","SH","KN","LC","MF","PM","VC","WS","SM","ST","SA",
                "SN","RS","SC","SL","SG","SX","SK","SI","SB","SO","ZA","GS","SS","ES","LK",
                "SD","SR","SJ","SE","CH","SY","TW","TJ","TZ","TH","TL","TG","TK","TO","TT",
                "TN","TR","TM","TC","TV","UG","UA","AE","GB","US","UM","UY","UZ","VU","VE",
                "VN","VG","VI","WF","EH","YE","ZM","ZW"
        };

        out.println("# Canonical seed UUIDs — output of GenerateCanonicalUuids.java");
        out.println("# Re-run produces bit-identical output (deterministic by construction).");
        out.println();
        out.println("# country (ISO 3166-1 alpha-2 → UUID v7):");
        for (int i = 0; i < countryIso2.length; i++) {
            out.printf("  %s = %s%n", countryIso2[i], uuidV7(TABLE_OFFSETS.get("country") + i));
        }

        String[] languageCodes = {"de", "fr", "it", "en", "rm", "de-CH", "fr-CH", "it-CH"};
        out.println();
        out.println("# language (BCP-47 → UUID v7):");
        for (int i = 0; i < languageCodes.length; i++) {
            out.printf("  %s = %s%n", languageCodes[i], uuidV7(TABLE_OFFSETS.get("language") + i));
        }

        String[] clubStates = {"ACTIVE", "SUSPENDED", "CLOSED"};
        out.println();
        out.println("# club_state:");
        for (int i = 0; i < clubStates.length; i++) {
            out.printf("  %s = %s%n", clubStates[i], uuidV7(TABLE_OFFSETS.get("club_state") + i));
        }

        // start_type carries applicable_categories TEXT[] per ADR 0020 (collapse
        // of the legacy is_for_glider/tow/motor boolean trio into a SET-MEMBERSHIP
        // column). The {code -> [categories]} mapping is committed in the
        // migration; this script emits only the UUID-per-code mapping.
        String[][] startTypes = {
                {"WINCH_LAUNCH",   "[GLIDER]"},
                {"AEROTOW",        "[GLIDER, TOW]"},
                {"SELF_START",     "[GLIDER]"},
                {"EXTERNAL_START", "[GLIDER]"},
                {"MOTOR",          "[MOTOR]"},
        };
        out.println();
        out.println("# start_type:");
        for (int i = 0; i < startTypes.length; i++) {
            out.printf("  %s applicable_categories=%s uuid=%s%n",
                    startTypes[i][0], startTypes[i][1],
                    uuidV7(TABLE_OFFSETS.get("start_type") + i));
        }

        String[] lengthUnits = {"METER", "FEET"};
        out.println();
        out.println("# length_unit_type:");
        for (int i = 0; i < lengthUnits.length; i++) {
            out.printf("  %s = %s%n", lengthUnits[i], uuidV7(TABLE_OFFSETS.get("length_unit_type") + i));
        }

        String[] elevationUnits = {"METER", "FEET"};
        out.println();
        out.println("# elevation_unit_type:");
        for (int i = 0; i < elevationUnits.length; i++) {
            out.printf("  %s = %s%n", elevationUnits[i], uuidV7(TABLE_OFFSETS.get("elevation_unit_type") + i));
        }

        String[] counterUnits = {"HOURS_DECIMAL", "HOURS_MINUTES", "LANDINGS", "STARTS"};
        out.println();
        out.println("# counter_unit_type:");
        for (int i = 0; i < counterUnits.length; i++) {
            out.printf("  %s = %s%n", counterUnits[i], uuidV7(TABLE_OFFSETS.get("counter_unit_type") + i));
        }

        String[] extensionTypes = {"STRING", "INTEGER", "BOOLEAN", "DATE", "LIST"};
        out.println();
        out.println("# extension_type:");
        for (int i = 0; i < extensionTypes.length; i++) {
            out.printf("  %s = %s%n", extensionTypes[i], uuidV7(TABLE_OFFSETS.get("extension_type") + i));
        }

        String[] roles = {"ADMIN", "FLIGHT_OPS", "INSTRUCTOR", "PILOT", "READER"};
        out.println();
        out.println("# role:");
        for (int i = 0; i < roles.length; i++) {
            out.printf("  %s = %s%n", roles[i], uuidV7(TABLE_OFFSETS.get("role") + i));
        }

        // S-013 reference tables — order matters: matches the per-table row
        // ordinals embedded in V3__flights_aircraft_locations.sql.

        // aircraft_type — bit-field codes preserved as legacy_int_id (sparse: 0,1,2,4,8,16,32,64).
        String[] aircraftTypes = {
                "UNKNOWN", "GLIDER", "GLIDER_WITH_MOTOR", "MOTOR_GLIDER",
                "MOTOR_AIRCRAFT", "MULTI_ENGINE", "JET", "HELICOPTER"};
        out.println();
        out.println("# aircraft_type:");
        for (int i = 0; i < aircraftTypes.length; i++) {
            out.printf("  %s = %s%n", aircraftTypes[i], uuidV7(TABLE_OFFSETS.get("aircraft_type") + i));
        }

        // aircraft_state — per AircraftStateKey.cs; legacy_int_id sparse (1..6, 99).
        String[] aircraftStates = {
                "OK", "INFORMATION", "ATTENTION", "MALFUNCTION",
                "MAINTENANCE", "UNINSURED", "END_OF_LIFE"};
        out.println();
        out.println("# aircraft_state:");
        for (int i = 0; i < aircraftStates.length; i++) {
            out.printf("  %s = %s%n", aircraftStates[i], uuidV7(TABLE_OFFSETS.get("aircraft_state") + i));
        }

        // location_type — 6 rows from legacy "3 Insert Static Data.sql"; LocationTypeCupId in {1,2,3,4,5,99}.
        String[] locationTypes = {
                "WAYPOINT", "GRASS_RUNWAY", "EXTERNAL_FIELD",
                "GLIDER_AIRFIELD", "CONCRETE_RUNWAY", "OTHER"};
        out.println();
        out.println("# location_type:");
        for (int i = 0; i < locationTypes.length; i++) {
            out.printf("  %s = %s%n", locationTypes[i], uuidV7(TABLE_OFFSETS.get("location_type") + i));
        }

        // flight_crew_type — 7 rows from legacy seed; legacy_int_id in {1..6, 10}.
        String[] flightCrewTypes = {
                "PILOT_OR_STUDENT", "CO_PILOT", "FLIGHT_INSTRUCTOR", "PASSENGER",
                "WINCH_OPERATOR", "OBSERVER", "FLIGHT_COST_INVOICE_RECIPIENT"};
        out.println();
        out.println("# flight_crew_type:");
        for (int i = 0; i < flightCrewTypes.length; i++) {
            out.printf("  %s = %s%n", flightCrewTypes[i], uuidV7(TABLE_OFFSETS.get("flight_crew_type") + i));
        }

        // flight_process_state — per FlightProcessState.cs enum; legacy_int_id in {0,28,30,40,45,50,60,99}.
        String[] flightProcessStates = {
                "NOT_PROCESSED", "INVALID", "VALID", "LOCKED",
                "DELIVERY_PREPARATION_ERROR", "DELIVERY_PREPARED", "DELIVERY_BOOKED",
                "EXCLUDED_FROM_DELIVERY_PROCESS"};
        out.println();
        out.println("# flight_process_state:");
        for (int i = 0; i < flightProcessStates.length; i++) {
            out.printf("  %s = %s%n", flightProcessStates[i], uuidV7(TABLE_OFFSETS.get("flight_process_state") + i));
        }

        // flight_air_state — per FlightAirState.cs enum; legacy_int_id in {0,5,8,10,15,20,25}.
        String[] flightAirStates = {
                "NEW", "FLIGHT_PLAN_OPEN", "MIGHT_BE_STARTED", "STARTED",
                "MIGHT_BE_LANDED_OR_IN_AIR", "LANDED", "FLIGHT_PLAN_CLOSED"};
        out.println();
        out.println("# flight_air_state:");
        for (int i = 0; i < flightAirStates.length; i++) {
            out.printf("  %s = %s%n", flightAirStates[i], uuidV7(TABLE_OFFSETS.get("flight_air_state") + i));
        }

        // flight_cost_balance_type — 5 rows from legacy seed; is_for_glider / is_for_tow /
        // is_for_motor flags re-pinned per design notes (legacy TOW_PILOT_PAYS_TOW row had
        // all flags = 0, structurally wrong vs. the at-least-one CHECK).
        String[] flightCostBalanceTypes = {
                "PILOT_PAYS_ALL", "FIFTY_FIFTY_PILOT_COPILOT", "TOW_PILOT_PAYS_TOW",
                "NO_INSTRUCTOR_FEE", "INVOICE_TO_PERSON"};
        out.println();
        out.println("# flight_cost_balance_type:");
        for (int i = 0; i < flightCostBalanceTypes.length; i++) {
            out.printf("  %s = %s%n",
                    flightCostBalanceTypes[i], uuidV7(TABLE_OFFSETS.get("flight_cost_balance_type") + i));
        }
    }
}
