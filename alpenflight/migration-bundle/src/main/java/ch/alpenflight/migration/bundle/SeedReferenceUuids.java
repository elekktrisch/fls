package ch.alpenflight.migration.bundle;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class SeedReferenceUuids {

    private static final long CANONICAL_UUID_TIMESTAMP_MS = 1778889600000L;

    private static final long COUNTRY_OFFSET = 1_000L;
    private static final long LANGUAGE_OFFSET = 2_000L;
    private static final long CLUB_STATE_OFFSET = 3_000L;
    private static final long START_TYPE_OFFSET = 4_000L;

    private static final String[] COUNTRY_ISO2_IN_V2_SEED_ORDER = {
            "AF", "AL", "DZ", "AS", "AD", "AO", "AI", "AQ", "AG", "AR", "AM", "AW", "AU", "AT", "AZ",
            "BS", "BH", "BD", "BB", "BY", "BE", "BZ", "BJ", "BM", "BT", "BO", "BQ", "BA", "BW", "BV",
            "BR", "IO", "BN", "BG", "BF", "BI", "CV", "KH", "CM", "CA", "KY", "CF", "TD", "CL", "CN",
            "CX", "CC", "CO", "KM", "CD", "CG", "CK", "CR", "CI", "HR", "CU", "CW", "CY", "CZ", "DK",
            "DJ", "DM", "DO", "EC", "EG", "SV", "GQ", "ER", "EE", "SZ", "ET", "FK", "FO", "FJ", "FI",
            "FR", "GF", "PF", "TF", "GA", "GM", "GE", "DE", "GH", "GI", "GR", "GL", "GD", "GP", "GU",
            "GT", "GG", "GN", "GW", "GY", "HT", "HM", "VA", "HN", "HK", "HU", "IS", "IN", "ID", "IR",
            "IQ", "IE", "IM", "IL", "IT", "JM", "JP", "JE", "JO", "KZ", "KE", "KI", "KP", "KR", "KW",
            "KG", "LA", "LV", "LB", "LS", "LR", "LY", "LI", "LT", "LU", "MO", "MG", "MW", "MY", "MV",
            "ML", "MT", "MH", "MQ", "MR", "MU", "YT", "MX", "FM", "MD", "MC", "MN", "ME", "MS", "MA",
            "MZ", "MM", "NA", "NR", "NP", "NL", "NC", "NZ", "NI", "NE", "NG", "NU", "NF", "MK", "MP",
            "NO", "OM", "PK", "PW", "PS", "PA", "PG", "PY", "PE", "PH", "PN", "PL", "PT", "PR", "QA",
            "RE", "RO", "RU", "RW", "BL", "SH", "KN", "LC", "MF", "PM", "VC", "WS", "SM", "ST", "SA",
            "SN", "RS", "SC", "SL", "SG", "SX", "SK", "SI", "SB", "SO", "ZA", "GS", "SS", "ES", "LK",
            "SD", "SR", "SJ", "SE", "CH", "SY", "TW", "TJ", "TZ", "TH", "TL", "TG", "TK", "TO", "TT",
            "TN", "TR", "TM", "TC", "TV", "UG", "UA", "AE", "GB", "US", "UM", "UY", "UZ", "VU", "VE",
            "VN", "VG", "VI", "WF", "EH", "YE", "ZM", "ZW"
    };

    private static final String[] LANGUAGE_CODES_IN_V2_SEED_ORDER =
            {"de", "fr", "it", "en", "rm", "de-CH", "fr-CH", "it-CH"};

    private static final String[] CLUB_STATE_CODES_IN_V2_SEED_ORDER = {"ACTIVE", "SUSPENDED", "CLOSED"};

    private static final String[] START_TYPE_CODES_IN_V2_SEED_ORDER =
            {"WINCH_LAUNCH", "AEROTOW", "SELF_START", "EXTERNAL_START", "MOTOR"};

    private static final Map<String, UUID> COUNTRY_BY_ISO2 =
            indexByNaturalKey(COUNTRY_ISO2_IN_V2_SEED_ORDER, COUNTRY_OFFSET);
    private static final Map<String, UUID> CLUB_STATE_BY_CODE =
            indexByNaturalKey(CLUB_STATE_CODES_IN_V2_SEED_ORDER, CLUB_STATE_OFFSET);
    private static final Map<String, UUID> START_TYPE_BY_CODE =
            indexByNaturalKey(START_TYPE_CODES_IN_V2_SEED_ORDER, START_TYPE_OFFSET);

    private static final Map<String, UUID> LANGUAGE_BY_CODE =
            indexByExactKey(LANGUAGE_CODES_IN_V2_SEED_ORDER, LANGUAGE_OFFSET);

    private static final Map<String, UUID> LANGUAGE_BY_LOWER_CODE =
            indexByLowerKey(LANGUAGE_CODES_IN_V2_SEED_ORDER, LANGUAGE_OFFSET);

    private SeedReferenceUuids() {
    }

    public static @Nullable UUID countryByIso2(@Nullable String iso2) {
        return iso2 == null ? null : COUNTRY_BY_ISO2.get(iso2.trim().toUpperCase(Locale.ROOT));
    }

    public static @Nullable UUID clubStateByCode(@Nullable String code) {
        return code == null ? null : CLUB_STATE_BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
    }

    public static @Nullable UUID languageByCode(@Nullable String code) {
        return code == null ? null : LANGUAGE_BY_LOWER_CODE.get(code.trim().toLowerCase(Locale.ROOT));
    }

    public static @Nullable UUID startTypeByCode(@Nullable String code) {
        return code == null ? null : START_TYPE_BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
    }

    public static Map<String, UUID> countriesByIso2() {
        return Map.copyOf(COUNTRY_BY_ISO2);
    }

    public static Map<String, UUID> startTypesByCode() {
        return Map.copyOf(START_TYPE_BY_CODE);
    }

    public static Map<String, UUID> clubStatesByCode() {
        return Map.copyOf(CLUB_STATE_BY_CODE);
    }

    public static Map<String, UUID> languagesByCode() {
        return Map.copyOf(LANGUAGE_BY_CODE);
    }

    private static Map<String, UUID> indexByNaturalKey(String[] keys, long offset) {
        Map<String, UUID> map = new LinkedHashMap<>(keys.length * 2);
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i].toUpperCase(Locale.ROOT), uuidV7(offset + i));
        }
        return map;
    }

    private static Map<String, UUID> indexByExactKey(String[] keys, long offset) {
        Map<String, UUID> map = new LinkedHashMap<>(keys.length * 2);
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], uuidV7(offset + i));
        }
        return map;
    }

    private static Map<String, UUID> indexByLowerKey(String[] keys, long offset) {
        Map<String, UUID> map = new LinkedHashMap<>(keys.length * 2);
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i].toLowerCase(Locale.ROOT), uuidV7(offset + i));
        }
        return map;
    }

    private static final long UUID_VERSION_7 = 0x7L;
    private static final long UUID_VARIANT_RFC9562 = 0b10L;
    private static final int TIMESTAMP_BITS = 48;
    private static final int RAND_A_BITS = 12;
    private static final int RAND_B_BITS = 62;
    private static final int VERSION_PLUS_RAND_A_BITS = 16;

    static UUID uuidV7(long counter) {
        long timestampPrefix = CANONICAL_UUID_TIMESTAMP_MS & ((1L << TIMESTAMP_BITS) - 1L);
        long versionAndRandA =
                (UUID_VERSION_7 << RAND_A_BITS) | (counter & ((1L << RAND_A_BITS) - 1L));
        long variantAndRandB =
                (UUID_VARIANT_RFC9562 << RAND_B_BITS) | (counter & ((1L << RAND_B_BITS) - 1L));
        long high64 = (timestampPrefix << VERSION_PLUS_RAND_A_BITS) | versionAndRandA;
        return new UUID(high64, variantAndRandB);
    }
}
