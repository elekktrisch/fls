package ch.alpenflight.migration.bundle;

import org.jspecify.annotations.Nullable;

/**
 * Stateless value-coercion helpers shared by every mapper.
 *
 * <p>Mappers operate on {@link java.sql.ResultSet} (export side) or Jackson
 * {@code JsonNode} (ingest side) and bind directly to {@code PreparedStatement}
 * parameters — no intermediate POJOs in the hot path. Coercions live here as
 * {@code static} methods to keep the per-row allocation budget at zero.
 */
public final class Coercions {

    private Coercions() { }

    /**
     * Legacy {@code bit} (boolean) → tri-state enum tag {@code YES} / {@code NO} /
     * {@code UNKNOWN}. Null input maps to {@code UNKNOWN} so a legacy nullable
     * bit column round-trips through the S-129 string-enum encoding without
     * losing the third state.
     */
    public static String boolToEnumTag(@Nullable Boolean value) {
        if (value == null) {
            return "UNKNOWN";
        }
        return value ? "YES" : "NO";
    }
}
