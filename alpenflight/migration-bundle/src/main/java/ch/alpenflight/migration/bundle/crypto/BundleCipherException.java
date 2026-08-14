package ch.alpenflight.migration.bundle.crypto;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public class BundleCipherException extends RuntimeException {

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
