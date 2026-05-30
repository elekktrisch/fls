package ch.alpenflight.migrations.web;

import ch.alpenflight.migrations.application.UnknownPrincipalException;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * S-141 controller-scoped exception translator. Maps each
 * {@link BundleIngestErrorCode} to a fixed HTTP status; the bounded
 * enum means a new code surfaces here as a missing-mapping log line +
 * a defensive 500, rather than silently leaking the exception message.
 */
@RestControllerAdvice(assignableTypes = MigrationBundleController.class)
class MigrationBundleExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationBundleExceptionHandler.class);

    private static final Map<BundleIngestErrorCode, HttpStatus> STATUS_BY_CODE = buildStatusMap();

    @ExceptionHandler(BundleIngestException.class)
    ResponseEntity<ProblemDetail> onBundleIngest(BundleIngestException e) {
        HttpStatus status = STATUS_BY_CODE.getOrDefault(e.getErrorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
        if (status == HttpStatus.INTERNAL_SERVER_ERROR
                && e.getErrorCode() != BundleIngestErrorCode.INGEST_INTERNAL_ERROR) {
            LOG.error("MigrationBundle: missing status mapping for errorCode={}; defaulting to 500",
                    e.getErrorCode(), e);
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setTitle("Migration bundle ingest");
        pd.setProperty("errorCode", e.getErrorCode().name());
        for (Map.Entry<String, Object> attribute : e.getAttributes().entrySet()) {
            pd.setProperty(attribute.getKey(), attribute.getValue());
        }
        return ResponseEntity.status(status).body(pd);
    }

    @ExceptionHandler(UnknownPrincipalException.class)
    ProblemDetail onUnknownPrincipal(UnknownPrincipalException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Unknown principal");
        pd.setProperty("errorCode", BundleIngestErrorCode.BUNDLE_FORBIDDEN.name());
        return pd;
    }

    private static Map<BundleIngestErrorCode, HttpStatus> buildStatusMap() {
        EnumMap<BundleIngestErrorCode, HttpStatus> map = new EnumMap<>(BundleIngestErrorCode.class);
        map.put(BundleIngestErrorCode.BUNDLE_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE);
        map.put(BundleIngestErrorCode.BUNDLE_FORBIDDEN, HttpStatus.FORBIDDEN);
        map.put(BundleIngestErrorCode.BUNDLE_HANDSHAKE_EXPIRED, HttpStatus.GONE);
        map.put(BundleIngestErrorCode.BUNDLE_ALREADY_CONSUMED, HttpStatus.CONFLICT);
        map.put(BundleIngestErrorCode.BUNDLE_PRIOR_RUN_FAILED, HttpStatus.CONFLICT);
        map.put(BundleIngestErrorCode.BUNDLE_INGEST_IN_PROGRESS, HttpStatus.CONFLICT);
        map.put(BundleIngestErrorCode.BUNDLE_HEADER_MALFORMED, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.BUNDLE_DECRYPT_RSA_UNWRAP_FAILED, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.BUNDLE_DECRYPT_AEAD_TAG_FAILED, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.BUNDLE_TRUNCATED, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.BUNDLE_TAR_PARSE_FAILED, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.BUNDLE_MISSING_ENTRIES, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.BUNDLE_EXTRA_ENTRIES, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.BUNDLE_TIMEOUT, HttpStatus.REQUEST_TIMEOUT);
        map.put(BundleIngestErrorCode.MANIFEST_INVALID, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.MANIFEST_EMPTY_CLUBS, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.SCHEMA_VERSION_MISMATCH, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.MAPPER_NOT_AVAILABLE, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.MAPPER_CONSTRAINT_VIOLATION, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.NDJSON_PARSE_FAILED, HttpStatus.BAD_REQUEST);
        map.put(BundleIngestErrorCode.DEPLOYMENT_EXISTS, HttpStatus.CONFLICT);
        map.put(BundleIngestErrorCode.HANDSHAKE_INGEST_IN_PROGRESS, HttpStatus.CONFLICT);
        map.put(BundleIngestErrorCode.DATABASE_CAPACITY_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS);
        map.put(BundleIngestErrorCode.INGEST_INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
        return Map.copyOf(map);
    }
}
