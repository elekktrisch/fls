package ch.alpenflight.migration.bundle;

import org.jspecify.annotations.Nullable;

/**
 * Stateless coercion helpers shared by every mapper. Static methods only — the
 * per-row allocation budget in the mapper hot path is zero.
 */
public final class Coercions {

    private Coercions() { }

    /** Null preserves the third state required by the S-129 string-enum encoding. */
    public static String bitToTriStateTag(@Nullable Boolean value) {
        if (value == null) {
            return "UNKNOWN";
        }
        return value ? "YES" : "NO";
    }
}
