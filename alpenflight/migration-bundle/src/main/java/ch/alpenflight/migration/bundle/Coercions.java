package ch.alpenflight.migration.bundle;

import java.util.Locale;
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

    /** Legacy {@code bit} (boolean) → string-enum tag. {@code null} round-trips. */
    public static @Nullable String boolToEnumTag(@Nullable Boolean value) {
        if (value == null) {
            return null;
        }
        return value ? "YES" : "NO";
    }

    /** Lowercase a non-null tag with a {@link Locale#ROOT} guarantee. */
    public static String lowerRoot(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
