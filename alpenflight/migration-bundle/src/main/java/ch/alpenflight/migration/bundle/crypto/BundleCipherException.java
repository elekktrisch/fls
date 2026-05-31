package ch.alpenflight.migration.bundle.crypto;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Crypto-local failure carrier raised by {@link MigrationBundleCipher}
 * implementations. Lives entirely inside the shared bundle library so the
 * standalone export JAR can depend on the cipher without dragging in the
 * server-side ingest vocabulary ({@code BundleIngestErrorCode}).
 *
 * <p>The server boundary catches this and maps {@link Failure} onto its own
 * {@code BundleIngestErrorCode} so the RFC 7807 surface is unchanged.
 */
public class BundleCipherException extends RuntimeException {

    /**
     * Bounded set of cipher-layer failure modes. The server maps these onto
     * its ingest error codes; the JAR surfaces them directly.
     */
    public enum Failure {
        RSA_UNWRAP_FAILED,
        AEAD_TAG_FAILED,
        INTERNAL
    }

    private final Failure failure;

    public BundleCipherException(Failure failure, String message) {
        this(failure, message, null);
    }

    public BundleCipherException(Failure failure, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public Failure failure() {
        return failure;
    }
}
