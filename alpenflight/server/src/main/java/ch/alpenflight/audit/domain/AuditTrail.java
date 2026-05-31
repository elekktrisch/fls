package ch.alpenflight.audit.domain;

/**
 * Port for emitting mutation-audit events. The application-layer
 * implementation publishes an {@code AFTER_COMMIT REQUIRES_NEW} event so a
 * rolled-back business transaction never drops the audit row, and a 5xx
 * mid-transaction still surfaces via the synthetic-failure filter.
 *
 * <p>Wired into every mutating service method per the convention pinned in
 * {@link ch.alpenflight.audit.domain.AuditedBy} and enforced by the
 * {@code ControllerAuditCoverage} ArchUnit rule.
 *
 * <p>Recording is best-effort: a failure inside the listener (Postgres
 * down, serialization throw) does NOT roll the business transaction back —
 * the listener swallows + logs at ERROR. Audit gaps are caught by the
 * {@link ch.alpenflight.audit.web.RequestAuditFilter}'s synthetic
 * {@code failed=true} row when the response is non-2xx and the gap is
 * observable, and by operator review of error logs otherwise.
 */
public interface AuditTrail {

    /**
     * Record a successful mutation. Publishes a transactional event; the
     * row is committed to {@code t_mutation_audit_event} only after the
     * caller's transaction commits.
     */
    void record(AuditAction action, AuditedTarget target);

    /**
     * Record an observed-failed mutation (4xx / 5xx). Synthesised by the
     * {@link ch.alpenflight.audit.web.RequestAuditFilter} after the
     * response is committed; production code rarely calls this directly.
     *
     * @param httpStatus      observed HTTP status; ignored for non-HTTP origins.
     * @param failureReason   short cause label (exception class name + HTTP status).
     *                        Never the exception message — that may carry PII.
     */
    void recordFailed(AuditAction action,
                      AuditedTarget target,
                      int httpStatus,
                      String failureReason);
}
