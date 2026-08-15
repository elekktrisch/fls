
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GenerateCanonicalUuids {

    private static final long TIMESTAMP_MS = Instant.parse("2026-05-16T00:00:00Z").toEpochMilli();

    private static final Map<String, Long> TABLE_OFFSETS = new java.util.LinkedHashMap<>();
    static {
        TABLE_OFFSETS.put("country",                  1_000L);
        TABLE_OFFSETS.put("language",                 2_000L);
        TABLE_OFFSETS.put("club_state",               3_000L);
        TABLE_OFFSETS.put("start_type",               4_000L);
        TABLE_OFFSETS.put("length_unit_type",         5_000L);
        TABLE_OFFSETS.put("elevation_unit_type",      6_000L);
        TABLE_OFFSETS.put("counter_unit_type",        7_000L);
        TABLE_OFFSETS.put("extension_type",           8_000L);
        TABLE_OFFSETS.put("role",                     9_000L);
        TABLE_OFFSETS.put("email_template",          10_000L);

        TABLE_OFFSETS.put("aircraft_type",           11_000L);
        TABLE_OFFSETS.put("aircraft_state",          12_000L);
        TABLE_OFFSETS.put("location_type",           13_000L);
        TABLE_OFFSETS.put("flight_crew_type",        14_000L);
        TABLE_OFFSETS.put("flight_process_state",    15_000L);
        TABLE_OFFSETS.put("flight_air_state",        16_000L);
        TABLE_OFFSETS.put("flight_cost_balance_type",17_000L);

        TABLE_OFFSETS.put("accounting_rule_filter_type", 18_000L);
        TABLE_OFFSETS.put("accounting_unit_type",        19_000L);
    }

    static String uuidV7(long counter) {
        long ts = TIMESTAMP_MS & ((1L << 48) - 1L);
        long verAndRandA = (0x7L << 12) | (counter & 0xFFFL);
        long varAndRandB = (0b10L << 62) | (counter & ((1L << 62) - 1L));
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

    static void printTable(PrintStream out, String tableName,
                           String[] codesInAppendOnlyIndexOrder) {
        printTable(out, tableName, tableName, codesInAppendOnlyIndexOrder);
    }

    static void printTable(PrintStream out, String tableName, String heading,
                           String[] codesInAppendOnlyIndexOrder) {
        out.println();
        out.println("# " + heading + ":");
        for (int i = 0; i < codesInAppendOnlyIndexOrder.length; i++) {
            out.printf("  %s = %s%n", codesInAppendOnlyIndexOrder[i],
                    uuidV7(TABLE_OFFSETS.get(tableName) + i));
        }
    }

    record Row(String tableName, String naturalKey, int index) {
        String uuid() {
            return uuidV7(TABLE_OFFSETS.get(tableName) + index);
        }
    }

    public static void main(String[] args) {
        PrintStream out = System.out;

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
        printTable(out, "country", "country (ISO 3166-1 alpha-2 → UUID v7)", countryIso2);

        String[] languageCodes = {"de", "fr", "it", "en", "rm", "de-CH", "fr-CH", "it-CH"};
        printTable(out, "language", "language (BCP-47 → UUID v7)", languageCodes);

        String[] clubStates = {"ACTIVE", "SUSPENDED", "CLOSED"};
        printTable(out, "club_state", clubStates);

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
        printTable(out, "length_unit_type", lengthUnits);

        String[] elevationUnits = {"METER", "FEET"};
        printTable(out, "elevation_unit_type", elevationUnits);

        String[] counterUnits = {"HOURS_DECIMAL", "HOURS_MINUTES", "LANDINGS", "STARTS"};
        printTable(out, "counter_unit_type", counterUnits);

        String[] extensionTypes = {"STRING", "INTEGER", "BOOLEAN", "DATE", "LIST"};
        printTable(out, "extension_type", extensionTypes);

        String[] roles = {"ADMIN", "FLIGHT_OPS", "INSTRUCTOR", "PILOT", "READER"};
        printTable(out, "role", roles);

        String[] aircraftTypes = {
                "UNKNOWN", "GLIDER", "GLIDER_WITH_MOTOR", "MOTOR_GLIDER",
                "MOTOR_AIRCRAFT", "MULTI_ENGINE", "JET", "HELICOPTER"};
        printTable(out, "aircraft_type", aircraftTypes);

        String[] aircraftStates = {
                "OK", "INFORMATION", "ATTENTION", "MALFUNCTION",
                "MAINTENANCE", "UNINSURED", "END_OF_LIFE"};
        printTable(out, "aircraft_state", aircraftStates);

        String[] locationTypes = {
                "WAYPOINT", "GRASS_RUNWAY", "EXTERNAL_FIELD",
                "GLIDER_AIRFIELD", "CONCRETE_RUNWAY", "OTHER"};
        printTable(out, "location_type", locationTypes);

        String[] flightCrewTypes = {
                "PILOT_OR_STUDENT", "CO_PILOT", "FLIGHT_INSTRUCTOR", "PASSENGER",
                "WINCH_OPERATOR", "OBSERVER", "FLIGHT_COST_INVOICE_RECIPIENT"};
        printTable(out, "flight_crew_type", flightCrewTypes);

        String[] flightProcessStates = {
                "NOT_PROCESSED", "INVALID", "VALID", "LOCKED",
                "DELIVERY_PREPARATION_ERROR", "DELIVERY_PREPARED", "DELIVERY_BOOKED",
                "EXCLUDED_FROM_DELIVERY_PROCESS"};
        printTable(out, "flight_process_state", flightProcessStates);

        String[] flightAirStates = {
                "NEW", "FLIGHT_PLAN_OPEN", "MIGHT_BE_STARTED", "STARTED",
                "MIGHT_BE_LANDED_OR_IN_AIR", "LANDED", "FLIGHT_PLAN_CLOSED"};
        printTable(out, "flight_air_state", flightAirStates);

        String[] flightCostBalanceTypes = {
                "PILOT_PAYS_ALL", "FIFTY_FIFTY_PILOT_COPILOT", "TOW_PILOT_PAYS_TOW",
                "NO_INSTRUCTOR_FEE", "INVOICE_TO_PERSON"};
        printTable(out, "flight_cost_balance_type", flightCostBalanceTypes);

        String[] accountingRuleFilterTypes = {
                "RECIPIENT", "NO_LANDING_TAX", "FLIGHT_TIME", "INSTRUCTOR_FEE",
                "ADDITIONAL_FUEL_FEE", "LANDING_TAX", "VSF_FEE", "ENGINE_TIME",
                "DO_NOT_INVOICE", "START_TAX"};
        printTable(out, "accounting_rule_filter_type", accountingRuleFilterTypes);

        String[] accountingUnitTypes = {
                "MINUTES", "SECONDS", "LANDINGS", "START_OR_FLIGHT"};
        printTable(out, "accounting_unit_type", accountingUnitTypes);
    }
}
