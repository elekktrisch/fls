package ch.alpenflight.migrations.domain;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
