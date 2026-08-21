package ch.alpenflight.audit.domain;

public interface AuditTrail {

    void record(AuditAction action, AuditedTarget target);

    void recordAnonymousPublicSubmission(AuditAction action, AuditedTarget target, String clientIp);

    void recordFailed(AuditAction action,
                      AuditedTarget target,
                      int httpStatus,
                      String failureReason);
}
