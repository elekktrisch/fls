package ch.alpenflight.audit.domain;

public interface AuditTrail {

    void record(AuditAction action, AuditedTarget target);

    void recordFailed(AuditAction action,
                      AuditedTarget target,
                      int httpStatus,
                      String failureReason);
}
