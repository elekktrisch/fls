package ch.fls.legacyextract;

import java.util.List;

/**
 * Static SQL-string guard. Every SQL the extractor sends over the wire passes
 * through {@link #assertSafe(String, String)} (metadata-category) or
 * {@link #assertAggregateSafe(String, String)} (aggregate-gated). Forbidden
 * patterns are rejected with a message naming the resource ID, so a future
 * committer can find the source without grep-spelunking.
 *
 * <p>Rationale: a runtime guard is a belt-and-braces defense around the
 * read-only DB role. Even if the role is misconfigured or the connection
 * uses a writer login by mistake, the guard makes accidental row-data reads
 * a NullPointerException-class fail rather than a silent leak.
 *
 * <p>The guard is pessimistic: false positives (an actually-safe query that
 * trips a pattern) are easier to fix than a false negative (a row-data read
 * that slips through).
 */
public final class SqlGuard {

    private SqlGuard() {}

    /** Assert a metadata-category SQL is safe. */
    public static void assertSafe(String resourceId, String sql) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /** Assert an aggregate-category SQL is safe (allows aggregate-only reads). */
    public static void assertAggregateSafe(String resourceId, String sql) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /** Walk the classpath {@code sql/} resources and assert each is safe. */
    public static ScanReport scanClasspathResources() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    public record ScanReport(int scannedCount, List<String> errors) {}
}
