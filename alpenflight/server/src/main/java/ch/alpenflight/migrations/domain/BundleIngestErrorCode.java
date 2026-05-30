package ch.alpenflight.migrations.domain;

/**
 * Bounded enum of every 4xx-mapped failure the S-141 ingest pipeline can
 * surface. The exception handler maps each value to a fixed HTTP status —
 * structured 4xx never 500. New codes added here MUST also be added to
 * the handler's status map and to the SPA's typed-error switch.
 *
 * <p>5xx surfaces are reserved for {@link #INGEST_INTERNAL_ERROR} —
 * unexpected programming faults inside the pipeline, not user-driven.
 */
public enum BundleIngestErrorCode {

    BUNDLE_TOO_LARGE,
    BUNDLE_FORBIDDEN,
    BUNDLE_HANDSHAKE_EXPIRED,
    BUNDLE_ALREADY_CONSUMED,
    BUNDLE_PRIOR_RUN_FAILED,
    BUNDLE_INGEST_IN_PROGRESS,
    BUNDLE_HEADER_MALFORMED,
    BUNDLE_DECRYPT_RSA_UNWRAP_FAILED,
    BUNDLE_DECRYPT_AEAD_TAG_FAILED,
    BUNDLE_TRUNCATED,
    BUNDLE_TAR_PARSE_FAILED,
    BUNDLE_MISSING_ENTRIES,
    BUNDLE_EXTRA_ENTRIES,
    BUNDLE_CROSS_TENANT_FK_LEAK,
    BUNDLE_TIMEOUT,
    MANIFEST_INVALID,
    MANIFEST_EMPTY_CLUBS,
    SCHEMA_VERSION_MISMATCH,
    MAPPER_NOT_AVAILABLE,
    MAPPER_CONSTRAINT_VIOLATION,
    NDJSON_PARSE_FAILED,
    DEPLOYMENT_EXISTS,
    HANDSHAKE_INGEST_IN_PROGRESS,
    DATABASE_CAPACITY_EXCEEDED,
    INGEST_INTERNAL_ERROR
}
