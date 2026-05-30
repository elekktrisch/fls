package ch.alpenflight.migrations.domain;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Carrier for every failure the ingest pipeline surfaces to the caller.
 * Holds the bounded {@link BundleIngestErrorCode} + a free-form
 * {@code detail} string + a bag of {@code attributes} the exception handler
 * surfaces on the RFC 7807 ProblemDetail (e.g. {@code existingDeploymentId}
 * on a 409 {@code DEPLOYMENT_EXISTS}).
 */
public class BundleIngestException extends RuntimeException {

    private final BundleIngestErrorCode errorCode;
    private final Map<String, Object> attributes;

    public BundleIngestException(BundleIngestErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of(), null);
    }

    public BundleIngestException(BundleIngestErrorCode errorCode, String detail, @Nullable Throwable cause) {
        this(errorCode, detail, Map.of(), cause);
    }

    public BundleIngestException(BundleIngestErrorCode errorCode,
                                 String detail,
                                 Map<String, Object> attributes,
                                 @Nullable Throwable cause) {
        super(detail, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.attributes = Map.copyOf(attributes);
    }

    public BundleIngestErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }
}
