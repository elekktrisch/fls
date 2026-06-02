package ch.alpenflight.migration.bundle;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Single source of truth for the deterministic seed UUIDs the Flyway baseline
 * {@code V2__identity_and_reference.sql} embeds for the SYSTEM_GLOBAL reference
 * tables {@code t_country}, {@code t_club_state} and {@code t_language}.
 *
 * <p><strong>Why this exists (J-0c T-15).</strong> The new stack seeds COUNTRY
 * and CLUB_STATE with a fixed, deterministic catalogue keyed by
 * {@code uuidV7(offset + ordinal)} (see
 * {@code server/src/test/resources/scripts/GenerateCanonicalUuids.java}). Legacy
 * FLS has its own ~196 Country GUIDs and an INT-keyed ClubStates catalogue that
 * do NOT match those seed PKs. The migration producer therefore must RESOLVE a
 * legacy system-global reference to the corresponding new-stack seed PK before
 * it lands in the bundle — otherwise {@code t_club.country_id} /
 * {@code t_club.club_state_id} FK-violate at provisioning time (the GUID/INT is
 * absent from the seed).
 *
 * <p>This class replicates the {@code GenerateCanonicalUuids} derivation for the
 * two tables the migration producer resolves against, keyed by the stable
 * natural key (ISO 3166-1 alpha-2 for country, the V2 lifecycle code for
 * club-state). The producer joins legacy rows to this natural key, then reads
 * the seed PK here.
 *
 * <p><strong>Brittleness guard (mandatory — T-15).</strong> Because the producer
 * now couples to the seed values, a re-order or regeneration of the V2 seed
 * would silently map migrated clubs to a wrong / nonexistent seed PK. The guard
 * test {@code SeedReferenceUuidsSeedParityTest} asserts every entry produced here
 * equals the actual Flyway-seeded {@code t_country} / {@code t_club_state} row —
 * a seed reorder fails CI loudly rather than corrupting a migration. Keep this
 * derivation and the seed in lockstep; do not edit one without the other.
 */
public final class SeedReferenceUuids {

    /** 48-bit timestamp prefix, fixed at 2026-05-16T00:00:00Z (S-012 land date). */
    private static final long TIMESTAMP_MS = 1778889600000L;

    /** Per-table offset locked by {@code GenerateCanonicalUuids.TABLE_OFFSETS}. */
    private static final long COUNTRY_OFFSET = 1_000L;
    private static final long LANGUAGE_OFFSET = 2_000L;
    private static final long CLUB_STATE_OFFSET = 3_000L;

    /**
     * ISO 3166-1 alpha-2 codes in the EXACT ordinal order
     * {@code GenerateCanonicalUuids} emits them and the V2 {@code t_country}
     * INSERT preserves. The seed PK of code {@code countryIso2[i]} is
     * {@code uuidV7(COUNTRY_OFFSET + i)}.
     */
    private static final String[] COUNTRY_ISO2 = {
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

    /**
     * BCP-47 language codes in the EXACT ordinal order {@code GenerateCanonicalUuids}
     * emits them and the V2 {@code t_language} INSERT preserves. The seed PK of
     * {@code LANGUAGE_CODES[i]} is {@code uuidV7(LANGUAGE_OFFSET + i)}. The migration
     * producer joins a legacy {@code Languages.LanguageKey} (lower-cased) to this
     * code — {@code t_language} carries no {@code legacy_int_id}, so the resolution
     * is by code, not by the legacy INT.
     */
    private static final String[] LANGUAGE_CODES =
            {"de", "fr", "it", "en", "rm", "de-CH", "fr-CH", "it-CH"};

    /**
     * V2 lifecycle codes in the EXACT ordinal order {@code GenerateCanonicalUuids}
     * emits them and the V2 {@code t_club_state} INSERT preserves. The seed PK of
     * {@code CLUB_STATE_CODES[i]} is {@code uuidV7(CLUB_STATE_OFFSET + i)}.
     */
    private static final String[] CLUB_STATE_CODES = {"ACTIVE", "SUSPENDED", "CLOSED"};

    /** Natural key -> seed PK, computed once. Keys upper-cased / case-stable. */
    private static final Map<String, UUID> COUNTRY_BY_ISO2 =
            indexByNaturalKey(COUNTRY_ISO2, COUNTRY_OFFSET);
    private static final Map<String, UUID> CLUB_STATE_BY_CODE =
            indexByNaturalKey(CLUB_STATE_CODES, CLUB_STATE_OFFSET);

    /**
     * {@code code -> seed PK} keyed by the EXACT V2 seed code (e.g. {@code de},
     * {@code de-CH}) for the guard's natural-key assertion.
     */
    private static final Map<String, UUID> LANGUAGE_BY_CODE =
            indexByExactKey(LANGUAGE_CODES, LANGUAGE_OFFSET);

    /**
     * {@code lower(code) -> seed PK} for the producer lookup. The migration's
     * {@code LanguageMapper} lower-cases the legacy {@code LanguageKey} before
     * emitting it as the NDJSON {@code code}, so the resolver matches
     * case-insensitively (BCP-47 codes like {@code de-CH} round-trip through the
     * lower-cased form {@code de-ch}).
     */
    private static final Map<String, UUID> LANGUAGE_BY_LOWER_CODE =
            indexByLowerKey(LANGUAGE_CODES, LANGUAGE_OFFSET);

    private SeedReferenceUuids() {
    }

    /**
     * Seed {@code t_country.id} for an ISO 3166-1 alpha-2 code, or {@code null}
     * if the code is not in the seeded catalogue. Case-insensitive on the input.
     */
    public static @Nullable UUID countryByIso2(@Nullable String iso2) {
        return iso2 == null ? null : COUNTRY_BY_ISO2.get(iso2.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Seed {@code t_club_state.id} for a V2 lifecycle code (ACTIVE / SUSPENDED /
     * CLOSED), or {@code null} if the code is unknown.
     */
    public static @Nullable UUID clubStateByCode(@Nullable String code) {
        return code == null ? null : CLUB_STATE_BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Seed {@code t_language.id} for a BCP-47 code (matched case-insensitively
     * against the V2 seed, since {@code LanguageMapper} lower-cases the legacy
     * {@code LanguageKey}), or {@code null} if the code is not in the seed.
     */
    public static @Nullable UUID languageByCode(@Nullable String code) {
        return code == null ? null : LANGUAGE_BY_LOWER_CODE.get(code.trim().toLowerCase(Locale.ROOT));
    }

    /** Immutable {@code ISO2 -> seed PK} view (guard test asserts vs Flyway). */
    public static Map<String, UUID> countriesByIso2() {
        return Map.copyOf(COUNTRY_BY_ISO2);
    }

    /** Immutable {@code code -> seed PK} view (guard test asserts vs Flyway). */
    public static Map<String, UUID> clubStatesByCode() {
        return Map.copyOf(CLUB_STATE_BY_CODE);
    }

    /**
     * Immutable {@code code -> seed PK} view keyed by the EXACT V2 seed code
     * (e.g. {@code de}, {@code de-CH}); the guard test asserts this equals the
     * Flyway {@code t_language} seed.
     */
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

    /**
     * RFC 9562 UUIDv7, deterministic by construction — bit-identical to
     * {@code GenerateCanonicalUuids.uuidV7}: fixed 48-bit timestamp prefix, the
     * per-table counter encoded into rand_a (low 12 bits) + rand_b (low 62 bits),
     * version 7, variant 0b10.
     */
    static UUID uuidV7(long counter) {
        long ts = TIMESTAMP_MS & ((1L << 48) - 1L);
        long verAndRandA = (0x7L << 12) | (counter & 0xFFFL);
        long varAndRandB = (0b10L << 62) | (counter & ((1L << 62) - 1L));
        long high64 = (ts << 16) | verAndRandA;
        long low64 = varAndRandB;
        return new UUID(high64, low64);
    }
}
