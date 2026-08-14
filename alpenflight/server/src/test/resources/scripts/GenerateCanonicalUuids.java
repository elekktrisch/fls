
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GenerateCanonicalUuids {

    private static final long TIMESTAMP_MS = 1778889600000L;

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


        String[] aircraftTypes = {
                "UNKNOWN", "GLIDER", "GLIDER_WITH_MOTOR", "MOTOR_GLIDER",
                "MOTOR_AIRCRAFT", "MULTI_ENGINE", "JET", "HELICOPTER"};
        out.println();
        out.println("# aircraft_type:");
        for (int i = 0; i < aircraftTypes.length; i++) {
            out.printf("  %s = %s%n", aircraftTypes[i], uuidV7(TABLE_OFFSETS.get("aircraft_type") + i));
        }

        String[] aircraftStates = {
                "OK", "INFORMATION", "ATTENTION", "MALFUNCTION",
                "MAINTENANCE", "UNINSURED", "END_OF_LIFE"};
        out.println();
        out.println("# aircraft_state:");
        for (int i = 0; i < aircraftStates.length; i++) {
            out.printf("  %s = %s%n", aircraftStates[i], uuidV7(TABLE_OFFSETS.get("aircraft_state") + i));
        }

        String[] locationTypes = {
                "WAYPOINT", "GRASS_RUNWAY", "EXTERNAL_FIELD",
                "GLIDER_AIRFIELD", "CONCRETE_RUNWAY", "OTHER"};
        out.println();
        out.println("# location_type:");
        for (int i = 0; i < locationTypes.length; i++) {
            out.printf("  %s = %s%n", locationTypes[i], uuidV7(TABLE_OFFSETS.get("location_type") + i));
        }

        String[] flightCrewTypes = {
                "PILOT_OR_STUDENT", "CO_PILOT", "FLIGHT_INSTRUCTOR", "PASSENGER",
                "WINCH_OPERATOR", "OBSERVER", "FLIGHT_COST_INVOICE_RECIPIENT"};
        out.println();
        out.println("# flight_crew_type:");
        for (int i = 0; i < flightCrewTypes.length; i++) {
            out.printf("  %s = %s%n", flightCrewTypes[i], uuidV7(TABLE_OFFSETS.get("flight_crew_type") + i));
        }

        String[] flightProcessStates = {
                "NOT_PROCESSED", "INVALID", "VALID", "LOCKED",
                "DELIVERY_PREPARATION_ERROR", "DELIVERY_PREPARED", "DELIVERY_BOOKED",
                "EXCLUDED_FROM_DELIVERY_PROCESS"};
        out.println();
        out.println("# flight_process_state:");
        for (int i = 0; i < flightProcessStates.length; i++) {
            out.printf("  %s = %s%n", flightProcessStates[i], uuidV7(TABLE_OFFSETS.get("flight_process_state") + i));
        }

        String[] flightAirStates = {
                "NEW", "FLIGHT_PLAN_OPEN", "MIGHT_BE_STARTED", "STARTED",
                "MIGHT_BE_LANDED_OR_IN_AIR", "LANDED", "FLIGHT_PLAN_CLOSED"};
        out.println();
        out.println("# flight_air_state:");
        for (int i = 0; i < flightAirStates.length; i++) {
            out.printf("  %s = %s%n", flightAirStates[i], uuidV7(TABLE_OFFSETS.get("flight_air_state") + i));
        }

        String[] flightCostBalanceTypes = {
                "PILOT_PAYS_ALL", "FIFTY_FIFTY_PILOT_COPILOT", "TOW_PILOT_PAYS_TOW",
                "NO_INSTRUCTOR_FEE", "INVOICE_TO_PERSON"};
        out.println();
        out.println("# flight_cost_balance_type:");
        for (int i = 0; i < flightCostBalanceTypes.length; i++) {
            out.printf("  %s = %s%n",
                    flightCostBalanceTypes[i], uuidV7(TABLE_OFFSETS.get("flight_cost_balance_type") + i));
        }


        String[] accountingRuleFilterTypes = {
                "RECIPIENT", "NO_LANDING_TAX", "FLIGHT_TIME", "INSTRUCTOR_FEE",
                "ADDITIONAL_FUEL_FEE", "LANDING_TAX", "VSF_FEE", "ENGINE_TIME",
                "DO_NOT_INVOICE", "START_TAX"};
        out.println();
        out.println("# accounting_rule_filter_type:");
        for (int i = 0; i < accountingRuleFilterTypes.length; i++) {
            out.printf("  %s = %s%n",
                    accountingRuleFilterTypes[i],
                    uuidV7(TABLE_OFFSETS.get("accounting_rule_filter_type") + i));
        }

        String[] accountingUnitTypes = {
                "MINUTES", "SECONDS", "LANDINGS", "START_OR_FLIGHT"};
        out.println();
        out.println("# accounting_unit_type:");
        for (int i = 0; i < accountingUnitTypes.length; i++) {
            out.printf("  %s = %s%n",
                    accountingUnitTypes[i],
                    uuidV7(TABLE_OFFSETS.get("accounting_unit_type") + i));
        }
    }
}
